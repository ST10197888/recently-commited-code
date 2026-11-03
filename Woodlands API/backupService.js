const cron = require('node-cron');
const zlib = require('zlib');
const { promisify } = require('util');
const gzip = promisify(zlib.gzip);

const BUCKET = process.env.BACKUP_BUCKET || 'woodlands-backups';
const RETENTION = Math.max(1, parseInt(process.env.BACKUP_RETENTION, 10) || 14);
const MAX_AGE_HOURS = parseInt(process.env.BACKUP_MAX_AGE_HOURS, 10) || 26;
const PREFIX = 'woodlands-backup-';
const PAGE = 1000;

const DEFAULT_CRON = process.env.BACKUP_CRON || '0 12 * * *';   // 12:00 daily
const DEFAULT_TZ = process.env.BACKUP_TIMEZONE || 'Africa/Johannesburg';
const SETTINGS_FILE = 'backup-settings.json'; // doesn't start with PREFIX, so it is never listed or pruned as a backup
const TIME_RE = /^([01]\d|2[0-3]):([0-5]\d)$/;

const TABLES = [
    'app_users', 'branches', 'products', 'services', 'faqs', 'testimonials',
    'homepage_assets', 'quote_requests', 'deletion_requests', 'account_archive'
];
// Never write one-time restore-code hashes into a backup file
const STRIP_COLUMNS = { account_archive: ['code_hash', 'code_expires_at', 'code_sent_at', 'code_attempts'] };

module.exports = function createBackupService(app, supabase, auth) {
    let running = false;
    let last = null; // summary of the most recent run in this process
    let task = null; // the running cron job
    let settings = { enabled: process.env.BACKUP_ENABLED !== 'false', cron: DEFAULT_CRON };

    function cronToTime(expr) {
        const m = /^(\d{1,2}) (\d{1,2}) \* \* \*$/.exec(expr);
        return m ? `${m[2].padStart(2, '0')}:${m[1].padStart(2, '0')}` : null;
    }

    // Saved admin settings override the environment defaults
    async function loadSettings() {
        try {
            await ensureBucket();
            const { data, error } = await supabase.storage.from(BUCKET).download(SETTINGS_FILE);
            if (error || !data) return; // nothing saved yet - keep the defaults
            const saved = JSON.parse(await data.text());
            if (typeof saved.enabled === 'boolean') settings.enabled = saved.enabled;
            if (typeof saved.cron === 'string' && cron.validate(saved.cron)) settings.cron = saved.cron;
        } catch (e) { console.error('[backup] could not load settings, using defaults:', e.message); }
    }

    async function saveSettings(updatedBy) {
        const body = Buffer.from(JSON.stringify({ ...settings, updated_by: updatedBy, updated_at: new Date().toISOString() }));
        const up = await supabase.storage.from(BUCKET)
            .upload(SETTINGS_FILE, body, { contentType: 'application/json', upsert: true });
        if (up.error) throw new Error('Could not save settings: ' + up.error.message);
    }

    // (Re)starts the cron job from the current settings
    function applySchedule() {
        if (task) { task.stop(); task = null; }
        if (!settings.enabled) { console.log('[backup] scheduler disabled'); return; }
        if (!cron.validate(settings.cron)) {
            console.error(`[backup] invalid schedule "${settings.cron}" - scheduler not started`);
            return;
        }
        task = cron.schedule(settings.cron, () => { runBackup('schedule'); }, { timezone: DEFAULT_TZ });
        console.log(`[backup] scheduled "${settings.cron}" (${DEFAULT_TZ}), keeping ${RETENTION} backups`);
    }

    async function ensureBucket() {
        const { data, error } = await supabase.storage.listBuckets();
        if (error) throw new Error('Cannot list storage buckets: ' + error.message);
        if (!data.some(b => b.name === BUCKET)) {
            const created = await supabase.storage.createBucket(BUCKET, { public: false });
            if (created.error) throw new Error('Cannot create backup bucket: ' + created.error.message);
            console.log(`[backup] created private bucket "${BUCKET}"`);
        }
    }

    async function exportTable(table) {
        const rows = [];
        for (let from = 0; ; from += PAGE) {
            const { data, error } = await supabase.from(table).select('*').range(from, from + PAGE - 1);
            if (error) throw new Error(error.message);
            rows.push(...data);
            if (data.length < PAGE) break;
        }
        const strip = STRIP_COLUMNS[table];
        return strip ? rows.map(r => { const c = { ...r }; strip.forEach(k => delete c[k]); return c; }) : rows;
    }

    async function listBackups() {
        const { data, error } = await supabase.storage.from(BUCKET)
            .list('', { limit: 1000, sortBy: { column: 'created_at', order: 'desc' } });
        if (error) throw new Error(error.message);
        return (data || [])
            .filter(f => f.name.startsWith(PREFIX))
            .map(f => ({ name: f.name, size: (f.metadata && f.metadata.size) || null, created_at: f.created_at }));
    }

    async function prune() {
        const all = await listBackups(); // newest first
        const old = all.slice(RETENTION).map(f => f.name);
        if (old.length) {
            const { error } = await supabase.storage.from(BUCKET).remove(old);
            if (error) console.error('[backup] prune failed:', error.message);
            else console.log(`[backup] removed ${old.length} old backup(s)`);
        }
        return old.length;
    }

    async function runBackup(trigger = 'schedule') {
        if (running) return { skipped: true, reason: 'A backup is already running.' };
        running = true;
        const startedAt = new Date();
        try {
            await ensureBucket();
            const data = {};
            const counts = {};
            const errors = {};
            for (const table of TABLES) {
                try {
                    data[table] = await exportTable(table);
                    counts[table] = data[table].length;
                } catch (e) {
                    errors[table] = e.message;
                    console.error(`[backup] table ${table} failed: ${e.message}`);
                }
            }
            if (Object.keys(data).length === 0) throw new Error('No tables could be exported.');

            const meta = { version: 1, created_at: startedAt.toISOString(), trigger, counts, errors };
            const file = await gzip(Buffer.from(JSON.stringify({ meta, data })));
            const name = `${PREFIX}${startedAt.toISOString().replace(/[:.]/g, '-')}.json.gz`;

            const up = await supabase.storage.from(BUCKET)
                .upload(name, file, { contentType: 'application/gzip', upsert: false });
            if (up.error) throw new Error('Upload failed: ' + up.error.message);

            const pruned = await prune();
            last = { ok: true, name, trigger, at: startedAt.toISOString(), bytes: file.length, counts, errors, pruned };
            console.log(`[backup] OK ${name} (${file.length} bytes) tables=${JSON.stringify(counts)}`);
            return last;
        } catch (e) {
            last = { ok: false, trigger, at: startedAt.toISOString(), error: e.message };
            console.error('[backup] FAILED:', e.message);
            return last;
        } finally {
            running = false;
        }
    }

    //  Admin-only endpoints 
    app.get('/api/admin/backups', auth.requireRole('Admin'), async (req, res) => {
        try {
            res.json({
                bucket: BUCKET, retention: RETENTION,
                                enabled: settings.enabled,
                schedule: settings.cron,
                time: cronToTime(settings.cron),
                timezone: DEFAULT_TZ,
                last_run: last, backups: await listBackups()
            });
        } catch (e) { res.status(500).json({ error: e.message }); }
    });

    app.post('/api/admin/backups/run', auth.requireRole('Admin'), async (req, res) => {
        const result = await runBackup('manual:' + req.user.email);
        res.status(result.ok ? 201 : result.skipped ? 409 : 500).json(result);
    });

        // Admin changes the daily backup time and/or switches the scheduler on or off
    app.put('/api/admin/backups/schedule', auth.requireRole('Admin'), async (req, res) => {
        const body = req.body || {};
        const has = (k) => Object.prototype.hasOwnProperty.call(body, k);
        if (!has('time') && !has('enabled')) return res.status(400).json({ error: 'Nothing to update.' });

        const next = { ...settings };
        if (has('enabled')) {
            if (typeof body.enabled !== 'boolean') return res.status(400).json({ error: 'enabled must be true or false.' });
            next.enabled = body.enabled;
        }
        if (has('time')) {
            const m = TIME_RE.exec(String(body.time));
            if (!m) return res.status(400).json({ error: 'time must be 24-hour HH:MM, e.g. "12:00".' });
            next.cron = `${parseInt(m[2], 10)} ${parseInt(m[1], 10)} * * *`;
        }

        const previous = settings;
        try {
            settings = next;
            await saveSettings(req.user.email);
        } catch (e) {
            settings = previous; // keep the old schedule if it could not be saved
            return res.status(500).json({ error: e.message });
        }
        applySchedule();
        auth.audit('backup_schedule_changed', req, { cron: settings.cron, enabled: settings.enabled });
        res.json({ enabled: settings.enabled, schedule: settings.cron, time: cronToTime(settings.cron), timezone: DEFAULT_TZ });
    });

    // Used by the website's Backups page (admin only)

    // Current ids of one table (used to work out what a restore or rollback would add, overwrite or delete)
    async function liveIds(table) {
        const ids = [];
        for (let from = 0; ; from += PAGE) {
            const { data, error } = await supabase.from(table).select('id').order('id').range(from, from + PAGE - 1);
            if (error) throw new Error(error.message);
            ids.push(...data.map(r => String(r.id)));
            if (data.length < PAGE) break;
        }
        return ids;
    }

    // The live data of every table (or only ?table=name), straight from the database
    app.get('/api/admin/export', auth.requireRole('Admin'), async (req, res) => {
        try {
            const only = typeof req.query.table === 'string' && req.query.table ? req.query.table : null;
            if (only && !TABLES.includes(only)) return res.status(400).json({ error: 'Unknown table.' });
            const data = {};
            const counts = {};
            const errors = {};
            for (const table of TABLES) {
                if (only && only !== table) continue;
                try {
                    data[table] = await exportTable(table);
                    counts[table] = data[table].length;
                } catch (e) { errors[table] = e.message; }
            }
            if (Object.keys(data).length === 0) return res.status(500).json({ error: 'No tables could be exported.' });
            auth.audit('backup_export', req, { tables: Object.keys(data).join(',') });
            res.json({ counts, errors, data });
        } catch (e) { res.status(500).json({ error: e.message }); }
    });

    // Writes snapshot data back into the database.

    //   mode "restore"  : rows in the snapshot are written back (same id = overwritten); nothing is deleted
    //   mode "rollback" : the same, and rows that are NOT in the snapshot are deleted

    app.post('/api/admin/import', auth.requireRole('Admin'), async (req, res) => {
        const { data, mode, table, dryRun } = req.body || {};
        if (!data || typeof data !== 'object' || Array.isArray(data)) return res.status(400).json({ error: 'No data supplied.' });
        if (mode !== 'restore' && mode !== 'rollback') return res.status(400).json({ error: 'mode must be "restore" or "rollback".' });
        if (table && !TABLES.includes(table)) return res.status(400).json({ error: 'Unknown table.' });

        const wanted = TABLES.filter(t => Array.isArray(data[t]) && (!table || table === t));
        if (wanted.length === 0) return res.status(400).json({ error: 'The snapshot has no rows for that table.' });

        const apply = dryRun !== true;
        if (apply) {
            if (running) return res.status(409).json({ error: 'A backup is running. Try again in a moment.' });
            running = true;
        }
        try {
            const me = String(req.user.id);
            const report = {};
            const plan = {};

            for (const t of wanted) {
                const all = data[t];
                if (all.some(r => !r || r.id === undefined || r.id === null)) {
                    report[t] = { error: 'Some rows have no id.' };
                    continue;
                }
                const now = await liveIds(t);
                const nowSet = new Set(now);
                const snapIds = new Set(all.map(r => String(r.id)));
                const rows = t === 'app_users' ? all.filter(r => String(r.id) !== me) : all;
                const toAdd = rows.filter(r => !nowSet.has(String(r.id))).length;
                const toDelete = mode === 'rollback'
                    ? now.filter(id => !snapIds.has(id) && !(t === 'app_users' && id === me))
                    : [];
                plan[t] = { rows, toDelete };
                report[t] = {
                    in_snapshot: all.length, in_database: now.length,
                    to_add: toAdd, to_update: rows.length - toAdd, to_delete: toDelete.length
                };
            }

            if (apply) {
                // write rows back (parents first)
                for (const t of wanted) {
                    if (!plan[t]) continue;
                    const { rows } = plan[t];
                    for (let i = 0; i < rows.length; i += 500) {
                        const { error } = await supabase.from(t).upsert(rows.slice(i, i + 500), { onConflict: 'id' });
                        if (error) { report[t].error = error.message; break; }
                    }
                }
                // rollback only: delete rows that are not in the snapshot (children first)
                if (mode === 'rollback') {
                    for (const t of [...wanted].reverse()) {
                        if (!plan[t] || report[t].error) continue;
                        const { toDelete } = plan[t];
                        for (let i = 0; i < toDelete.length; i += 100) {
                            const { error } = await supabase.from(t).delete().in('id', toDelete.slice(i, i + 100));
                            if (error) { report[t].error = error.message; break; }
                        }
                    }
                }
            }

            const ok = Object.values(report).every(r => !r.error);
            auth.audit(apply ? 'backup_import' : 'backup_import_preview', req, { mode, table: table || 'all', ok });
            res.json({ ok, dry_run: !apply, mode, tables: report });
        } catch (e) {
            console.error('[backup] import failed:', e.message);
            res.status(500).json({ error: e.message });
        } finally {
            if (apply) running = false;
        }
    });

    // Short-lived (60 s) download link for one backup file
    app.get('/api/admin/backups/:name/download', auth.requireRole('Admin'), async (req, res) => {
        if (!/^woodlands-backup-[\w.-]+\.json\.gz$/.test(req.params.name)) return res.status(400).json({ error: 'Invalid backup name.' });
        const { data, error } = await supabase.storage.from(BUCKET).createSignedUrl(req.params.name, 60);
        if (error) return res.status(404).json({ error: 'Backup not found.' });
        res.json({ url: data.signedUrl, expires_in: 60 });
    });

    //  Scheduler
       async function start() {
        await loadSettings();
        applySchedule();
        if (!settings.enabled) return;

        // Catch-up after a restart / wake-up from sleep
        setTimeout(async () => {
            try {
                if (!settings.enabled) return;
                await ensureBucket();
                const newest = (await listBackups())[0];
                const ageHours = newest ? (Date.now() - new Date(newest.created_at).getTime()) / 36e5 : Infinity;
                if (ageHours > MAX_AGE_HOURS) {
                    console.log('[backup] latest backup is stale - running catch-up backup');
                    runBackup('startup-catchup');
                }
            } catch (e) { console.error('[backup] catch-up check failed:', e.message); }
        }, 30 * 1000);
    }

    return { start, runBackup, listBackups };
};