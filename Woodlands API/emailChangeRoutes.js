
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

module.exports = function registerEmailChangeRoutes(app, supabase, auth) {
    const { requireAuth, requireRole, isAdmin } = auth;

    function dbError(res, error, context) {
        console.error(`${context}:`, error?.message || error);
        return res.status(500).json({
            error: 'Something went wrong. Please try again.'
        });
    }

    // Admin creates a request. The user's email is NOT changed here.
    app.post('/api/email-change-requests', requireRole('Admin'), async (req, res) => {
        try {
            const userId = String(req.body?.user_id || '').trim();
            const requestedEmail = String(req.body?.requested_email || '').trim();

            if (!userId || !EMAIL_RE.test(requestedEmail)) {
                return res.status(400).json({
                    error: 'A valid user ID and requested email are required.'
                });
            }

            const { data: target, error: targetError } = await supabase
                .from('app_users')
                .select('id, email, full_name')
                .eq('id', userId)
                .maybeSingle();

            if (targetError) return dbError(res, targetError, 'Email change target lookup');
            if (!target) return res.status(404).json({ error: 'User account not found.' });

            const currentEmail = String(target.email || '').trim();

            if (currentEmail.toLowerCase() === requestedEmail.toLowerCase()) {
                return res.status(400).json({
                    error: 'The requested email is already on this account.'
                });
            }

            // Check profile records for an existing owner of the requested address.
            const { data: existing, error: existingError } = await supabase
                .from('app_users')
                .select('id')
                .ilike('email', requestedEmail)
                .neq('id', userId)
                .limit(1);

            if (existingError) return dbError(res, existingError, 'Email availability check');
            if (existing?.length) {
                return res.status(409).json({
                    error: 'That email address is already assigned to another account.'
                });
            }

            const { data: pending, error: pendingError } = await supabase
                .from('email_change_requests')
                .select('id')
                .eq('user_id', userId)
                .eq('status', 'pending')
                .maybeSingle();

            if (pendingError) return dbError(res, pendingError, 'Pending email request lookup');
            if (pending) {
                return res.status(409).json({
                    error: 'This user already has a pending email change request.'
                });
            }

            const { data: request, error: insertError } = await supabase
                .from('email_change_requests')
                .insert([{
                    user_id: userId,
                    requested_by: req.user.id,
                    current_email: currentEmail,
                    requested_email: requestedEmail,
                    status: 'pending'
                }])
                .select('id, user_id, current_email, requested_email, status, created_at')
                .single();

            if (insertError) {
                if (insertError.code === '23505') {
                    return res.status(409).json({
                        error: 'This user already has a pending email change request.'
                    });
                }
                return dbError(res, insertError, 'Email change request creation');
            }

            auth.audit('email_change_requested', req, {
                target_user: userId,
                request_id: request.id
            });

            return res.status(201).json({
                message: 'Email change request created. The user must sign in and approve it.',
                request
            });
        } catch (error) {
            console.error('Email change request error:', error.message);
            return res.status(500).json({ error: 'Unable to create the email change request.' });
        }
    });

    // A signed-in user can only retrieve their own pending request.
    app.get('/api/email-change-requests/me', requireAuth, async (req, res) => {
        try {
            const { data, error } = await supabase
                .from('email_change_requests')
                .select('id, current_email, requested_email, status, created_at')
                .eq('user_id', req.user.id)
                .eq('status', 'pending')
                .order('created_at', { ascending: false })
                .limit(1);

            if (error) return dbError(res, error, 'Pending email request retrieval');
            return res.json(data?.[0] || null);
        } catch (error) {
            console.error('Pending email request error:', error.message);
            return res.status(500).json({ error: 'Unable to retrieve your email change request.' });
        }
    });

    // Only the account holder can accept or reject their own request.
    app.post('/api/email-change-requests/:id/decision', requireAuth, async (req, res) => {
        const decision = String(req.body?.decision || '').trim().toLowerCase();

        if (!['accept', 'reject'].includes(decision)) {
            return res.status(400).json({ error: 'Decision must be accept or reject.' });
        }

        try {
            // Claim the pending request first. Only one request can transition to processing.
            const { data: claimed, error: claimError } = await supabase
                .from('email_change_requests')
                .update({ status: decision === 'accept' ? 'processing' : 'rejected' })
                .eq('id', req.params.id)
                .eq('user_id', req.user.id)
                .eq('status', 'pending')
                .select('*')
                .maybeSingle();

            if (claimError) return dbError(res, claimError, 'Email change decision claim');
            if (!claimed) {
                return res.status(404).json({
                    error: 'No pending email change request was found for your account.'
                });
            }

            if (decision === 'reject') {
                const { error: rejectedError } = await supabase
                    .from('email_change_requests')
                    .update({ decided_at: new Date().toISOString() })
                    .eq('id', claimed.id)
                    .eq('user_id', req.user.id)
                    .eq('status', 'rejected');

                if (rejectedError) return dbError(res, rejectedError, 'Email change rejection');

                auth.audit('email_change_rejected', req, { request_id: claimed.id });
                return res.json({ message: 'Email change request rejected. Your email address was not changed.' });
            }

            // Confirm that the profile still has the email recorded when the request was made.
            const { data: profile, error: profileError } = await supabase
                .from('app_users')
                .select('id, email')
                .eq('id', req.user.id)
                .maybeSingle();

            if (profileError || !profile) {
                await supabase.from('email_change_requests')
                    .update({ status: 'pending' })
                    .eq('id', claimed.id)
                    .eq('status', 'processing');

                if (profileError) return dbError(res, profileError, 'Email change profile lookup');
                return res.status(404).json({ error: 'Your account profile could not be found.' });
            }

            if (String(profile.email || '').toLowerCase() !== String(claimed.current_email || '').toLowerCase()) {
                await supabase.from('email_change_requests')
                    .update({ status: 'cancelled', decided_at: new Date().toISOString() })
                    .eq('id', claimed.id)
                    .eq('status', 'processing');

                return res.status(409).json({
                    error: 'Your account email has changed since this request was created. Ask an administrator to submit a new request.'
                });
            }

            // Update Supabase Auth first. Supabase enforces Auth email uniqueness.
            const { data: authUpdate, error: authUpdateError } = await supabase.auth.admin.updateUserById(
                req.user.id,
                {
                    email: claimed.requested_email,
                    email_confirm: true
                }
            );

            if (authUpdateError || !authUpdate?.user) {
                await supabase.from('email_change_requests')
                    .update({ status: 'pending' })
                    .eq('id', claimed.id)
                    .eq('status', 'processing');

                auth.audit('email_change_auth_update_failed', req, { request_id: claimed.id });
                console.error('Supabase Auth email update failed:', authUpdateError?.message);
                return res.status(409).json({
                    error: 'Supabase could not update that email address. It may already be in use. Your existing email remains unchanged.'
                });
            }

            // Keep the profile in sync with Supabase Auth.
            const { error: profileUpdateError } = await supabase
                .from('app_users')
                .update({ email: claimed.requested_email })
                .eq('id', req.user.id);

            if (profileUpdateError) {
                // Compensating action: revert Auth to the previous address.
                const { error: rollbackError } = await supabase.auth.admin.updateUserById(
                    req.user.id,
                    {
                        email: claimed.current_email,
                        email_confirm: true
                    }
                );

                if (rollbackError) {
                    // Keep status as processing for manual investigation; do not pretend rollback worked.
                    console.error('CRITICAL: Auth email rollback failed:', rollbackError.message);
                    auth.audit('email_change_rollback_failed', req, { request_id: claimed.id });
                    return res.status(500).json({
                        error: 'The email update needs administrator attention. Do not retry this request; contact support.'
                    });
                }

                await supabase.from('email_change_requests')
                    .update({ status: 'pending' })
                    .eq('id', claimed.id)
                    .eq('status', 'processing');

                return dbError(res, profileUpdateError, 'Email change profile update');
            }

            const { error: finishError } = await supabase
                .from('email_change_requests')
                .update({
                    status: 'accepted',
                    decided_at: new Date().toISOString()
                })
                .eq('id', claimed.id)
                .eq('status', 'processing');

            if (finishError) {
                // The two email stores are already updated. Keep processing for reconciliation.
                console.error('Email changed but request finalisation failed:', finishError.message);
                auth.audit('email_change_finalisation_failed', req, { request_id: claimed.id });
                return res.status(500).json({
                    error: 'Your email was changed, but the request status could not be finalised. Contact support before retrying.'
                });
            }

            auth.audit('email_change_accepted', req, { request_id: claimed.id });
            return res.json({
                message: 'Your email address has been updated. Please sign in again using your new email address.'
            });
        } catch (error) {
            console.error('Email change decision error:', error.message);
            return res.status(500).json({ error: 'Unable to process the email change decision.' });
        }
    });
};
