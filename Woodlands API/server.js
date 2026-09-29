require('dotenv').config();
const express = require('express');
const cors = require('cors');
const { createClient } = require('@supabase/supabase-js');

const app = express();

// Middleware
app.use(cors()); // Allows your C# app to make requests
app.use(express.json()); // Parses incoming JSON data

// Initialize Supabase
const supabase = createClient(
    process.env.SUPABASE_URL,
    process.env.SUPABASE_KEY
);


// ENDPOINTS

// LOGIN ROUTES

app.post('/api/auth/login', async (req, res) => {
    const { email, password } = req.body;
    try {
        const { data: authData, error: authError } = await supabase.auth.signInWithPassword({ email, password });
        if (authError || !authData.user) {
            return res.status(401).json({ error: "Invalid email or password." });
        }

        // Fetch the app_users row for this user
        const { data: profile, error: profileError } = await supabase
            .from('app_users')
            .select('*')
            .eq('id', authData.user.id)
            .single();

        if (profileError || !profile) {
            return res.status(404).json({ error: "User profile not found." });
        }
        if (!profile.active) {
            return res.status(403).json({ error: "This account is currently inactive." });
        }

        res.json({ user: profile });
    } catch (ex) {
        res.status(500).json({ error: ex.message });
    }
});

// REGISTER ROUTES

app.post('/api/auth/register', async (req, res) => {
    const { fullName, email, password, phone } = req.body;
    try {
        // 1. Create the Supabase Auth user
        const { data: authData, error: authError } = await supabase.auth.admin.createUser({
            email: email,
            password: password,
            email_confirm: true
        });

        if (authError || !authData.user) {
            return res.status(400).json({ error: authError?.message || "Registration failed." });
        }

        // 2. Insert the app_users profile row
        const newUser = {
            id: authData.user.id,
            full_name: fullName,
            email: email,
            phone: phone || "",
            role: "Customer",
            branch: null,
            active: true
        };

        const { error: profileError } = await supabase
            .from('app_users')
            .insert([newUser]);

        if (profileError) {
            return res.status(500).json({ error: profileError.message });
        }

        res.status(201).json({ message: "Registration successful." });
    } catch (ex) {
        res.status(500).json({ error: ex.message });
    }
});

// APP USERS (Admin management only)

app.get('/api/app-users', async (req, res) => {
    const { data, error } = await supabase.from('app_users').select('*').order('full_name');
    if (error) return res.status(500).json({ error: error.message });
    res.json(data);
});

app.put('/api/app-users/:id', async (req, res) => {
    const { data, error } = await supabase
        .from('app_users')
        .update(req.body)
        .eq('id', req.params.id)
        .select();
    if (error) return res.status(500).json({ error: error.message });
    res.json(data);
});

app.delete('/api/app-users/:id', async (req, res) => {
    const { data, error } = await supabase
        .from('app_users')
        .delete()
        .eq('id', req.params.id)
        .select();
    if (error) return res.status(500).json({ error: error.message });
    res.json({ message: 'User deleted', data });
});
 
// PRODUCTS ROUTES
 
app.get('/api/products', async (req, res) => {
    const { data, error } = await supabase.from('products').select('*').order('created_at', { ascending: false });
    if (error) return res.status(500).json({ error: error.message });

    // Convert string JSON columns into real arrays
    const cleaned = (data || []).map(p => ({
        ...p,
        gallery: safeParse(p.gallery),
        features: safeParse(p.features),
        finishes: safeParse(p.finishes)
    }));

    res.json(cleaned);
});

app.get('/api/products/:id', async (req, res) => {
    const { data, error } = await supabase.from('products').select('*').eq('id', req.params.id).single();
    if (error) return res.status(500).json({ error: error.message });

    const cleaned = {
        ...data,
        gallery: safeParse(data.gallery),
        features: safeParse(data.features),
        finishes: safeParse(data.finishes)
    };

    res.json(cleaned);
});

app.post('/api/products', async (req, res) => {
    const { data, error } = await supabase.from('products').insert([req.body]).select();
    if (error) return res.status(500).json({ error: error.message });
    res.status(201).json(data);
});

app.put('/api/products/:id', async (req, res) => {
    const { data, error } = await supabase.from('products').update(req.body).eq('id', req.params.id).select();
    if (error) return res.status(500).json({ error: error.message });
    res.json(data);
});

app.delete('/api/products/:id', async (req, res) => {
    const { data, error } = await supabase.from('products').delete().eq('id', req.params.id).select();
    if (error) return res.status(500).json({ error: error.message });
    res.json({ message: 'Product deleted', data });
});

 
// BRANCHES ROUTES (Uses bigint ID)
 
app.get('/api/branches', async (req, res) => {
    const { data, error } = await supabase.from('branches').select('*').order('name');
    if (error) return res.status(500).json({ error: error.message });
    res.json(data);
});

app.get('/api/branches/:id', async (req, res) => {
    const { data, error } = await supabase.from('branches').select('*').eq('id', req.params.id).single();
    if (error) return res.status(500).json({ error: error.message });
    res.json(data);
});

app.post('/api/branches', async (req, res) => {
    const { data, error } = await supabase.from('branches').insert([req.body]).select();
    if (error) return res.status(500).json({ error: error.message });
    res.status(201).json(data);
});

app.put('/api/branches/:id', async (req, res) => {
    const { data, error } = await supabase.from('branches').update(req.body).eq('id', req.params.id).select();
    if (error) return res.status(500).json({ error: error.message });
    res.json(data);
});

app.delete('/api/branches/:id', async (req, res) => {
    const { data, error } = await supabase.from('branches').delete().eq('id', req.params.id).select();
    if (error) return res.status(500).json({ error: error.message });
    res.json({ message: 'Branch deleted', data });
});

 
// SERVICES ROUTES (Uses bigint ID)
 
app.get('/api/services', async (req, res) => {
    const { data, error } = await supabase.from('services').select('*').order('name');
    if (error) return res.status(500).json({ error: error.message });
    res.json(data);
});

app.post('/api/services', async (req, res) => {
    const { data, error } = await supabase.from('services').insert([req.body]).select();
    if (error) return res.status(500).json({ error: error.message });
    res.status(201).json(data);
});

app.put('/api/services/:id', async (req, res) => {
    const { data, error } = await supabase.from('services').update(req.body).eq('id', req.params.id).select();
    if (error) return res.status(500).json({ error: error.message });
    res.json(data);
});

app.delete('/api/services/:id', async (req, res) => {
    const { data, error } = await supabase.from('services').delete().eq('id', req.params.id).select();
    if (error) return res.status(500).json({ error: error.message });
    res.json({ message: 'Service deleted', data });
});

 
// TESTIMONIALS ROUTES (Uses bigint ID)
 
app.get('/api/testimonials', async (req, res) => {
    const { data, error } = await supabase.from('testimonials').select('*').order('created_at', { ascending: false });
    if (error) return res.status(500).json({ error: error.message });
    res.json(data);
});

app.post('/api/testimonials', async (req, res) => {
    const { data, error } = await supabase.from('testimonials').insert([req.body]).select();
    if (error) return res.status(500).json({ error: error.message });
    res.status(201).json(data);
});

app.delete('/api/testimonials/:id', async (req, res) => {
    const { data, error } = await supabase.from('testimonials').delete().eq('id', req.params.id).select();
    if (error) return res.status(500).json({ error: error.message });
    res.json({ message: 'Testimonial deleted', data });
});

 
// FAQS ROUTES (Uses bigint ID)
 
app.get('/api/faqs', async (req, res) => {
    const { data, error } = await supabase.from('faqs').select('*').order('category');
    if (error) return res.status(500).json({ error: error.message });
    res.json(data);
});

app.post('/api/faqs', async (req, res) => {
    const { data, error } = await supabase.from('faqs').insert([req.body]).select();
    if (error) return res.status(500).json({ error: error.message });
    res.status(201).json(data);
});

app.delete('/api/faqs/:id', async (req, res) => {
    const { data, error } = await supabase.from('faqs').delete().eq('id', req.params.id).select();
    if (error) return res.status(500).json({ error: error.message });
    res.json({ message: 'FAQ deleted', data });
});

 
// HOMEPAGE ASSETS ROUTES (Uses bigint ID)
 
app.get('/api/homepage-assets', async (req, res) => {
    const { data, error } = await supabase.from('homepage_assets').select('*').order('display_order');
    if (error) return res.status(500).json({ error: error.message });
    res.json(data);
});

 
// QUOTE REQUESTS ROUTES (Uses uuid ID)

app.get('/api/quote-requests', async (req, res) => {
    const { data, error } = await supabase.from('quote_requests').select('*').order('created_at', { ascending: false });
    if (error) return res.status(500).json({ error: error.message });
    res.json(data);
});

app.post('/api/quote-requests', async (req, res) => {
    const { data, error } = await supabase.from('quote_requests').insert([req.body]).select();
    if (error) return res.status(500).json({ error: error.message });
    res.status(201).json(data);
});

app.put('/api/quote-requests/:id', async (req, res) => {
    const { data, error } = await supabase.from('quote_requests').update(req.body).eq('id', req.params.id).select();
    if (error) return res.status(500).json({ error: error.message });
    res.json(data);
});

app.delete('/api/quote-requests/:id', async (req, res) => {
    const { data, error } = await supabase.from('quote_requests').delete().eq('id', req.params.id).select();
    if (error) return res.status(500).json({ error: error.message });
    res.json({ message: 'Quote request deleted', data });
});

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


// Start the server
const PORT = process.env.PORT || 5000;
app.listen(PORT, () => {
    console.log(`Node API running on http://localhost:${PORT}`);
});