require('dotenv').config();
const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const { rateLimit } = require('express-rate-limit');
const { createClient } = require('@supabase/supabase-js');
const createAuth = require('./auth');
const registerDeletionRoutes = require('./deletionRoutes');
const createBackupService = require('./backupService');
const {
    sendCustomerQuoteReceivedEmail,
    sendAdminNewQuoteEmail,
    sendBranchManagerNewQuoteEmail,
    sendCustomerStatusEmail,
    sendWelcomeEmail,
    sendAdminNewRegistrationEmail
} = require('./emailService');

const app = express();
app.set('trust proxy', 1); // Render sits behind a proxy - needed for correct client IPs


// SECURITY MIDDLEWARE

app.use(helmet()); // standard security headers

// Browsers may only call the API from origins listed in ALLOWED_ORIGINS (comma separated).

// The website calls the API server-to-server and the Android app sends no Origin header, so non are affected.

const allowedOrigins = (process.env.ALLOWED_ORIGINS || '').split(',').map(s => s.trim()).filter(Boolean);
app.use(cors({
    origin: (origin, cb) => cb(null, !origin || allowedOrigins.includes(origin)),
    methods: ['GET', 'POST', 'PUT', 'DELETE'],
    allowedHeaders: ['Content-Type', 'Authorization']
}));

// Only the branch-image upload needs a large body; everything else is capped at 1 MB.
app.use('/api/admin/import', express.json({ limit: '50mb' }));
app.use(express.json({ limit: '1mb' }));

// Rate limits (brute force / spam protection)
const limitMessage = { error: 'Too many requests. Please try again later.' };
const emailKey = (req) => 'e:' + String((req.body && req.body.email) || 'none').trim().toLowerCase();
// Failed logins only, per e-mail address (the website's server shares one IP, so IP-keying would
// throttle every customer together).
const loginLimiter = rateLimit({
    windowMs: 15 * 60 * 1000, limit: 10, skipSuccessfulRequests: true,
    keyGenerator: emailKey, standardHeaders: 'draft-7', legacyHeaders: false, message: limitMessage,
    handler: (req, res, next, options) => { auth.audit('login_rate_limited', req); res.status(429).json(options.message); }
});
const registerLimiter = rateLimit({ windowMs: 60 * 60 * 1000, limit: 30, standardHeaders: 'draft-7', legacyHeaders: false, message: limitMessage });
const quoteLimiter = rateLimit({ windowMs: 60 * 60 * 1000, limit: 60, standardHeaders: 'draft-7', legacyHeaders: false, message: limitMessage });


// SUPABASE + AUTH

const clientOptions = {
    auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false }
};
const supabase = createClient(process.env.SUPABASE_URL, process.env.SUPABASE_KEY, clientOptions);
const auth = createAuth(supabase);
const { requireAuth, optionalAuth, requireRole, isAdmin, isManager, isStaff, branchOf } = auth;

function createAuthClient() {
    return createClient(process.env.SUPABASE_URL, process.env.SUPABASE_KEY, clientOptions);
}


// HELPERS

const ROLES = ['Admin', 'Manager (Soweto)', 'Manager (Roodepoort)', 'Manager (Randfontein)', 'Customer'];
const QUOTE_STATUSES = ['Pending', 'In Progress', 'Completed', 'Cancelled'];
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const escapeLike = (v) => v.replace(/[\\%_]/g, '\\$&');
const str = (v, max) => (typeof v === 'string' ? v.trim().slice(0, max) : v == null ? null : String(v).slice(0, max));
const has = (obj, k) => Object.prototype.hasOwnProperty.call(obj, k);

// Log the real database error, send the client a generic message (no table/column names leaked)
function dbError(res, error) {
    console.error('Database error:', error.message);
    return res.status(500).json({ error: 'Something went wrong. Please try again.' });
}

// Helper: parse a JSON string into an array (for products' gallery/features/finishes columns)
function safeParse(value) {
    if (Array.isArray(value)) return value;
    if (typeof value !== 'string' || !value.trim()) return [];
    try {
        const parsed = JSON.parse(value);
        return Array.isArray(parsed) ? parsed : [];
    } catch {
        return [];
    }
}

const cleanProduct = (p) => ({ ...p, gallery: safeParse(p.gallery), features: safeParse(p.features), finishes: safeParse(p.finishes) });

// Registers GET (public) + POST/PUT/DELETE (role protected) for a simple table
function registerCrud({ path, table, order, ascending = true, writeRoles, label, getOne = false }) {
    app.get(`/api/${path}`, async (req, res) => {
        const { data, error } = await supabase.from(table).select('*').order(order, { ascending });
        if (error) return dbError(res, error);
        res.json(data);
    });
    if (getOne) {
        app.get(`/api/${path}/:id`, async (req, res) => {
            const { data, error } = await supabase.from(table).select('*').eq('id', req.params.id).single();
            if (error) return dbError(res, error);
            res.json(data);
        });
    }
    app.post(`/api/${path}`, requireRole(...writeRoles), async (req, res) => {
        const { data, error } = await supabase.from(table).insert([req.body]).select();
        if (error) return dbError(res, error);
        res.status(201).json(data);
    });
    app.put(`/api/${path}/:id`, requireRole(...writeRoles), async (req, res) => {
        const { data, error } = await supabase.from(table).update(req.body).eq('id', req.params.id).select();
        if (error) return dbError(res, error);
        res.json(data);
    });
    app.delete(`/api/${path}/:id`, requireRole(...writeRoles), async (req, res) => {
        const { data, error } = await supabase.from(table).delete().eq('id', req.params.id).select();
        if (error) return dbError(res, error);
        res.json({ message: `${label} deleted`, data });
    });
}


// HEALTH CHECK (public, no data) - also handy for uptime pingers that keep Render awake

app.get('/health', (req, res) => res.json({ status: 'ok', time: new Date().toISOString() }));


// LOGIN / REGISTER (public)

app.post('/api/auth/login', loginLimiter, async (req, res) => {
    const { email, password } = req.body || {};
    if (typeof email !== 'string' || typeof password !== 'string' || !email || !password) {
        return res.status(400).json({ error: 'Email and password are required.' });
    }
    try {
        const { data: authData, error: authError } = await createAuthClient().auth.signInWithPassword({ email, password });
        if (authError || !authData.user) {
            auth.audit('login_failed', req, { email: email.trim().toLowerCase() });
            return res.status(401).json({ error: 'Invalid email or password.' });
        }

        const { data: profile, error: profileError } = await supabase
            .from('app_users')
            .select('*')
            .eq('id', authData.user.id)
            .single();

        if (profileError || !profile) {
            return res.status(404).json({ error: 'User profile not found.' });
        }
        if (!profile.active) {
            return res.status(403).json({ error: 'This account is currently inactive.' });
        }

        // "token" is what the website and the Android app send as "Authorization: Bearer <token>"
        res.json({ user: profile, token: auth.signToken(profile) });
    } catch (ex) {
        console.error('Login error:', ex.message);
        res.status(500).json({ error: 'Login failed. Please try again.' });
    }
});

app.post('/api/auth/register', registerLimiter, async (req, res) => {
    const { fullName, email, password, phone } = req.body || {};
    if (typeof fullName !== 'string' || !fullName.trim() || typeof email !== 'string' || !EMAIL_RE.test(email.trim()) ||
        typeof password !== 'string' || password.length < 8) {
        return res.status(400).json({ error: 'A name, a valid email and a password of at least 8 characters are required.' });
    }
    try {
        const { data: authData, error: authError } = await supabase.auth.admin.createUser({
            email: email.trim(),
            password: password,
            email_confirm: true
        });

        if (authError || !authData.user) {
            return res.status(400).json({ error: authError?.message || 'Registration failed.' });
        }

        // Public registration can ONLY create a Customer - role/branch are never taken from the request
        const newUser = {
            id: authData.user.id,
            full_name: str(fullName, 120),
            email: email.trim(),
            phone: str(phone || '', 30) || '',
            role: 'Customer',
            branch: null,
            active: true
        };

        const { error: profileError } = await supabase.from('app_users').insert([newUser]);
        if (profileError) return dbError(res, profileError);

        res.status(201).json({ message: 'Registration successful.' });
        sendWelcomeEmail(newUser).catch(err => console.error('Welcome email error:', err));
        sendAdminNewRegistrationEmail(supabase, newUser).catch(err => console.error('Admin registration email error:', err));
    } catch (ex) {
        console.error('Register error:', ex.message);
        res.status(500).json({ error: 'Registration failed. Please try again.' });
    }
});


// APP USERS
//   Admin  : list everyone, edit any field, delete (not themselves)
//   Others : see and edit only their OWN row, and only name / phone / recovery setting

const SELF_FIELDS = ['full_name', 'phone', 'keep_data_on_delete'];
const ADMIN_ONLY_FIELDS = ['email', 'role', 'branch', 'active'];

app.get('/api/app-users', requireAuth, async (req, res) => {
    let query = supabase.from('app_users').select('*').order('full_name');
    if (isAdmin(req.user)) {
        if (typeof req.query.email === 'string' && req.query.email.trim()) {
            query = query.ilike('email', escapeLike(req.query.email.trim()));
        }
    } else {
        query = query.eq('id', req.user.id); // ?email= is ignored for non-admins
    }
    const { data, error } = await query;
    if (error) return dbError(res, error);
    res.json(data);
});

app.put('/api/app-users/:id', requireAuth, async (req, res) => {
    const body = req.body || {};
    const admin = isAdmin(req.user);
    const isSelf = req.params.id === req.user.id;

    if (!admin && !isSelf) {
        auth.audit('forbidden', req, { reason: 'edit_other_user' });
        return res.status(403).json({ error: 'You can only edit your own account.' });
    }
    if (!admin && ADMIN_ONLY_FIELDS.some(k => has(body, k))) {
        auth.audit('privilege_escalation_attempt', req, { fields: ADMIN_ONLY_FIELDS.filter(k => has(body, k)).join(',') });
        return res.status(403).json({ error: 'Only an administrator can change role, branch, email or status.' });
    }

    const update = {};
    if (has(body, 'full_name')) update.full_name = str(body.full_name, 120);
    if (has(body, 'phone')) update.phone = str(body.phone, 30) || '';
    if (has(body, 'keep_data_on_delete')) update.keep_data_on_delete = body.keep_data_on_delete === true;

    if (admin) {
        if (has(body, 'email')) {
            if (typeof body.email !== 'string' || !EMAIL_RE.test(body.email.trim())) return res.status(400).json({ error: 'Invalid email.' });
            update.email = body.email.trim();
        }
        if (has(body, 'role')) {
            if (!ROLES.includes(body.role)) return res.status(400).json({ error: 'Invalid role.' });
            update.role = body.role;
        }
        if (has(body, 'branch')) update.branch = body.branch ? str(body.branch, 60) : null;
        if (has(body, 'active')) update.active = body.active === true;
        // An admin cannot lock themselves out
        if (isSelf && ((update.role && update.role !== 'Admin') || update.active === false)) {
            return res.status(400).json({ error: 'You cannot demote or deactivate your own account.' });
        }
    }

    if (Object.keys(update).length === 0) return res.status(400).json({ error: 'Nothing to update.' });

    const { data, error } = await supabase.from('app_users').update(update).eq('id', req.params.id).select();
    if (error) return dbError(res, error);
    res.json(data);
});

app.delete('/api/app-users/:id', requireRole('Admin'), async (req, res) => {
    if (req.params.id === req.user.id) return res.status(400).json({ error: 'You cannot delete your own account.' });
    const { data, error } = await supabase.from('app_users').delete().eq('id', req.params.id).select();
    if (error) return dbError(res, error);
    const { error: authError } = await supabase.auth.admin.deleteUser(req.params.id);
    if (authError) console.error('Auth user delete error:', authError.message);
    res.json({ message: 'User deleted', data });
});

// CONTACTS: active branch managers are public; admins are only shown to signed-in staff
app.get('/api/contacts', optionalAuth, async (req, res) => {
    const { data, error } = await supabase
        .from('app_users')
        .select('id, full_name, email, phone, role, branch, active')
        .eq('active', true)
        .order('full_name');
    if (error) return dbError(res, error);

    const viewerIsStaff = isStaff(req.user); // decided by the token, not by a ?viewer= value
    const contacts = data.filter(u =>
        String(u.role).startsWith('Manager') || (viewerIsStaff && u.role === 'Admin'));
    res.json(contacts);
});


// PRODUCTS  (read: public | write: Admin + Managers)

app.get('/api/products', async (req, res) => {
    const { data, error } = await supabase.from('products').select('*').order('created_at', { ascending: false });
    if (error) return dbError(res, error);
    res.json((data || []).map(cleanProduct));
});

app.get('/api/products/:id', async (req, res) => {
    const { data, error } = await supabase.from('products').select('*').eq('id', req.params.id).single();
    if (error) return dbError(res, error);
    res.json(cleanProduct(data));
});

app.post('/api/products', requireRole('Admin', 'Manager'), async (req, res) => {
    const { data, error } = await supabase.from('products').insert([req.body]).select();
    if (error) return dbError(res, error);
    res.status(201).json(data);
});

app.put('/api/products/:id', requireRole('Admin', 'Manager'), async (req, res) => {
    const { data, error } = await supabase.from('products').update(req.body).eq('id', req.params.id).select();
    if (error) return dbError(res, error);
    res.json(data);
});

app.delete('/api/products/:id', requireRole('Admin', 'Manager'), async (req, res) => {
    const { data, error } = await supabase.from('products').delete().eq('id', req.params.id).select();
    if (error) return dbError(res, error);
    res.json({ message: 'Product deleted', data });
});


// BRANCHES (read: public | write: Admin only)

registerCrud({ path: 'branches', table: 'branches', order: 'name', writeRoles: ['Admin'], label: 'Branch', getOne: true });

app.put('/api/branches/:id/image', requireRole('Admin'), async (req, res) => {
    try {
        const { data, contentType } = req.body || {};
        if (!data) return res.status(400).json({ error: 'Image data is required' });
        const buffer = Buffer.from(data, 'base64');
        if (buffer.length === 0 || buffer.length > 8 * 1024 * 1024) return res.status(400).json({ error: 'Image must be under 8 MB' });
        const type = contentType === 'image/png' ? 'image/png' : 'image/jpeg';
        const ext = type === 'image/png' ? 'png' : 'jpg';
        const path = `branches/${String(req.params.id).replace(/[^\w-]/g, '')}-${Date.now()}.${ext}`;
        const up = await supabase.storage.from('woodlands-assets').upload(path, buffer, { contentType: type, upsert: true });
        if (up.error) return dbError(res, up.error);
        const { data: pub } = supabase.storage.from('woodlands-assets').getPublicUrl(path);
        const { error } = await supabase.from('branches').update({ image: pub.publicUrl }).eq('id', req.params.id);
        if (error) return dbError(res, error);
        res.json({ image: pub.publicUrl });
    } catch (e) {
        console.error('Branch image error:', e.message);
        res.status(500).json({ error: 'Image upload failed.' });
    }
});


// SERVICES (read: public | write: Admin + Managers)
// TESTIMONIALS, FAQS (read: public | write: Admin only)

registerCrud({ path: 'services', table: 'services', order: 'name', writeRoles: ['Admin', 'Manager'], label: 'Service' });
registerCrud({ path: 'testimonials', table: 'testimonials', order: 'created_at', ascending: false, writeRoles: ['Admin'], label: 'Testimonial' });
registerCrud({ path: 'faqs', table: 'faqs', order: 'category', writeRoles: ['Admin'], label: 'FAQ' });

// HOMEPAGE ASSETS (read only, public)
app.get('/api/homepage-assets', async (req, res) => {
    const { data, error } = await supabase.from('homepage_assets').select('*').order('display_order');
    if (error) return dbError(res, error);
    res.json(data);
});


// QUOTE REQUESTS
//   POST (create) : public - customers and guests can request a quote
//   GET           : Admin = all | Manager = own branch only | Customer = own quotes only
//   PUT (status)  : Admin, or the Manager of that quote's branch
//   DELETE        : Admin only

app.get('/api/quote-requests', requireAuth, async (req, res) => {
    let query = supabase.from('quote_requests').select('*').order('created_at', { ascending: false });
    const u = req.user;

    if (isAdmin(u)) {
        if (typeof req.query.email === 'string' && req.query.email.trim()) query = query.ilike('email', escapeLike(req.query.email.trim()));
        if (typeof req.query.branch === 'string' && req.query.branch.trim()) query = query.eq('branch', req.query.branch.trim());
    } else if (isManager(u)) {
        const branch = branchOf(u);
        if (!branch) return res.json([]);
        query = query.eq('branch', branch); // forced - the ?branch= value cannot widen this
    } else {
        query = query.ilike('email', escapeLike(u.email)); // forced - customers only ever see their own
    }

    const { data, error } = await query;
    if (error) return dbError(res, error);
    res.json(data);
});

app.post('/api/quote-requests', quoteLimiter, optionalAuth, async (req, res) => {
    const b = req.body || {};
    if (typeof b.email !== 'string' || !EMAIL_RE.test(b.email.trim()) || !b.first_name || !b.quote_code) {
        return res.status(400).json({ error: 'First name, a valid email and a quote code are required.' });
    }
    // Whitelist: status is always "Pending" and nobody can set id / value / created_at
    const quoteRow = {
        quote_code: str(b.quote_code, 60),
        first_name: str(b.first_name, 100),
        last_name: str(b.last_name || '', 100),
        email: b.email.trim().slice(0, 200),
        phone: str(b.phone || '', 30),
        branch: str(b.branch || '', 60),
        service: str(b.service || '', 120),
        message: str(b.message || '', 4000),
        status: 'Pending'
    };
    if (b.product_id !== undefined && b.product_id !== null && b.product_id !== '') quoteRow.product_id = str(b.product_id, 100);

    const { data, error } = await supabase.from('quote_requests').insert([quoteRow]).select();
    if (error) {
        if (error.code === '23505') {
            const { data: existing } = await supabase.from('quote_requests').select('*')
                .eq('quote_code', quoteRow.quote_code).eq('email', quoteRow.email);
            return res.status(200).json(existing || []);
        }
        return dbError(res, error);
    }
    const quote = data && data[0];
    if (quote) {
        sendCustomerQuoteReceivedEmail(quote).catch(err => console.error('Customer quote email error:', err));
        sendAdminNewQuoteEmail(supabase, quote).catch(err => console.error('Admin quote email error:', err));
        sendBranchManagerNewQuoteEmail(supabase, quote).catch(err => console.error('Manager quote email error:', err));
    }
    res.status(201).json(data);
});

app.put('/api/quote-requests/:id', requireRole('Admin', 'Manager'), async (req, res) => {
    const { data: before, error: beforeError } = await supabase
        .from('quote_requests').select('status, branch').eq('id', req.params.id).maybeSingle();
    if (beforeError) return dbError(res, beforeError);
    if (!before) return res.status(404).json({ error: 'Quote request not found.' });

    if (isManager(req.user) && String(before.branch || '').toLowerCase() !== String(branchOf(req.user) || '').toLowerCase()) {
        auth.audit('forbidden', req, { reason: 'manager_other_branch_quote' });
        return res.status(403).json({ error: 'You can only update quotes for your own branch.' });
    }

    const update = {};
    if (has(req.body || {}, 'status')) {
        if (!QUOTE_STATUSES.includes(req.body.status)) return res.status(400).json({ error: 'Invalid status.' });
        update.status = req.body.status;
    }
    if (has(req.body || {}, 'value')) update.value = str(req.body.value, 50);
    if (Object.keys(update).length === 0) return res.status(400).json({ error: 'Nothing to update.' });

    const { data, error } = await supabase.from('quote_requests').update(update).eq('id', req.params.id).select();
    if (error) return dbError(res, error);
    const quote = data && data[0];
    if (quote && update.status && update.status !== before.status) {
        sendCustomerStatusEmail(quote, before.status, quote.status).catch(err => console.error('Status email error:', err));
    }
    res.json(data);
});

app.delete('/api/quote-requests/:id', requireRole('Admin'), async (req, res) => {
    const { data, error } = await supabase.from('quote_requests').delete().eq('id', req.params.id).select();
    if (error) return dbError(res, error);
    res.json({ message: 'Quote request deleted', data });
});


// ACCOUNT DELETION + ARCHIVE ROUTES, and the SCHEDULED BACKUP feature

registerDeletionRoutes(app, supabase, auth);
const backups = createBackupService(app, supabase, auth);

// Unknown routes + last-resort error handler (no stack traces to the client)
app.use((req, res) => res.status(404).json({ error: 'Not found.' }));
app.use((err, req, res, next) => {
    if (err && err.type === 'entity.too.large') return res.status(413).json({ error: 'Request is too large.' });
    if (err && err.type === 'entity.parse.failed') return res.status(400).json({ error: 'Invalid JSON.' });
    console.error('Unhandled error:', err && err.message);
    res.status(500).json({ error: 'Something went wrong.' });
});

// Start the server
const PORT = process.env.PORT || 5000;
app.listen(PORT, () => {
    console.log(`Node API running on http://localhost:${PORT}`);
    backups.start(); // starts the interval-based backup job
});