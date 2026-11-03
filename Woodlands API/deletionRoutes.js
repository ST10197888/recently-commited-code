const crypto = require('crypto');
const { sendRestoreCodeEmail } = require('./emailService');

const CODE_MINUTES = 15;
const MAX_ATTEMPTS = 5;
const RESEND_SECONDS = 60;

function normaliseEmail(value) {
    return typeof value === 'string' ? value.trim().toLowerCase() : '';
}

function escapeLike(value) {
    return value.replace(/[\\%_]/g, '\\$&');
}

function hashCode(code, email) {
    return crypto.createHash('sha256').update(`${email}:${code}`).digest('hex');
}

function sameHash(a, b) {
    const left = Buffer.from(String(a || ''));
    const right = Buffer.from(String(b || ''));
    return left.length === right.length && crypto.timingSafeEqual(left, right);
}

// Every route here needs a signed-in user. Who may do what:

//   create request   : the signed-in user, for THEIR OWN account only
//   list requests    : Admin sees all, everyone else sees only their own
//   cancel           : the owner or an Admin
//   approve / reject : Admin only
//   archive routes   : the signed-in user, for THEIR OWN e-mail only


module.exports = function registerDeletionRoutes(app, supabase, auth) {
    const { requireAuth, requireRole, isAdmin } = auth;

    function dbError(res, error) {
        console.error('Database error:', error.message);
        return res.status(500).json({ error: 'Something went wrong. Please try again.' });
    }

    function resolveRequest(id, status, fields) {
        return supabase
            .from('deletion_requests')
            .update({ status, resolved_at: new Date().toISOString(), ...fields })
            .eq('id', id)
            .eq('status', 'Pending')
            .select();
    }

    app.post('/api/deletion-requests', requireAuth, async (req, res) => {
        const body = req.body || {};
        const me = req.user; // identity comes from the token, never from the request body

        // The mobile app doesn't know the user's recovery setting, so read it here when not supplied.
        let keep = body.keep_data === true;
        if (body.keep_data === undefined) {
            const { data: profile } = await supabase.from('app_users')
                .select('keep_data_on_delete').eq('id', me.id).maybeSingle();
            keep = !!(profile && profile.keep_data_on_delete);
        }

        const { data, error } = await supabase
            .from('deletion_requests')
            .insert([{
                user_id: me.id,
                email: normaliseEmail(me.email),
                full_name: me.full_name || '',
                role: me.role || '',
                keep_data: keep,
                message: String(body.message || '').trim().slice(0, 1000) || null
            }])
            .select();

        if (error) {
            if (error.code === '23505') {
                return res.status(409).json({ error: 'A deletion request is already pending for this account.' });
            }
            return dbError(res, error);
        }

        res.status(201).json(data);
    });

    app.get('/api/deletion-requests', requireAuth, async (req, res) => {
        let query = supabase.from('deletion_requests').select('*').order('created_at', { ascending: false });

        if (typeof req.query.status === 'string' && req.query.status.trim()) {
            query = query.eq('status', req.query.status.trim());
        }
        if (isAdmin(req.user)) {
            if (typeof req.query.user_id === 'string' && req.query.user_id.trim()) {
                query = query.eq('user_id', req.query.user_id.trim());
            }
        } else {
            query = query.eq('user_id', req.user.id); // forced for non-admins
        }

        const { data, error } = await query;
        if (error) return dbError(res, error);
        res.json(data);
    });

    app.put('/api/deletion-requests/:id/reject', requireRole('Admin'), async (req, res) => {
        const body = req.body || {};
        const { data, error } = await resolveRequest(req.params.id, 'Rejected', {
            admin_note: String(body.admin_note || '').slice(0, 500),
            resolved_by: req.user.email
        });

        if (error) return dbError(res, error);
        if (!data || data.length === 0) return res.status(404).json({ error: 'No pending request found.' });
        res.json(data);
    });

    app.put('/api/deletion-requests/:id/cancel', requireAuth, async (req, res) => {
        const { data: existing, error: findError } = await supabase
            .from('deletion_requests').select('user_id').eq('id', req.params.id).maybeSingle();
        if (findError) return dbError(res, findError);
        if (!existing) return res.status(404).json({ error: 'No pending request found.' });
        if (!isAdmin(req.user) && String(existing.user_id) !== String(req.user.id)) {
            auth.audit('forbidden', req, { reason: 'cancel_other_users_request' });
            return res.status(403).json({ error: 'You can only cancel your own request.' });
        }

        const { data, error } = await resolveRequest(req.params.id, 'Cancelled', {});
        if (error) return dbError(res, error);
        if (!data || data.length === 0) return res.status(404).json({ error: 'No pending request found.' });
        res.json(data);
    });

    app.post('/api/deletion-requests/:id/approve', requireRole('Admin'), async (req, res) => {
        try {
            const { data: request, error: requestError } = await supabase
                .from('deletion_requests')
                .select('*')
                .eq('id', req.params.id)
                .eq('status', 'Pending')
                .maybeSingle();

            if (requestError) return dbError(res, requestError);
            if (!request) return res.status(404).json({ error: 'No pending request found.' });

            const { data: user, error: userError } = await supabase
                .from('app_users')
                .select('*')
                .eq('id', request.user_id)
                .maybeSingle();

            if (userError) return dbError(res, userError);
            if (user && user.role === 'Admin') {
                return res.status(400).json({ error: 'Administrator accounts cannot be deleted.' });
            }

            const email = normaliseEmail(request.email);

            const { data: quotes, error: quotesError } = await supabase
                .from('quote_requests')
                .select('*')
                .ilike('email', escapeLike(email));

            if (quotesError) return dbError(res, quotesError);

            if (request.keep_data) {
                const archive = {
                    email,
                    data: {
                        archived_at: new Date().toISOString(),
                        profile: user ? {
                            full_name: user.full_name,
                            phone: user.phone,
                            role: user.role,
                            branch: user.branch,
                            created_at: user.created_at
                        } : null,
                        quotes: quotes || []
                    },
                    archived_at: new Date().toISOString(),
                    code_hash: null,
                    code_expires_at: null,
                    code_sent_at: null,
                    code_attempts: 0
                };

                const { error: archiveError } = await supabase
                    .from('account_archive')
                    .upsert([archive], { onConflict: 'email' });

                if (archiveError) return dbError(res, archiveError);
            }

            const quoteIds = (quotes || []).map(q => q.id);
            if (quoteIds.length > 0) {
                const { error } = await supabase.from('quote_requests').delete().in('id', quoteIds);
                if (error) return dbError(res, error);
            }

            if (user) {
                const { error } = await supabase.from('app_users').delete().eq('id', request.user_id);
                if (error) return dbError(res, error);
            }

            const { error: authError } = await supabase.auth.admin.deleteUser(request.user_id);
            if (authError && !/not found/i.test(authError.message)) {
                console.error('Auth delete error:', authError.message);
                return res.status(500).json({ error: 'The login could not be removed. Please try again.' });
            }

            const { error: resolveError } = await resolveRequest(request.id, 'Approved', {
                resolved_by: req.user.email
            });
            if (resolveError) return dbError(res, resolveError);

            res.json({
                message: 'Account deleted.',
                archived: request.keep_data === true,
                quotes_removed: quoteIds.length
            });
        } catch (ex) {
            console.error('Approve deletion error:', ex.message);
            res.status(500).json({ error: 'Something went wrong. Please try again.' });
        }
    });

    // The ?email= value is ignored - the signed-in user's own e-mail is always used, so nobody
    // can probe whether someone else has saved data.
    app.get('/api/account-archive/available', requireAuth, async (req, res) => {
        const email = normaliseEmail(req.user.email);
        if (!email) return res.json({ available: false });

        const { data, error } = await supabase
            .from('account_archive')
            .select('id')
            .eq('email', email)
            .maybeSingle();

        if (error) return dbError(res, error);
        res.json({ available: !!data });
    });

    app.post('/api/account-archive/request-code', requireAuth, async (req, res) => {
        try {
            const email = normaliseEmail(req.user.email);
            if (!email) return res.status(400).json({ error: 'Email is required.' });

            const { data: archive, error } = await supabase
                .from('account_archive')
                .select('id, code_sent_at')
                .eq('email', email)
                .maybeSingle();

            if (error) return dbError(res, error);
            if (!archive) return res.status(404).json({ error: 'No saved data was found for this email.' });

            if (archive.code_sent_at && Date.now() - new Date(archive.code_sent_at).getTime() < RESEND_SECONDS * 1000) {
                return res.status(429).json({ error: 'Please wait a minute before requesting another code.' });
            }

            const code = String(crypto.randomInt(100000, 1000000));

            const { error: updateError } = await supabase
                .from('account_archive')
                .update({
                    code_hash: hashCode(code, email),
                    code_expires_at: new Date(Date.now() + CODE_MINUTES * 60 * 1000).toISOString(),
                    code_sent_at: new Date().toISOString(),
                    code_attempts: 0
                })
                .eq('id', archive.id);

            if (updateError) return dbError(res, updateError);

            const sent = await sendRestoreCodeEmail(email, code, CODE_MINUTES);
            if (!sent || !sent.success) {
                return res.status(502).json({ error: 'The email could not be sent. Please try again later.' });
            }

            res.json({ message: 'Code sent.' });
        } catch (ex) {
            console.error('Request code error:', ex.message);
            res.status(500).json({ error: 'Something went wrong. Please try again.' });
        }
    });

    app.post('/api/account-archive/restore', requireAuth, async (req, res) => {
        try {
            const body = req.body || {};
            const code = typeof body.code === 'string' ? body.code.trim() : '';
            const userId = req.user.id;
            const email = normaliseEmail(req.user.email);

            if (!code) {
                return res.status(400).json({ error: 'A code is required.' });
            }

            const { data: user, error: userError } = await supabase
                .from('app_users')
                .select('*')
                .eq('id', userId)
                .maybeSingle();

            if (userError) return dbError(res, userError);
            if (!user || normaliseEmail(user.email) !== email) {
                return res.status(403).json({ error: 'This account does not match the saved data.' });
            }

            const { data: archive, error: archiveError } = await supabase
                .from('account_archive')
                .select('*')
                .eq('email', email)
                .maybeSingle();

            if (archiveError) return dbError(res, archiveError);
            if (!archive) return res.status(404).json({ error: 'No saved data was found for this email.' });

            if (!archive.code_hash || !archive.code_expires_at || new Date(archive.code_expires_at) < new Date()) {
                return res.status(400).json({ error: 'Your code has expired. Request a new one.' });
            }
            if (archive.code_attempts >= MAX_ATTEMPTS) {
                return res.status(429).json({ error: 'Too many attempts. Request a new code.' });
            }
            if (!sameHash(archive.code_hash, hashCode(code, email))) {
                await supabase
                    .from('account_archive')
                    .update({ code_attempts: archive.code_attempts + 1 })
                    .eq('id', archive.id);
                return res.status(400).json({ error: 'That code is not correct.' });
            }

            const saved = archive.data || {};
            const quotes = Array.isArray(saved.quotes) ? saved.quotes : [];

            if (quotes.length > 0) {
                const { error } = await supabase
                    .from('quote_requests')
                    .upsert(quotes, { onConflict: 'id', ignoreDuplicates: true });
                if (error) return dbError(res, error);
            }

            const savedPhone = saved.profile && saved.profile.phone;
            const restoredPhone = !!savedPhone && !user.phone;
            if (restoredPhone) {
                const { error } = await supabase.from('app_users').update({ phone: savedPhone }).eq('id', userId);
                if (error) return dbError(res, error);
            }

            const { error: removeError } = await supabase.from('account_archive').delete().eq('id', archive.id);
            if (removeError) return dbError(res, removeError);

            res.json({ message: 'Data restored.', restored: { quotes: quotes.length, phone: restoredPhone } });
        } catch (ex) {
            console.error('Restore error:', ex.message);
            res.status(500).json({ error: 'Something went wrong. Please try again.' });
        }
    });
};