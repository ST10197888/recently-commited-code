
require('dotenv').config();

const express = require('express');
const cors = require('cors');
const crypto = require('crypto');
const helmet = require('helmet');
const { rateLimit } = require('express-rate-limit');
const { createClient } = require('@supabase/supabase-js');
const createAuth = require('./auth');
const registerNotificationRoutes = require('./notificationRoutes');
const registerDeletionRoutes = require('./deletionRoutes');
const registerProductDeletionRoutes = require('./productDeletionRoutes');
const createBackupService = require('./backupService');
const registerEmailChangeRoutes = require('./emailChangeRoutes');


const {
    sendCustomerQuoteReceivedEmail,
    sendAdminNewQuoteEmail,
    sendBranchManagerNewQuoteEmail,
    sendCustomerStatusEmail,
    sendWelcomeEmail,
    sendAdminNewRegistrationEmail
} = require('./emailService');

const app = express();
app.set('trust proxy', 1);

const allowedOrigins = (process.env.ALLOWED_ORIGINS || '')
    .split(',')
    .map(value => value.trim())
    .filter(Boolean);

app.use(helmet());

app.use(cors({
    origin: (origin, callback) =>
        callback(null, !origin || allowedOrigins.includes(origin)),
    methods: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'],
    allowedHeaders: ['Content-Type', 'Authorization']
}));

app.use('/api/admin/import', express.json({ limit: '50mb' }));

// Base64 images expand the original image size.
// Allow enough room for an image of up to 8 MB plus JSON overhead.
app.use(
    '/api/branches/:id/image',
    express.json({ limit: '12mb' })
);

// Keep the normal API request limit small.
app.use(express.json({ limit: '1mb' }));

const clientOptions = {
    auth: {
        persistSession: false,
        autoRefreshToken: false,
        detectSessionInUrl: false
    }
};

if (
    !process.env.SUPABASE_URL ||
    !process.env.SUPABASE_SECRET_KEY ||
    !process.env.SUPABASE_PUBLISHABLE_KEY
) {
    throw new Error(
        'SUPABASE_URL, SUPABASE_SECRET_KEY and SUPABASE_PUBLISHABLE_KEY must be configured.'
    );
}

// Privileged server-side client
const supabase = createClient(
    process.env.SUPABASE_URL,
    process.env.SUPABASE_SECRET_KEY,
    clientOptions
);

const auth = createAuth(supabase);

const {
    requireAuth,
    optionalAuth,
    requireRole,
    isAdmin,
    isManager,
    isStaff,
    branchOf
} = auth;

// Customer authentication client
const createAuthClient = () =>
    createClient(
        process.env.SUPABASE_URL,
        process.env.SUPABASE_PUBLISHABLE_KEY,
        clientOptions
    );

const ROLES = [
    'Admin',
    'Manager (Soweto)',
    'Manager (Roodepoort)',
    'Manager (Randfontein)',
    'Customer'
];

const BRANCHES = ['Soweto', 'Roodepoort', 'Randfontein'];

const QUOTE_STATUSES = [
    'Pending',
    'In Progress',
    'Completed',
    'Cancelled'
];

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const has = (object, key) =>
    Object.prototype.hasOwnProperty.call(object || {}, key);

const str = (value, max) =>
    typeof value === 'string'
        ? value.trim().slice(0, max)
        : value == null
            ? null
            : String(value).trim().slice(0, max);

const escapeLike = value =>
    String(value).replace(/[\\%_]/g, '\\$&');

function dbError(res, error) {
    console.error('Database error:', error?.message || error);

    return res.status(500).json({
        error: 'Something went wrong. Please try again.'
    });
}

function notFound(res) {
    return res.status(404).json({ error: 'Record not found.' });
}

function validBranch(value) {
    return typeof value === 'string' &&
        BRANCHES.some(branch =>
            branch.toLowerCase() === value.trim().toLowerCase()
        );
}

function canonicalBranch(value) {
    return BRANCHES.find(branch =>
        branch.toLowerCase() === String(value || '').trim().toLowerCase()
    ) || null;
}

function managerBranch(user) {
    return canonicalBranch(branchOf(user));
}

function managerOwnsRecord(user, record) {
    const branch = managerBranch(user);

    return Boolean(
        branch &&
        record &&
        String(record.branch || '').toLowerCase() === branch.toLowerCase() &&
        record.created_by === user.id
    );
}

function safeParse(value) {
    if (Array.isArray(value)) return value;

    if (typeof value !== 'string' || !value.trim()) {
        return [];
    }

    try {
        const parsed = JSON.parse(value);
        return Array.isArray(parsed) ? parsed : [];
    } catch {
        return [];
    }
}

function cleanProduct(product) {
    return {
        ...product,
        gallery: safeParse(product.gallery),
        features: safeParse(product.features),
        finishes: safeParse(product.finishes)
    };
}

function canManageOwnedRecord(record, user) {
    if (isAdmin(user)) return true;
    if (!isManager(user)) return false;

    const userBranch = String(branchOf(user) || '').trim().toLowerCase();
    const recordBranch = String(record.branch || '').trim().toLowerCase();

    return Boolean(
        userBranch &&
        recordBranch &&
        userBranch === recordBranch &&
        record.created_by === user.id
    );
}

function cleanProductPayload(body, includeId = false) {
    const result = {};

    if (includeId && has(body, 'id')) {
        result.id = str(body.id, 120);
    }

    const fields = {
        category: 100,
        title: 180,
        tagline: 250,
        description: 5000,
        image: 2000,
        lead_time: 120,
        tag: 80,
        price: 80
    };

    for (const [field, max] of Object.entries(fields)) {
        if (has(body, field)) {
            result[field] = str(body[field], max);
        }
    }

    for (const field of ['gallery', 'features', 'finishes']) {
        if (has(body, field)) {
            const values = safeParse(body[field]);

            if (values.length > 100) {
                throw new Error(`${field} contains too many items.`);
            }

            result[field] = JSON.stringify(
                values
                    .filter(value => typeof value === 'string')
                    .map(value => value.trim().slice(0, 1000))
            );
        }
    }

    if (has(body, 'is_from_price')) {
        if (typeof body.is_from_price !== 'boolean') {
            throw new Error('is_from_price must be a boolean.');
        }

        result.is_from_price = body.is_from_price;
    }

    return result;
}

function cleanServicePayload(body) {
    const result = {};

    for (const field of ['name', 'description', 'image']) {
        if (has(body, field)) {
            result[field] = str(
                body[field],
                field === 'description' ? 1000 : field === 'image' ? 2000 : 120
            );
        }
    }

    if (has(body, 'is_active')) {
        if (typeof body.is_active !== 'boolean') {
            throw new Error('is_active must be a boolean.');
        }

        result.is_active = body.is_active;
    }

    return result;
}

function cleanBranchPayload(body, admin) {
    const result = {};

    const fields = {
        name: 100,
        region: 120,
        phone: 40,
        hours: 250,
        notes: 2000,
        image: 2000,
        address: 500
    };

    for (const [field, max] of Object.entries(fields)) {
        if (has(body, field)) {
            result[field] = str(body[field], max);
        }
    }

    if (!admin && has(result, 'name')) {
        throw new Error('Managers cannot rename branches.');
    }

    return result;
}

function createProductSlug(value) {
    return String(value || '')
        .normalize('NFKD')
        .replace(/[\u0300-\u036f]/g, '')
        .toLowerCase()
        .trim()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .slice(0, 100)
        .replace(/-+$/g, '') || 'product';
}

function cleanQuoteUpdate(body) {
    const update = {};

    if (has(body, 'status')) {
        if (!QUOTE_STATUSES.includes(body.status)) {
            throw new Error('Invalid quote status.');
        }

        update.status = body.status;
    }

    if (has(body, 'value')) {
        update.value = str(body.value, 50);
    }

    return update;
}

function asyncRoute(handler) {
    return (req, res, next) =>
        Promise.resolve(handler(req, res, next)).catch(next);
}

const limitMessage = {
    error: 'Too many requests. Please try again later.'
};

const emailKey = req =>
    'e:' + String(req.body?.email || 'none').trim().toLowerCase();

const loginLimiter = rateLimit({
    windowMs: 15 * 60 * 1000,
    limit: 10,
    skipSuccessfulRequests: true,
    keyGenerator: emailKey,
    standardHeaders: 'draft-7',
    legacyHeaders: false,
    message: limitMessage
});

const registerLimiter = rateLimit({
    windowMs: 60 * 60 * 1000,
    limit: 30,
    standardHeaders: 'draft-7',
    legacyHeaders: false,
    message: limitMessage
});

const quoteLimiter = rateLimit({
    windowMs: 60 * 60 * 1000,
    limit: 60,
    standardHeaders: 'draft-7',
    legacyHeaders: false,
    message: limitMessage
});


// HEALTH

app.get('/health', (req, res) => {
    res.json({
        status: 'ok',
        time: new Date().toISOString()
    });
});


// AUTHENTICATION

app.post('/api/auth/login', loginLimiter, asyncRoute(async (req, res) => {
    const { email, password } = req.body || {};

    if (
        typeof email !== 'string' ||
        typeof password !== 'string' ||
        !email.trim() ||
        !password
    ) {
        return res.status(400).json({
            error: 'Email and password are required.'
        });
    }

    const normalizedEmail = email.trim().toLowerCase();

    const { data: authData, error: authError } =
        await createAuthClient().auth.signInWithPassword({
            email: normalizedEmail,
            password
        });

    if (authError || !authData?.user) {
        auth.audit('login_failed', req, {
            email: normalizedEmail
        });

        return res.status(401).json({
            error: 'Invalid email or password.'
        });
    }

    const { data: profile, error: profileError } = await supabase
        .from('app_users')
        .select('*')
        .eq('id', authData.user.id)
        .maybeSingle();

    if (profileError) {
        console.error(
            'Login profile lookup failed:',
            profileError.message
        );

        return res.status(503).json({
            error: 'Your account could not be verified. Please try again later.'
        });
    }

    if (!profile) {
        return res.status(404).json({
            error: 'Your account profile could not be found. Please contact support.'
        });
    }

    if (!profile.active) {
        return res.status(403).json({
            error: 'This account is currently inactive.'
        });
    }

    return res.json({
        user: profile,
        token: auth.signToken(profile)
    });
}));

app.post('/api/auth/register', registerLimiter, asyncRoute(async (req, res) => {
    const { fullName, email, password, phone } = req.body || {};

    if (
        typeof fullName !== 'string' ||
        !fullName.trim() ||
        typeof email !== 'string' ||
        !EMAIL_RE.test(email.trim()) ||
        typeof password !== 'string' ||
        password.length < 8 ||
        !/[a-z]/.test(password) ||
        !/[0-9]/.test(password)
    ) {
        return res.status(400).json({
            error: 'A name, valid email and password of at least 8 characters containing a lowercase letter and a number are required.'
        });
    }

    const normalizedEmail = email.trim().toLowerCase();

    const { data: authData, error: authError } =
        await supabase.auth.admin.createUser({
            email: normalizedEmail,
            password,
            email_confirm: true
        });

    if (authError || !authData?.user) {
        console.error(
            'Registration Auth creation failed:',
            authError?.message || 'No user was returned.'
        );

        return res.status(400).json({
            error: authError?.message || 'Registration failed.'
        });
    }

    const newUser = {
        id: authData.user.id,
        full_name: str(fullName, 120),
        email: normalizedEmail,
        phone: str(phone || '', 30),
        role: 'Customer',
        branch: null,
        active: true
    };

    const { error: profileError } = await supabase
        .from('app_users')
        .insert([newUser]);

    if (profileError) {
        console.error(
            'Registration profile creation failed:',
            profileError.message
        );

        try {
            const { error: rollbackError } =
                await supabase.auth.admin.deleteUser(authData.user.id);

            if (rollbackError) {
                console.error(
                    'CRITICAL: Registration rollback failed. Auth user:',
                    authData.user.id,
                    rollbackError.message
                );
            }
        } catch (rollbackError) {
            console.error(
                'CRITICAL: Registration rollback threw an error. Auth user:',
                authData.user.id,
                rollbackError?.message || rollbackError
            );
        }

        return res.status(500).json({
            error: 'Registration could not be completed. Please try again.'
        });
    }

    res.status(201).json({
        message: 'Registration successful.'
    });

    sendWelcomeEmail(newUser)
        .catch(error =>
            console.error('Welcome email error:', error)
        );

    sendAdminNewRegistrationEmail(supabase, newUser)
        .catch(error =>
            console.error('Admin registration email error:', error)
        );
}));

// APP USERS

const SELF_FIELDS = [
    'full_name',
    'phone',
    'keep_data_on_delete'
];

const ADMIN_FIELDS = [
    'email',
    'role',
    'branch',
    'active'
];

app.get('/api/app-users', requireAuth, asyncRoute(async (req, res) => {
    let query = supabase
        .from('app_users')
        .select('*')
        .order('full_name');

    if (isAdmin(req.user)) {
        if (typeof req.query.email === 'string' && req.query.email.trim()) {
            query = query.ilike(
                'email',
                escapeLike(req.query.email.trim())
            );
        }
    } else {
        query = query.eq('id', req.user.id);
    }

    const { data, error } = await query;

    if (error) return dbError(res, error);

    res.json(data || []);
}));


app.put('/api/app-users/:id', requireAuth, asyncRoute(async (req, res) => {
    const body = req.body || {};
    const admin = isAdmin(req.user);
    const self = req.params.id === req.user.id;

    if (!admin && !self) {
        auth.audit('forbidden', req, {
            reason: 'edit_other_user'
        });

        return res.status(403).json({
            error: 'You can only edit your own account.'
        });
    }

    // Email changes must use the separate approval workflow.
    if (has(body, 'email')) {
        auth.audit('direct_email_change_blocked', req, {
            target_user: req.params.id
        });

        return res.status(400).json({
            error: 'Email addresses must be changed through the email approval request process.'
        });
    }

    // Non-admin users cannot change administrative fields.
    if (!admin && ADMIN_FIELDS.some(field => has(body, field))) {
        auth.audit('privilege_escalation_attempt', req, {
            fields: ADMIN_FIELDS
                .filter(field => has(body, field))
                .join(',')
        });

        return res.status(403).json({
            error: 'Only an administrator can change role, branch, email or status.'
        });
    }

    const update = {};

    if (has(body, 'full_name')) {
        if (
            typeof body.full_name !== 'string' ||
            !body.full_name.trim()
        ) {
            return res.status(400).json({
                error: 'A valid name is required.'
            });
        }

        update.full_name = str(body.full_name, 120);
    }

    if (has(body, 'phone')) {
        update.phone = str(body.phone, 30) || '';
    }

    if (has(body, 'keep_data_on_delete')) {
        if (typeof body.keep_data_on_delete !== 'boolean') {
            return res.status(400).json({
                error: 'Invalid account recovery preference.'
            });
        }

        update.keep_data_on_delete = body.keep_data_on_delete;
    }

    // Administrators can change account roles.
    if (admin && has(body, 'role')) {
        if (!ROLES.includes(body.role)) {
            return res.status(400).json({
                error: 'Invalid role.'
            });
        }

        update.role = body.role;

        if (body.role.startsWith('Manager')) {
            const roleBranch = body.role.match(/^Manager \((.+)\)$/)?.[1];

            if (!validBranch(roleBranch)) {
                return res.status(400).json({
                    error: 'Invalid manager branch.'
                });
            }

            update.branch = canonicalBranch(roleBranch);
        } else if (
            body.role === 'Customer' ||
            body.role === 'Admin'
        ) {
            update.branch = null;
        }
    }

    // Branch assignments must match the manager role.
    if (admin && has(body, 'branch') && !has(body, 'role')) {
        const requestedBranch = body.branch
            ? canonicalBranch(body.branch)
            : null;

        if (body.branch && !requestedBranch) {
            return res.status(400).json({
                error: 'Invalid branch.'
            });
        }

        const effectiveRole = update.role || req.user.role;

        if (effectiveRole.startsWith('Manager')) {
            const expected = effectiveRole.match(/^Manager \((.+)\)$/)?.[1];

            if (requestedBranch !== expected) {
                return res.status(400).json({
                    error: 'The manager branch must match the assigned manager role.'
                });
            }
        } else if (requestedBranch) {
            return res.status(400).json({
                error: 'Only manager accounts can be assigned a branch.'
            });
        }

        update.branch = requestedBranch;
    }

    // Administrators can activate or deactivate accounts.
    if (admin && has(body, 'active')) {
        if (typeof body.active !== 'boolean') {
            return res.status(400).json({
                error: 'Invalid account status.'
            });
        }

        update.active = body.active;
    }

    // Prevent administrators from demoting or deactivating themselves.
    if (
        self &&
        admin &&
        (
            (update.role && update.role !== 'Admin') ||
            update.active === false
        )
    ) {
        return res.status(400).json({
            error: 'You cannot demote or deactivate your own account.'
        });
    }

    if (Object.keys(update).length === 0) {
        return res.status(400).json({
            error: 'Nothing to update.'
        });
    }

    const { data, error } = await supabase
        .from('app_users')
        .update(update)
        .eq('id', req.params.id)
        .select();

    if (error) {
        return dbError(res, error);
    }

    if (!data?.length) {
        return notFound(res);
    }

    return res.json(data);
}));


// Direct deletion is intentionally disabled.
// The account-deletion workflow handles archive, approval and Auth deletion.
app.delete('/api/app-users/:id', requireRole('Admin'), (req, res) => {
    res.status(409).json({
        error: 'Use the account deletion request workflow to remove an account.'
    });
});


// CONTACTS

app.get('/api/contacts', optionalAuth, asyncRoute(async (req, res) => {
    const { data, error } = await supabase
        .from('app_users')
        .select('id, full_name, email, phone, role, branch, active')
        .eq('active', true)
        .order('full_name');

    if (error) return dbError(res, error);

    const viewerIsStaff = isStaff(req.user);

    res.json((data || []).filter(user =>
        String(user.role).startsWith('Manager') ||
        (viewerIsStaff && user.role === 'Admin')
    ));
}));


// PRODUCTS

app.get('/api/products', async (req, res) => {
    const { data, error } = await supabase
        .from('products')
        .select('*')
        .order('created_at', { ascending: false });

    if (error) return dbError(res, error);

    return res.json((data || []).map(cleanProduct));
});

app.get('/api/products/:id', async (req, res) => {
    const { data, error } = await supabase
        .from('products')
        .select('*')
        .eq('id', req.params.id)
        .single();

    if (error) return dbError(res, error);

    return res.json(cleanProduct(data));
});

app.post('/api/products', requireRole('Admin', 'Manager'), asyncRoute(async (req, res) => {
    const body = req.body || {};
    let payload;

    try {
        payload = cleanProductPayload(body);
    } catch (error) {
        return res.status(400).json({ error: error.message });
    }

    if (!payload.title || !payload.title.trim()) {
        return res.status(400).json({
            error: 'A product title is required.'
        });
    }

    // Products belong to the shared catalogue.
    // Their IDs are generated by the API because the database has no ID default.
    delete payload.branch;
    delete payload.created_by;
    delete payload.id;
    delete payload.created_at;

    const requestedId = typeof body.id === 'string'
        ? body.id.trim()
        : '';

    const baseId = createProductSlug(requestedId || payload.title);

    let lastError = null;

    // Retry with a random suffix when the requested slug already exists.
    for (let attempt = 0; attempt < 5; attempt++) {
        const productId = attempt === 0
            ? baseId
            : `${baseId}-${crypto.randomBytes(4).toString('hex')}`;

        const { data, error } = await supabase
            .from('products')
            .insert([{
                ...payload,
                id: productId
            }])
            .select();

        if (!error) {
            return res.status(201).json(data.map(cleanProduct));
        }

        lastError = error;

        // Only retry unique-key collisions.
        if (error.code !== '23505') {
            return dbError(res, error);
        }
    }

    return dbError(res, lastError);
}));

app.put('/api/products/:id', requireRole('Admin', 'Manager'), asyncRoute(async (req, res) => {
    let update;

    try {
        update = cleanProductPayload(req.body || {});
    } catch (error) {
        return res.status(400).json({ error: error.message });
    }

    // Products belong to the shared catalogue.
    // Never accept branch or individual ownership changes.
    delete update.id;
    delete update.created_at;
    delete update.branch;
    delete update.created_by;

    if (Object.keys(update).length === 0) {
        return res.status(400).json({
            error: 'Nothing to update.'
        });
    }

    const { data, error } = await supabase
        .from('products')
        .update(update)
        .eq('id', req.params.id)
        .select();

    if (error) return dbError(res, error);

    if (!data || data.length === 0) {
        return res.status(404).json({
            error: 'Product not found.'
        });
    }

    return res.json(data.map(cleanProduct));
}));



// BRANCHES

app.get('/api/branches', asyncRoute(async (req, res) => {
    const { data, error } = await supabase
        .from('branches')
        .select('*')
        .order('name');

    if (error) return dbError(res, error);

    res.json(data || []);
}));

app.get('/api/branches/:id', asyncRoute(async (req, res) => {
    const { data, error } = await supabase
        .from('branches')
        .select('*')
        .eq('id', req.params.id)
        .maybeSingle();

    if (error) return dbError(res, error);
    if (!data) return notFound(res);

    res.json(data);
}));

app.post('/api/branches', requireRole('Admin'), asyncRoute(async (req, res) => {
    let payload;

    try {
        payload = cleanBranchPayload(req.body || {}, true);
    } catch (error) {
        return res.status(400).json({ error: error.message });
    }

    if (!payload.name) {
        return res.status(400).json({
            error: 'Branch name is required.'
        });
    }

    const supportedName = canonicalBranch(payload.name);

    if (!supportedName) {
        return res.status(400).json({
            error: `Unsupported branch. Supported branches are: ${BRANCHES.join(', ')}.`
        });
    }

    payload.name = supportedName;

    const { data: existing, error: existingError } = await supabase
        .from('branches')
        .select('id')
        .ilike('name', escapeLike(payload.name))
        .limit(1);

    if (existingError) return dbError(res, existingError);

    if (existing?.length) {
        return res.status(409).json({
            error: 'This branch already exists.'
        });
    }

    const { data, error } = await supabase
        .from('branches')
        .insert([payload])
        .select();

    if (error) return dbError(res, error);

    return res.status(201).json(data);
}));

app.put('/api/branches/:id', requireRole('Admin', 'Manager'), asyncRoute(async (req, res) => {
    const { data: existingBranch, error: branchError } = await supabase
        .from('branches')
        .select('id, name')
        .eq('id', req.params.id)
        .maybeSingle();

    if (branchError) return dbError(res, branchError);
    if (!existingBranch) return notFound(res);

    const body = { ...(req.body || {}) };

    // Branch names are immutable because they are linked to Manager roles.
    // Submitting the existing name unchanged is still allowed.
    if (has(body, 'name')) {
        const requestedName = canonicalBranch(body.name);
        const currentName = canonicalBranch(existingBranch.name);

        if (!requestedName || requestedName !== currentName) {
            return res.status(isManager(req.user) ? 403 : 400).json({
                error: 'Branch names cannot be changed because they are linked to Manager roles.'
            });
        }

        delete body.name;
    }

    let payload;

    try {
        payload = cleanBranchPayload(body, isAdmin(req.user));
    } catch (error) {
        return res.status(403).json({ error: error.message });
    }

    if (!Object.keys(payload).length) {
        return res.status(400).json({
            error: 'Nothing to update.'
        });
    }

    let query = supabase
        .from('branches')
        .update(payload)
        .eq('id', req.params.id);

    if (isManager(req.user)) {
        const branch = managerBranch(req.user);

        if (!branch || branch.toLowerCase() !== existingBranch.name.toLowerCase()) {
            return res.status(403).json({
                error: 'You can only update your own branch.'
            });
        }

        query = query.eq('name', existingBranch.name);
    }

    const { data, error } = await query.select();

    if (error) return dbError(res, error);
    if (!data?.length) return notFound(res);

    return res.json(data);
}));

app.delete('/api/branches/:id', requireRole('Admin'), asyncRoute(async (req, res) => {

    // Find the branch before attempting deletion.
    const { data: branch, error: branchError } = await supabase
        .from('branches')
        .select('id, name')
        .eq('id', req.params.id)
        .maybeSingle();

    if (branchError) return dbError(res, branchError);
    if (!branch) return notFound(res);

    const branchName = branch.name;

    // Check whether any user account is still assigned to this branch.
    const { count: userCount, error: userError } = await supabase
        .from('app_users')
        .select('id', { count: 'exact', head: true })
        .eq('branch', branchName);

    if (userError) return dbError(res, userError);

    // Check whether branch-specific services still exist.
    const { count: serviceCount, error: serviceError } = await supabase
        .from('services')
        .select('id', { count: 'exact', head: true })
        .eq('branch', branchName);

    if (serviceError) return dbError(res, serviceError);

    // Preserve historical and active quote requests.
    const { count: quoteCount, error: quoteError } = await supabase
        .from('quote_requests')
        .select('id', { count: 'exact', head: true })
        .eq('branch', branchName);

    if (quoteError) return dbError(res, quoteError);

    const dependencies = {
        user_accounts: userCount || 0,
        services: serviceCount || 0,
        quote_requests: quoteCount || 0
    };

    const hasDependencies = Object.values(dependencies)
        .some(count => count > 0);

    if (hasDependencies) {
        auth.audit('branch_deletion_blocked', req, {
            branch: branchName,
            dependencies
        });

        return res.status(409).json({
            error: 'This branch cannot be deleted while records are still assigned to it.',
            dependencies,
            message: 'Reassign or appropriately resolve the associated records before deleting the branch.'
        });
    }

    // Delete only after every dependency check succeeds.
    const { data, error } = await supabase
        .from('branches')
        .delete()
        .eq('id', req.params.id)
        .select();

    if (error) return dbError(res, error);
    if (!data?.length) return notFound(res);

    auth.audit('branch_deleted', req, {
        branch: branchName
    });

    return res.json({
        message: 'Branch deleted.',
        data
    });
}));

app.put('/api/branches/:id/image', requireRole('Admin', 'Manager'), asyncRoute(async (req, res) => {
    const { data: imageData, contentType } = req.body || {};

    if (typeof imageData !== 'string' || !imageData.trim()) {
        return res.status(400).json({ error: 'Image data is required.' });
    }

    const buffer = Buffer.from(imageData, 'base64');

    if (!buffer.length || buffer.length > 8 * 1024 * 1024) {
        return res.status(400).json({ error: 'Image must be under 8 MB.' });
    }

    const allowedTypes = {
        'image/png': 'png',
        'image/jpeg': 'jpg',
        'image/webp': 'webp'
    };

    const extension = allowedTypes[contentType];

    if (!extension) {
        return res.status(400).json({ error: 'Unsupported image type.' });
    }

    const { data: branchRecord, error: branchError } = await supabase
        .from('branches')
        .select('id, name')
        .eq('id', req.params.id)
        .maybeSingle();

    if (branchError) return dbError(res, branchError);
    if (!branchRecord) return notFound(res);

    if (
        isManager(req.user) &&
        String(branchRecord.name).toLowerCase() !==
        String(managerBranch(req.user) || '').toLowerCase()
    ) {
        return res.status(403).json({
            error: 'You can only update your own branch image.'
        });
    }

    const path = `branches/${String(req.params.id).replace(/[^\w-]/g, '')}-${Date.now()}.${extension}`;

    const upload = await supabase.storage
        .from('woodlands-assets')
        .upload(path, buffer, {
            contentType,
            upsert: true
        });

    if (upload.error) return dbError(res, upload.error);

    const { data: publicUrl } = supabase.storage
        .from('woodlands-assets')
        .getPublicUrl(path);

    let updateQuery = supabase
        .from('branches')
        .update({ image: publicUrl.publicUrl })
        .eq('id', req.params.id);

    if (isManager(req.user)) {
        updateQuery = updateQuery.eq('name', managerBranch(req.user));
    }

    const { data, error } = await updateQuery.select();

    if (error) return dbError(res, error);
    if (!data?.length) return notFound(res);

    res.json({
        image: publicUrl.publicUrl,
        data
    });
}));


// SERVICES

app.get('/api/services', asyncRoute(async (req, res) => {
    const { data, error } = await supabase
        .from('services')
        .select('*')
        .order('name');

    if (error) return dbError(res, error);

    res.json(data || []);
}));

app.post('/api/services', requireRole('Admin', 'Manager'), asyncRoute(async (req, res) => {
    let payload;

    try {
        payload = cleanServicePayload(req.body || {});
    } catch (error) {
        return res.status(400).json({ error: error.message });
    }

    if (!payload.name) {
        return res.status(400).json({ error: 'Service name is required.' });
    }

    // Ownership is always assigned by the API.
    payload.created_by = req.user.id;

    if (isManager(req.user)) {
        const branch = managerBranch(req.user);

        if (!branch) {
            return res.status(403).json({
                error: 'Your account has no valid branch.'
            });
        }

        // Managers cannot choose another branch.
        payload.branch = branch;
    } else if (has(req.body, 'branch')) {
        const requestedBranch = req.body.branch
            ? canonicalBranch(req.body.branch)
            : null;

        if (req.body.branch && !requestedBranch) {
            return res.status(400).json({ error: 'Invalid branch.' });
        }

        payload.branch = requestedBranch;
    }

    const { data, error } = await supabase
        .from('services')
        .insert([payload])
        .select();

    if (error) return dbError(res, error);

    res.status(201).json(data);
}));

app.put('/api/services/:id', requireRole('Admin', 'Manager'), asyncRoute(async (req, res) => {
    const { data: existing, error: fetchError } = await supabase
        .from('services')
        .select('*')
        .eq('id', req.params.id)
        .maybeSingle();

    if (fetchError) return dbError(res, fetchError);
    if (!existing) return notFound(res);

    if (!canManageOwnedRecord(existing, req.user)) {
        auth.audit('forbidden', req, {
            reason: 'service_ownership_violation'
        });

        return res.status(403).json({
            error: 'You can only edit services created by you for your own branch.'
        });
    }

    let update;

    try {
        update = cleanServicePayload(req.body || {});
    } catch (error) {
        return res.status(400).json({ error: error.message });
    }

    if (!Object.keys(update).length) {
        return res.status(400).json({ error: 'Nothing to update.' });
    }

    let query = supabase
        .from('services')
        .update(update)
        .eq('id', req.params.id);

    if (isManager(req.user)) {
        query = query
            .eq('branch', existing.branch)
            .eq('created_by', req.user.id);
    }

    const { data, error } = await query.select();

    if (error) return dbError(res, error);
    if (!data?.length) {
        return res.status(409).json({
            error: 'The service could not be updated. Refresh and try again.'
        });
    }

    res.json(data);
}));

app.delete('/api/services/:id', requireRole('Admin', 'Manager'), asyncRoute(async (req, res) => {
    const { data: existing, error: fetchError } = await supabase
        .from('services')
        .select('*')
        .eq('id', req.params.id)
        .maybeSingle();

    if (fetchError) return dbError(res, fetchError);
    if (!existing) return notFound(res);

    if (!canManageOwnedRecord(existing, req.user)) {
        auth.audit('forbidden', req, {
            reason: 'service_delete_ownership_violation'
        });

        return res.status(403).json({
            error: 'You can only delete services created by you for your own branch.'
        });
    }

    let query = supabase
        .from('services')
        .delete()
        .eq('id', req.params.id);

    if (isManager(req.user)) {
        query = query
            .eq('branch', existing.branch)
            .eq('created_by', req.user.id);
    }

    const { data, error } = await query.select();

    if (error) return dbError(res, error);
    if (!data?.length) {
        return res.status(409).json({
            error: 'The service could not be deleted. Refresh and try again.'
        });
    }

    res.json({
        message: 'Service deleted',
        data
    });
}));


// TESTIMONIALS AND FAQS - ADMIN ONLY

function registerAdminCrud({ path, table, order, ascending = true, fields, label }) {
    app.get(`/api/${path}`, asyncRoute(async (req, res) => {
        const { data, error } = await supabase
            .from(table)
            .select('*')
            .order(order, { ascending });

        if (error) return dbError(res, error);

        res.json(data || []);
    }));

    app.post(`/api/${path}`, requireRole('Admin'), asyncRoute(async (req, res) => {
        const payload = {};

        for (const [field, max] of Object.entries(fields)) {
            if (has(req.body, field)) {
                payload[field] = str(req.body[field], max);
            }
        }

        const { data, error } = await supabase
            .from(table)
            .insert([payload])
            .select();

        if (error) return dbError(res, error);

        res.status(201).json(data);
    }));

    app.put(`/api/${path}/:id`, requireRole('Admin'), asyncRoute(async (req, res) => {
        const payload = {};

        for (const [field, max] of Object.entries(fields)) {
            if (has(req.body, field)) {
                payload[field] = str(req.body[field], max);
            }
        }

        if (!Object.keys(payload).length) {
            return res.status(400).json({ error: 'Nothing to update.' });
        }

        const { data, error } = await supabase
            .from(table)
            .update(payload)
            .eq('id', req.params.id)
            .select();

        if (error) return dbError(res, error);
        if (!data?.length) return notFound(res);

        res.json(data);
    }));

    app.delete(`/api/${path}/:id`, requireRole('Admin'), asyncRoute(async (req, res) => {
        const { data, error } = await supabase
            .from(table)
            .delete()
            .eq('id', req.params.id)
            .select();

        if (error) return dbError(res, error);
        if (!data?.length) return notFound(res);

        res.json({
            message: `${label} deleted`,
            data
        });
    }));
}

registerAdminCrud({
    path: 'testimonials',
    table: 'testimonials',
    order: 'created_at',
    ascending: false,
    label: 'Testimonial',
    fields: {
        name: 120,
        role: 120,
        location: 120,
        rating: 10,
        review: 5000,
        project: 200
    }
});

registerAdminCrud({
    path: 'faqs',
    table: 'faqs',
    order: 'category',
    label: 'FAQ',
    fields: {
        category: 120,
        question: 500,
        answer: 5000
    }
});


// HOMEPAGE ASSETS

app.get('/api/homepage-assets', asyncRoute(async (req, res) => {
    const { data, error } = await supabase
        .from('homepage_assets')
        .select('*')
        .order('display_order');

    if (error) return dbError(res, error);

    res.json(data || []);
}));


// QUOTE REQUESTS

app.get('/api/quote-requests', requireAuth, asyncRoute(async (req, res) => {
    let query = supabase
        .from('quote_requests')
        .select('*')
        .order('created_at', { ascending: false });

    if (isAdmin(req.user)) {
        if (typeof req.query.email === 'string' && req.query.email.trim()) {
            query = query.ilike('email', escapeLike(req.query.email.trim()));
        }

        if (typeof req.query.branch === 'string' && req.query.branch.trim()) {
            query = query.eq('branch', req.query.branch.trim());
        }
    } else if (isManager(req.user)) {
        const branch = managerBranch(req.user);

        if (!branch) return res.json([]);

        query = query.eq('branch', branch);
    } else {
        query = query.ilike('email', escapeLike(req.user.email));
    }

    const { data, error } = await query;

    if (error) return dbError(res, error);

    res.json(data || []);
}));



app.post('/api/quote-requests', quoteLimiter, optionalAuth, asyncRoute(async (req, res) => {
    const b = req.body || {};
    const user = req.user || null;

    // Managers cannot create customer quote requests.
    if (user && isManager(user)) {
        auth.audit('forbidden', req, {
            reason: 'manager_quote_creation_blocked'
        });

        return res.status(403).json({
            error: 'Managers cannot create customer quote requests.'
        });
    }

    // Only guests, Customers and Admins can submit requests.
    if (user && !isAdmin(user) && user.role !== 'Customer') {
        return res.status(403).json({
            error: 'You do not have permission to create quote requests.'
        });
    }

    // Validate the submitted customer details.
    if (
        typeof b.first_name !== 'string' ||
        !b.first_name.trim() ||
        typeof b.last_name !== 'string' ||
        !b.last_name.trim() ||
        typeof b.email !== 'string' ||
        !EMAIL_RE.test(b.email.trim()) ||
        typeof b.phone !== 'string' ||
        !b.phone.trim() ||
        typeof b.quote_code !== 'string' ||
        !b.quote_code.trim()
    ) {
        return res.status(400).json({
            error: 'First name, last name, valid email, phone number and quote reference are required.'
        });
    }

    // Branch must match one of the application's supported branches.
    const branch = canonicalBranch(b.branch);

    if (!branch) {
        return res.status(400).json({
            error: `Invalid branch. Supported branches are: ${BRANCHES.join(', ')}.`
        });
    }

    // Validate the selected service against active services.
    if (
        typeof b.service !== 'string' ||
        !b.service.trim()
    ) {
        return res.status(400).json({
            error: 'A service must be selected.'
        });
    }

    const requestedService = str(b.service, 120);

    const { data: matchingServices, error: serviceError } = await supabase
        .from('services')
        .select('id, name, branch, is_active')
        .ilike('name', escapeLike(requestedService))
        .eq('is_active', true)
        .limit(50);

    if (serviceError) return dbError(res, serviceError);

    // A service is available if it is global or assigned to the selected branch.
    const selectedService = (matchingServices || []).find(service =>
        !service.branch ||
        String(service.branch).trim().toLowerCase() === branch.toLowerCase()
    );

    if (!selectedService) {
        return res.status(400).json({
            error: 'The selected service is not available at this branch.'
        });
    }

    // Validate the optional product reference.
    let productId = null;

    if (
        b.product_id !== undefined &&
        b.product_id !== null &&
        b.product_id !== ''
    ) {
        if (typeof b.product_id !== 'string') {
            return res.status(400).json({
                error: 'Invalid product reference.'
            });
        }

        productId = str(b.product_id, 100);

        const { data: product, error: productError } = await supabase
            .from('products')
            .select('id')
            .eq('id', productId)
            .maybeSingle();

        if (productError) return dbError(res, productError);

        if (!product) {
            return res.status(400).json({
                error: 'The selected product could not be found.'
            });
        }
    }

    const submittedEmail = b.email.trim().toLowerCase();

    // Signed-in Customers must use their own registered email.
    if (
        user &&
        user.role === 'Customer' &&
        submittedEmail !== String(user.email || '').trim().toLowerCase()
    ) {
        auth.audit('forbidden', req, {
            reason: 'customer_quote_email_mismatch'
        });

        return res.status(403).json({
            error: 'Your quote request email must match your registered account email.'
        });
    }

    if (
        typeof b.message !== 'string' ||
        !b.message.trim()
    ) {
        return res.status(400).json({
            error: 'Please describe your project.'
        });
    }

    // Construct the record using an explicit field allowlist.
    // Never accept status, value, id or ownership from the client.
    const quoteRow = {
        quote_code: str(b.quote_code, 60),
        first_name: str(b.first_name, 100),
        last_name: str(b.last_name, 100),
        email: submittedEmail.slice(0, 200),
        phone: str(b.phone, 30),
        branch,
        service: selectedService.name,
        message: str(b.message, 4000),
        status: 'Pending'
    };

    if (productId) {
        quoteRow.product_id = productId;
    }

    // Link authenticated customers and administrators to their account.
    // Guest requests remain unlinked.
    if (user) {
        quoteRow.created_by = user.id;
    }

    const { data, error } = await supabase
        .from('quote_requests')
        .insert([quoteRow])
        .select();

    if (error) {
        if (error.code === '23505') {
            // Preserve the existing idempotency behaviour.
            const { data: existing, error: existingError } = await supabase
                .from('quote_requests')
                .select('*')
                .eq('quote_code', quoteRow.quote_code)
                .eq('email', quoteRow.email);

            if (existingError) return dbError(res, existingError);

            return res.status(200).json(existing || []);
        }

        return dbError(res, error);
    }

    const quote = data && data[0];

    if (quote) {
        sendCustomerQuoteReceivedEmail(quote)
            .catch(error => console.error('Customer quote email error:', error));

        sendAdminNewQuoteEmail(supabase, quote)
            .catch(error => console.error('Admin quote email error:', error));

        sendBranchManagerNewQuoteEmail(supabase, quote)
            .catch(error => console.error('Manager quote email error:', error));
    }

    auth.audit('quote_request_created', req, {
        quote_id: quote?.id || null,
        branch
    });

    return res.status(201).json(data);
}));

app.put('/api/quote-requests/:id', requireRole('Admin', 'Manager'), asyncRoute(async (req, res) => {
    const { data: before, error: beforeError } = await supabase
        .from('quote_requests')
        .select('status, branch')
        .eq('id', req.params.id)
        .maybeSingle();

    if (beforeError) return dbError(res, beforeError);
    if (!before) return res.status(404).json({ error: 'Quote request not found.' });

    const manager = isManager(req.user);
    const managerBranch = String(branchOf(req.user) || '').trim().toLowerCase();
    const requestBranch = String(before.branch || '').trim().toLowerCase();

    if (manager && (!managerBranch || requestBranch !== managerBranch)) {
        auth.audit('forbidden', req, {
            reason: 'manager_other_branch_quote'
        });

        return res.status(403).json({
            error: 'You can only update requests for your own branch.'
        });
    }

    const update = {};

    if (has(req.body || {}, 'status')) {
        if (!QUOTE_STATUSES.includes(req.body.status)) {
            return res.status(400).json({ error: 'Invalid status.' });
        }

        update.status = req.body.status;
    }

    if (has(req.body || {}, 'value')) {
        update.value = str(req.body.value, 50);
    }

    if (Object.keys(update).length === 0) {
        return res.status(400).json({ error: 'Nothing to update.' });
    }

    let updateQuery = supabase
        .from('quote_requests')
        .update(update)
        .eq('id', req.params.id);

    if (manager) {
        updateQuery = updateQuery.eq('branch', before.branch);
    }

    const { data, error } = await updateQuery.select();

    if (error) return dbError(res, error);

    if (!data || data.length === 0) {
        return res.status(409).json({
            error: 'The request could not be updated. It may have changed; refresh and try again.'
        });
    }

    const quote = data[0];

    if (update.status && update.status !== before.status) {
        sendCustomerStatusEmail(quote, before.status, quote.status)
            .catch(err => console.error('Status email error:', err));
    }

    return res.json(data);
}));    

app.delete('/api/quote-requests/:id', requireRole('Admin'), asyncRoute(async (req, res) => {
    const { data, error } = await supabase
        .from('quote_requests')
        .delete()
        .eq('id', req.params.id)
        .select();

    if (error) return dbError(res, error);
    if (!data?.length) return notFound(res);

    res.json({
        message: 'Quote request deleted',
        data
    });
}));

// NOTIFICATIONS
const notificationService = registerNotificationRoutes(
    app,
    supabase,
    auth
);

// EMAIL CHANGE APPROVAL
registerEmailChangeRoutes(
    app,
    supabase,
    auth,
    notificationService
);

// DELETION AND BACKUP ROUTES
registerDeletionRoutes(app, supabase, auth);

registerProductDeletionRoutes(
    app,
    supabase,
    auth,
    notificationService
);

const backups = createBackupService(app, supabase, auth);


// ERROR HANDLING

app.use((req, res) => {
    res.status(404).json({ error: 'Not found.' });
});

app.use((error, req, res, next) => {
    if (error?.type === 'entity.too.large') {
        return res.status(413).json({ error: 'Request is too large.' });
    }

    if (error?.type === 'entity.parse.failed') {
        return res.status(400).json({ error: 'Invalid JSON.' });
    }

    console.error('Unhandled API error:', error?.message || error);

    res.status(500).json({
        error: 'Something went wrong.'
    });
});


// START SERVER

const PORT = process.env.PORT || 5000;

app.listen(PORT, () => {
    console.log(`Node API running on port ${PORT}`);
    backups.start();
});
