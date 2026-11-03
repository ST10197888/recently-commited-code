
require('dotenv').config();

const { createClient } = require('@supabase/supabase-js');

const PAGE_SIZE = 500;
const BATCH_SIZE = 100;

// Only these public catalogue/content tables are seeded.
// User accounts and customer-generated records are deliberately excluded.
const SEED_TABLES = {
    products: {
        columns: ['id'],
        key: row => String(row.id),
        rows: [
            {
                id: 'modern-kitchen-suite',
                category: 'Kitchen Units',
                title: 'Modern Kitchen Suite',
                tagline: 'Sleek lines. Enduring quality.',
                description: 'Contemporary kitchen units crafted with premium PG Bison melamine boards. Fully customised to your kitchen dimensions and chosen finishes - from handle-less slab doors to classic shaker profiles. Our installation team handles everything from delivery to final fitting.',
                image: '/images/products/Kitchen Unit 12.jpeg',
                gallery: JSON.stringify(['/images/products/Kitchen Unit 12.jpeg', '/images/products/Kitchen Unit 7.jpeg', '/images/products/Kitchen Unit 10.jpeg']),
                features: JSON.stringify(['Floor-to-ceiling cabinets', 'Soft-close hinges & drawers', 'Custom island options', 'Integrated appliance housing', '15+ colour finishes']),
                finishes: JSON.stringify(['Arctic White', 'Graphite Matt', 'Woodgrain Oak', 'Concrete Grey', 'Gloss White']),
                lead_time: '2-4 weeks',
                tag: 'Popular',
                price: 'R8,500',
                is_from_price: true
            },
            {
                id: 'curved-luxury-kitchen',
                category: 'Kitchen Units',
                title: 'Curved Luxury Kitchen',
                tagline: 'Flowing forms, flawless finish.',
                description: 'Curved kitchen cabinetry that redefines what is possible with PG Bison board materials. CNC precision-cutting allows us to achieve gentle arcs and radius profiles that transform a kitchen into a design centrepiece. Available in any PG Bison melamine colour.',
                image: '/images/products/Kitchen Unit 6.jpeg',
                gallery: JSON.stringify(['/images/products/Kitchen Unit 6.jpeg', '/images/products/Kitchen Unit 9.jpeg', '/images/products/Kitchen Unit 11.jpeg']),
                features: JSON.stringify(['Curved radius profiles', 'Integrated appliance housing', 'Marble-effect finishes', 'LED under-cabinet lighting', 'Bespoke island designs']),
                finishes: JSON.stringify(['Pearl White', 'Warm Linen', 'Deep Navy', 'Sage Green', 'Terrazzo Effect']),
                lead_time: '3-5 weeks',
                tag: null,
                price: 'R14,000',
                is_from_price: true
            },
            {
                id: 'compact-galley-kitchen',
                category: 'Kitchen Units',
                title: 'Compact Galley Kitchen',
                tagline: 'Maximum storage, minimal footprint.',
                description: 'Purpose-built for narrow or apartment kitchens. Every centimetre is optimised - pull-out pantry columns, slim-line overhead units, and deep base drawers eliminate wasted space without sacrificing style.',
                image: '/images/products/Kitchen Unit 4.jpeg',
                gallery: JSON.stringify(['/images/products/Kitchen Unit 4.jpeg', '/images/products/Kitchen Unit 3.jpeg', '/images/products/Kitchen 2.jpeg']),
                features: JSON.stringify(['Pull-out pantry columns', 'Overhead storage units', 'Deep base drawers', 'Corner carousel units', 'Space-saving design']),
                finishes: JSON.stringify(['White Gloss', 'Light Oak', 'Anthracite', 'Soft Grey']),
                lead_time: '2-3 weeks',
                tag: null,
                price: 'R6,200',
                is_from_price: true
            },
            {
                id: 'wall-mounted-tv-unit',
                category: 'TV Stands',
                title: 'Wall-Mounted TV Unit',
                tagline: 'Float it. Flaunt it.',
                description: 'Floating TV units that create a clean, contemporary look with integrated cable management channels so no wires are ever visible. Specify your TV size and we engineer the unit to match - including the correct wall-mounting substrate.',
                image: '/images/products/Floating TV-1.jpeg',
                gallery: JSON.stringify(['/images/products/Floating TV-1.jpeg', '/images/products/Floating TV-2.jpeg', '/images/products/Floating TV-4.jpeg']),
                features: JSON.stringify(['Hidden cable management', 'Wall-mounted / floating', 'Open + closed storage', 'Custom width up to 3m', 'LED strip options']),
                finishes: JSON.stringify(['White Matt', 'Smoked Oak', 'Charcoal', 'Walnut Effect', 'Bianco']),
                lead_time: '1-2 weeks',
                tag: 'New',
                price: 'R3,800',
                is_from_price: true
            },
            {
                id: 'entertainment-console',
                category: 'TV Stands',
                title: 'Entertainment Console',
                tagline: 'Storage that works as hard as you play.',
                description: 'A floor-standing entertainment centre with deep media shelves, adjustable compartments, and a mix of doors and open bays. Designed to house sound equipment, streaming devices, gaming consoles, and decor in one cohesive unit.',
                image: '/images/products/Floating TV-8.jpeg',
                gallery: JSON.stringify(['/images/products/Floating TV-8.jpeg', '/images/products/Floating TV-9.jpeg', '/images/products/Floating TV-10.jpeg', '/images/products/Floating TV-5.jpeg']),
                features: JSON.stringify(['Deep media shelves', 'Adjustable compartments', 'Door + open bay combo', 'Matching wall panels', 'Base plinth or leg options']),
                finishes: JSON.stringify(['Arctic White', 'Teak Effect', 'Black Matt', 'Light Grey']),
                lead_time: '1-2 weeks',
                tag: null,
                price: 'R2,900',
                is_from_price: true
            },
            {
                id: 'full-length-wardrobe',
                category: 'Built-In Cupboards',
                title: 'Full-Length Wardrobe',
                tagline: 'Every centimetre, perfectly used.',
                description: 'Floor-to-ceiling built-in wardrobes that make the most of your bedroom height. Choose sliding or hinged doors, full-length mirrors, internal drawer systems, and a mix of hanging and shelving zones. Installed by our own team with minimal disruption.',
                image: 'https://images.unsplash.com/photo-1720087448033-db1904db294b?w=800&h=600&fit=crop&auto=format',
                gallery: JSON.stringify([
                    'https://images.unsplash.com/photo-1720087448033-db1904db294b?w=800&h=600&fit=crop&auto=format',
                    'https://images.unsplash.com/photo-1721738857328-3987700892e7?w=800&h=600&fit=crop&auto=format',
                    'https://images.unsplash.com/photo-1721742604074-8e998cee43f2?w=800&h=600&fit=crop&auto=format'
                ]),
                features: JSON.stringify(['Full-length mirror options', 'Internal drawer systems', 'Hanging + shelving zones', 'Soft-close doors', 'Floor-to-ceiling height']),
                finishes: JSON.stringify(['White', 'Sand', 'Pewter', 'Cashmere', 'Stone Grey']),
                lead_time: '1-2 weeks',
                tag: 'Popular',
                price: 'R4,500',
                is_from_price: true
            },
            {
                id: 'bedroom-suite-storage',
                category: 'Built-In Cupboards',
                title: 'Bedroom Suite Storage',
                tagline: 'Coordinated. Considered. Complete.',
                description: 'A fully coordinated bedroom storage solution combining open display shelving, closed cupboards, and drawer pedestals in a unified design. Pair with a matching headboard panel for a truly bespoke bedroom suite.',
                image: 'https://images.unsplash.com/photo-1721738857328-3987700892e7?w=800&h=600&fit=crop&auto=format',
                gallery: JSON.stringify([
                    'https://images.unsplash.com/photo-1721738857328-3987700892e7?w=800&h=600&fit=crop&auto=format',
                    'https://images.unsplash.com/photo-1720087448033-db1904db294b?w=800&h=600&fit=crop&auto=format'
                ]),
                features: JSON.stringify(['Modular configuration', 'Integrated LED lighting', 'Bedside pedestals', 'Headboard panel option', 'Premium handles & ironmongery']),
                finishes: JSON.stringify(['Linen', 'Dove White', 'Dusty Rose', 'Sage', 'Midnight Blue']),
                lead_time: '2-3 weeks',
                tag: null,
                price: 'R6,800',
                is_from_price: true
            },
            {
                id: 'precision-board-cutting',
                category: 'Cutting & Edging',
                title: 'Precision Board Cutting',
                tagline: 'Your measurements. Our precision.',
                description: 'CNC-precision cutting of any PG Bison chipboard, MDF, or melamine board to your exact specifications. Ideal for contractors, cabinet-makers, and advanced DIY builders. Supply your cut-list and we do the rest - often same day.',
                image: 'https://images.unsplash.com/photo-1659930087003-2d64e33181f7?w=800&h=600&fit=crop&auto=format',
                gallery: JSON.stringify([
                    'https://images.unsplash.com/photo-1659930087003-2d64e33181f7?w=800&h=600&fit=crop&auto=format',
                    'https://images.unsplash.com/photo-1497219055242-93359eeed651?w=800&h=600&fit=crop&auto=format'
                ]),
                features: JSON.stringify(['+/-0.5mm tolerance', 'All PG Bison board ranges', 'Same-day turnaround (bulk orders)', 'Digital cut-list accepted', 'Bulk discount pricing']),
                finishes: JSON.stringify(['All PG Bison melamine colours', 'Raw chipboard', 'MDF', 'Supawood']),
                lead_time: 'Same day - 2 days',
                tag: null,
                price: 'R18/cut',
                is_from_price: true
            },
            {
                id: 'edge-banding-finishing',
                category: 'Cutting & Edging',
                title: 'Edge Banding & Finishing',
                tagline: 'The detail that defines the finish.',
                description: 'Professional PVC and ABS edge banding applied with a hot-melt adhesive press and trimmed flush - colour-matched from the full PG Bison ABS edging range. Available on any board thickness from 16mm to 38mm.',
                image: 'https://images.unsplash.com/photo-1497219055242-93359eeed651?w=800&h=600&fit=crop&auto=format',
                gallery: JSON.stringify([
                    'https://images.unsplash.com/photo-1497219055242-93359eeed651?w=800&h=600&fit=crop&auto=format',
                    'https://images.unsplash.com/photo-1659930087003-2d64e33181f7?w=800&h=600&fit=crop&auto=format'
                ]),
                features: JSON.stringify(['PVC & ABS edging', 'Full colour-match range', 'Flush trim finish', '16mm-38mm boards', 'Bulk pricing available']),
                finishes: JSON.stringify(['Matched to all PG Bison decors', 'Contrasting accent options']),
                lead_time: 'Same day - 2 days',
                tag: null,
                price: 'R8/linear metre',
                is_from_price: true
            }
        ]
    },

    testimonials: {
        columns: ['name', 'project'],
        key: row => `${row.name}::${row.project}`,
        rows: [
            { name: 'Thabo Mokoena', role: 'Homeowner', location: 'Soweto', rating: 5, project: 'Full Kitchen Renovation', review: 'Woodlands did my entire kitchen from scratch. The finish on the PG Bison boards is absolutely stunning - my neighbours keep asking who did it. Professional team, on time, and within budget.' },
            { name: 'Priya Naidoo', role: 'Interior Designer', location: 'Johannesburg', rating: 5, project: 'Multiple Residential Projects', review: 'As a designer, I am very particular about material quality and precision. Woodlands consistently delivers flawless cuts and edge-banding that make my clients projects look polished. My go-to supplier.' },
            { name: 'Lebo Sithole', role: 'Building Contractor', location: 'Randfontein', rating: 5, project: 'Bulk Cutting & Edging', review: 'I have used the Randfontein branch for over three years. Bulk cutting orders are ready same day, and the edge-banding quality is consistent every single time. Reliable partner for any contractor.' },
            { name: 'Sandra Van Wyk', role: 'Homeowner', location: 'Roodepoort', rating: 5, project: 'Built-In Bedroom Cupboards', review: 'My built-in cupboards look like they came straight out of a magazine. The team measured twice, cut once, and the installation was immaculate. I especially love the soft-close doors - such a premium touch.' },
            { name: 'Mpho Dlamini', role: 'Property Developer', location: 'Gauteng', rating: 5, project: 'Multi-Unit Development', review: 'We fitted 12 units for a new development using Woodlands. Three branches made logistics easy - we split orders between Soweto and Roodepoort and still got everything on schedule. Quality always top-notch.' },
            { name: 'Anita Joubert', role: 'Homeowner', location: 'Soweto', rating: 4, project: 'Custom TV Unit', review: 'The TV unit they built for my lounge is exactly what I had in mind - floating, clean lines, with enough storage for all our media equipment. Good communication throughout. Very satisfied.' },
            { name: 'Kagiso Nkosi', role: 'Architect', location: 'Johannesburg', rating: 5, project: 'High-End Kitchen Suite', review: 'Specified Woodlands for a high-end residential project. The CNC precision on the kitchen cabinets was exceptional - tolerances I would struggle to find elsewhere at this price point. Will specify again.' },
            { name: 'Fatima Essack', role: 'Homeowner', location: 'Roodepoort', rating: 5, project: 'Kitchen Units + Island', review: 'From the initial quote to the final installation, the process was smooth and professional. The team cleaned up completely after themselves. The kitchen is everything I dreamed of.' },
            { name: 'Deon Pretorius', role: 'DIY Builder', location: 'Randfontein', rating: 5, project: 'Repeat Cutting & Edging Orders', review: 'I build my own cabinets and Woodlands does all my cutting and edging. Same-day service on most orders, and the cut precision is spot on every time. Best cutting service in the West Rand.' }
        ]
    },

    services: {
        columns: ['name'],
        key: row => row.name,
        rows: [
            { name: 'Kitchen Units', description: 'Custom kitchen cabinetry built to your exact specs.', image: '', is_active: true },
            { name: 'TV Stands', description: 'Wall-mounted and floor-standing entertainment units.', image: '', is_active: true },
            { name: 'Built-In Cupboards', description: 'Floor-to-ceiling wardrobes and storage.', image: '', is_active: true },
            { name: 'Cutting & Edging', description: 'CNC cutting and edge banding.', image: '', is_active: true },
            { name: 'General Enquiry', description: 'Anything else', image: '', is_active: true }
        ]
    },

    faqs: {
        columns: ['category', 'question'],
        key: row => `${row.category}::${row.question}`,
        rows: [
            { category: 'Materials', question: 'What is PG Bison and why does it matter?', answer: 'PG Bison is South Africa leading manufacturer of timber-based board products - melamine, chipboard, MDF, and Supawood. Using PG Bison materials means your units meet the highest local standards for strength, durability, and finish quality. As an authorised partner, we guarantee every board is 100% genuine.' },
            { category: 'Process', question: 'Do you offer a measuring and design service?', answer: 'Yes. We offer a free consultation and site measurement for all custom built-in units. A team member will visit your space, take precise measurements, suggest the best material and finish options, and provide a detailed quote before any work begins.' },
            { category: 'Services', question: 'What services do you offer besides custom units?', answer: 'In addition to full kitchen units, TV stands, and built-in cupboards, we provide precision CNC board cutting and professional edge-banding services. Contractors and DIY builders who just need boards cut to size or edged are welcome - often with same-day turnaround.' },
            { category: 'Process', question: 'How long does a custom kitchen or cupboard take?', answer: 'Lead times depend on complexity. A standard built-in cupboard typically takes 5-10 business days from confirmed order to installation. A full kitchen suite may take 2-4 weeks. You will receive a firm delivery timeline in your quote.' },
            { category: 'Materials', question: 'What colour and finish options are available?', answer: 'We stock the full PG Bison Melamine range - over 70 colours and textures from solid whites and greys to wood-grain effects and high-gloss finishes. Special-order finishes from the PG Bison catalogue are also available upon request.' },
            { category: 'Installation', question: 'Do you install the units or just supply them?', answer: 'We do both. Choose a supply-only option (ideal for contractors with their own installation teams) or a full supply-and-install service. All installation is carried out by our own trained team with proper tools and fixings.' },
            { category: 'Branches', question: 'Which branch should I visit?', answer: 'We have three branches: Soweto, Roodepoort, and Randfontein. Visit whichever is closest to your project site. All three branches stock the same product ranges and offer the same services. You can also call or WhatsApp us to start your quote remotely.' },
            { category: 'Trade', question: 'Do you work with interior designers and contractors?', answer: 'Absolutely. A significant portion of our work comes from interior designers and building contractors. We offer trade pricing, bulk-order discounts, and dedicated account management for regular clients.' },
            { category: 'Services', question: 'Is there a minimum order for cutting and edging?', answer: 'There is no strict minimum for walk-in customers - we can cut and edge a single sheet if needed. For bulk orders (10+ sheets), we offer discounted pricing and prioritised turnaround. Contact your nearest branch for current bulk pricing.' },
            { category: 'Warranty', question: 'What warranty do you offer?', answer: 'All custom-built units come with a 12-month workmanship warranty covering any defects in construction or installation. PG Bison board materials carry their own manufacturer warranty for structural integrity. We are committed to making it right if anything falls short.' }
        ]
    },

    branches: {
        columns: ['name'],
        key: row => row.name,
        rows: [
            { name: 'Soweto', region: 'Soweto', phone: '+27 11 xxx xxxx', hours: 'Mon-Fri: 8am-5pm', notes: 'Main branch', address: 'Soweto, Johannesburg, Gauteng' },
            { name: 'Roodepoort', region: 'Roodepoort', phone: '+27 11 xxx xxxx', hours: 'Mon-Fri: 8am-5pm', notes: 'Branch office', address: 'Roodepoort, Johannesburg, Gauteng' },
            { name: 'Randfontein', region: 'Randfontein', phone: '+27 11 xxx xxxx', hours: 'Mon-Fri: 8am-5pm', notes: 'Branch office', address: 'Randfontein, Gauteng' }
        ]
    }
};

function createSupabaseClient() {
    const url = process.env.SUPABASE_URL;
    const key = process.env.SUPABASE_SECRET_KEY;

    if (!url || !key) {
        throw new Error(
            'SUPABASE_URL and SUPABASE_SECRET_KEY must be configured.'
        );
    }

    return createClient(url, key, {
        auth: {
            persistSession: false,
            autoRefreshToken: false,
            detectSessionInUrl: false
        }
    });
}

function parseArguments(args) {
    const allowed = new Set(['--apply']);

    for (const arg of args) {
        if (arg === '--reset') {
            throw new Error(
                '--reset is disabled because this script targets the live database.'
            );
        }

        if (!allowed.has(arg)) {
            throw new Error(`Unknown argument: ${arg}`);
        }
    }

    if (args.filter(arg => arg === '--apply').length > 1) {
        throw new Error('--apply was supplied more than once.');
    }

    return {
        apply: args.includes('--apply')
    };
}

async function getExistingRows(supabase, table, columns) {
    const results = [];
    let from = 0;

    while (true) {
        const to = from + PAGE_SIZE - 1;

        const { data, error } = await supabase
            .from(table)
            .select(columns.join(','))
            .order('id', { ascending: true })
            .range(from, to);

        if (error) {
            throw new Error(
                `Could not read existing ${table}: ${error.message}`
            );
        }

        const rows = data || [];
        results.push(...rows);

        if (rows.length < PAGE_SIZE) {
            break;
        }

        from += PAGE_SIZE;
    }

    return results;
}

function validateSeedRows(table, rows, keyFunction) {
    const keys = new Set();

    for (const row of rows) {
        const key = keyFunction(row);

        if (key === undefined || key === null || String(key).trim() === '') {
            throw new Error(`A seed record in ${table} has an invalid matching key.`);
        }

        const normalisedKey = String(key).trim().toLowerCase();

        if (keys.has(normalisedKey)) {
            throw new Error(
                `The seed data contains duplicate keys in ${table}: ${key}`
            );
        }

        keys.add(normalisedKey);
    }
}

async function seedTable(supabase, table, config, apply) {
    const { columns, key, rows } = config;

    validateSeedRows(table, rows, key);

    const existingRows = await getExistingRows(
        supabase,
        table,
        columns
    );

    const existingKeys = new Set(
        existingRows.map(row =>
            String(key(row)).trim().toLowerCase()
        )
    );

    const missingRows = rows.filter(row =>
        !existingKeys.has(
            String(key(row)).trim().toLowerCase()
        )
    );

    console.log(`\n${table}`);
    console.log(`  Existing records: ${existingRows.length}`);
    console.log(`  Seed records:     ${rows.length}`);
    console.log(`  Missing records:  ${missingRows.length}`);

    if (missingRows.length === 0) {
        console.log('  Nothing to insert.');
        return {
            inserted: 0,
            skipped: rows.length
        };
    }

    if (!apply) {
        console.log('  DRY RUN: these records would be inserted.');

        return {
            inserted: 0,
            skipped: rows.length - missingRows.length,
            planned: missingRows.length
        };
    }

    let inserted = 0;

    for (
        let index = 0;
        index < missingRows.length;
        index += BATCH_SIZE
    ) {
        const batch = missingRows.slice(
            index,
            index + BATCH_SIZE
        );

        const { error } = await supabase
            .from(table)
            .insert(batch);

        if (error) {
            throw new Error(
                `Insertion failed for ${table} after ${inserted} ` +
                `new records: ${error.message}. Earlier inserts may already exist.`
            );
        }

        inserted += batch.length;
    }

    console.log(`  INSERTED: ${inserted}`);

    return {
        inserted,
        skipped: rows.length - missingRows.length
    };
}

async function run() {
    const { apply } = parseArguments(process.argv.slice(2));

    const supabase = createSupabaseClient();

    console.log('');
    console.log('WOODLANDS LIVE DATABASE SEEDER');
    console.log('================================');
    console.log(
        apply
            ? 'MODE: APPLY - missing records will be inserted.'
            : 'MODE: DRY RUN - no database changes will be made.'
    );

    console.log('');
    console.log('Safety rules:');
    console.log('- Existing records will not be updated.');
    console.log('- No records will be deleted.');
    console.log('- No prototype accounts will be created.');
    console.log('- No mock quote requests will be inserted.');
    console.log('');

    let totalInserted = 0;
    let totalSkipped = 0;
    let totalPlanned = 0;

    for (const [table, config] of Object.entries(SEED_TABLES)) {
        const result = await seedTable(
            supabase,
            table,
            config,
            apply
        );

        totalInserted += result.inserted || 0;
        totalSkipped += result.skipped || 0;
        totalPlanned += result.planned || 0;
    }

    console.log('');
    console.log('================================');
    console.log('SEED SUMMARY');
    console.log('================================');

    if (apply) {
        console.log(`New records inserted: ${totalInserted}`);
    } else {
        console.log(`Records that would be inserted: ${totalPlanned}`);
    }

    console.log(`Existing records left untouched: ${totalSkipped}`);

    if (!apply) {
        console.log('');
        console.log('Dry run completed. No database changes were made.');
        console.log('Review the counts, then use: npm run seed -- --apply');
    } else {
        console.log('');
        console.log('Seeding completed successfully.');
    }
}

run().catch(error => {
    console.error('');
    console.error('SEEDING FAILED');
    console.error(error.message);
    process.exitCode = 1;
});
