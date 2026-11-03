require('dotenv').config();
const fs = require('fs');
const zlib = require('zlib');
const { createClient } = require('@supabase/supabase-js');

(async () => {
    const args = process.argv.slice(2);
    const source = args.find(a => !a.startsWith('--'));
    const apply = args.includes('--apply');
    const only = args.includes('--table') ? args[args.indexOf('--table') + 1] : null;
    if (!source) { console.error('Usage: node restoreBackup.js <file.json.gz | name-in-bucket> [--table name] [--apply]'); process.exit(1); }

    const supabase = createClient(process.env.SUPABASE_URL, process.env.SUPABASE_KEY, { auth: { persistSession: false } });

    let buffer;
    if (fs.existsSync(source)) {
        buffer = fs.readFileSync(source);
    } else {
        const { data, error } = await supabase.storage.from(process.env.BACKUP_BUCKET || 'woodlands-backups').download(source);
        if (error) { console.error('Could not download backup:', error.message); process.exit(1); }
        buffer = Buffer.from(await data.arrayBuffer());
    }

    const backup = JSON.parse(zlib.gunzipSync(buffer).toString());
    console.log(`Backup taken ${backup.meta.created_at} (${backup.meta.trigger})`);
    console.log(apply ? 'MODE: APPLY - writing to the database' : 'MODE: dry run - add --apply to write');

    for (const [table, rows] of Object.entries(backup.data)) {
        if (only && only !== table) continue;
        if (!apply) { console.log(`  ${table}: ${rows.length} rows would be restored`); continue; }
        let done = 0;
        for (let i = 0; i < rows.length; i += 500) {
            const { error } = await supabase.from(table).upsert(rows.slice(i, i + 500), { onConflict: 'id' });
            if (error) { console.error(`  ${table}: FAILED - ${error.message}`); break; }
            done += Math.min(500, rows.length - i);
        }
        console.log(`  ${table}: ${done}/${rows.length} rows restored`);
    }
})();