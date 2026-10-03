package com.woodlands.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

class LocalDb(context: Context) : SQLiteOpenHelper(context, "woodlands_mobile.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE products(id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT, title TEXT, tagline TEXT, description TEXT, image TEXT, gallery TEXT, features TEXT, finishes TEXT, lead_time TEXT, tag TEXT, price TEXT)")
        db.execSQL("CREATE TABLE services(id INTEGER PRIMARY KEY, name TEXT, description TEXT, active INTEGER)")
        db.execSQL("CREATE TABLE testimonials(id INTEGER PRIMARY KEY, name TEXT, role TEXT, location TEXT, rating INTEGER, review TEXT, project TEXT)")
        db.execSQL("CREATE TABLE faqs(id INTEGER PRIMARY KEY, category TEXT, question TEXT, answer TEXT)")
        db.execSQL("CREATE TABLE branches(id INTEGER PRIMARY KEY, name TEXT, region TEXT, phone TEXT, hours TEXT, notes TEXT)")
        db.execSQL("CREATE TABLE quote_requests(id INTEGER PRIMARY KEY AUTOINCREMENT, quote_code TEXT, first_name TEXT, last_name TEXT, email TEXT, phone TEXT, branch TEXT, service TEXT, message TEXT, product_id TEXT, created_at INTEGER, status TEXT DEFAULT 'Pending', value TEXT)")
        db.execSQL("CREATE TABLE contact_submissions(id INTEGER PRIMARY KEY AUTOINCREMENT, first_name TEXT, last_name TEXT, email TEXT, phone TEXT, branch TEXT, service TEXT, message TEXT, created_at INTEGER)")
        db.execSQL("CREATE TABLE users(id TEXT PRIMARY KEY, full_name TEXT, email TEXT UNIQUE, phone TEXT, password_hash TEXT, role TEXT, branch TEXT, active INTEGER DEFAULT 1, created_at INTEGER)")
        seed(db)
        seedUsers(db)
        seedQuotes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY, full_name TEXT, email TEXT UNIQUE, phone TEXT, password_hash TEXT, role TEXT, branch TEXT, active INTEGER DEFAULT 1, created_at INTEGER)")
            db.execSQL("ALTER TABLE quote_requests ADD COLUMN status TEXT DEFAULT 'Pending'")
            db.execSQL("ALTER TABLE quote_requests ADD COLUMN value TEXT")
            db.execSQL("ALTER TABLE quote_requests ADD COLUMN quote_code TEXT")
            seedUsers(db)
            seedQuotes(db)
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE products_new(id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT, title TEXT, tagline TEXT, description TEXT, image TEXT, gallery TEXT, features TEXT, finishes TEXT, lead_time TEXT, tag TEXT, price TEXT)")
            db.execSQL("INSERT INTO products_new(category,title,tagline,description,image,gallery,features,finishes,lead_time,tag,price) SELECT category,title,tagline,description,image,gallery,features,finishes,lead_time,tag,price FROM products")
            db.execSQL("DROP TABLE products")
            db.execSQL("ALTER TABLE products_new RENAME TO products")
            seed(db)
        }
    }

    private fun seed(db: SQLiteDatabase) {
        product(db, "Kitchen Units", "Modern Kitchen Suite", "Sleek lines. Enduring quality.", "Contemporary kitchen units crafted with premium PG Bison melamine boards. Fully customised to your kitchen dimensions and chosen finishes — from handle-less slab doors to classic shaker profiles. Our installation team handles everything from delivery to final fitting.", "kitchen_12", listOf("kitchen_12", "kitchen_7", "kitchen_10"), listOf("Floor-to-ceiling cabinets", "Soft-close hinges & drawers", "Custom island options", "Integrated appliance housing", "15+ colour finishes"), listOf("Arctic White", "Graphite Matt", "Woodgrain Oak", "Concrete Grey", "Gloss White"), "2–4 weeks", "Popular", "R8,500")
        product(db, "Kitchen Units", "Curved Luxury Kitchen", "Flowing forms, flawless finish.", "Curved kitchen cabinetry that redefines what is possible with PG Bison board materials. CNC precision-cutting allows us to achieve gentle arcs and radius profiles that transform a kitchen into a design centrepiece. Available in any PG Bison melamine colour.", "kitchen_6", listOf("kitchen_6", "kitchen_9", "kitchen_11"), listOf("Curved radius profiles", "Integrated appliance housing", "Marble-effect finishes", "LED under-cabinet lighting", "Bespoke island designs"), listOf("Pearl White", "Warm Linen", "Deep Navy", "Sage Green", "Terrazzo Effect"), "3–5 weeks", null, "R14,000")
        product(db, "Kitchen Units", "Compact Galley Kitchen", "Maximum storage, minimal footprint.", "Purpose-built for narrow or apartment kitchens. Every centimetre is optimised — pull-out pantry columns, slim-line overhead units, and deep base drawers eliminate wasted space without sacrificing style.", "kitchen_4", listOf("kitchen_4", "kitchen_3", "kitchen_2"), listOf("Pull-out pantry columns", "Overhead storage units", "Deep base drawers", "Corner carousel units", "Space-saving design"), listOf("White Gloss", "Light Oak", "Anthracite", "Soft Grey"), "2–3 weeks", null, "R6,200")
        product(db, "TV Stands", "Wall-Mounted TV Unit", "Float it. Flaunt it.", "Floating TV units that create a clean, contemporary look with integrated cable management channels so no wires are ever visible. Specify your TV size and we engineer the unit to match — including the correct wall-mounting substrate.", "tv_1", listOf("tv_1", "tv_2", "tv_4"), listOf("Hidden cable management", "Wall-mounted / floating", "Open + closed storage", "Custom width up to 3m", "LED strip options"), listOf("White Matt", "Smoked Oak", "Charcoal", "Walnut Effect", "Bianco"), "1–2 weeks", "New", "R3,800")
        product(db, "TV Stands", "Entertainment Console", "Storage that works as hard as you play.", "A floor-standing entertainment centre with deep media shelves, adjustable compartments, and a mix of doors and open bays. Designed to house sound equipment, streaming devices, gaming consoles, and décor in one cohesive unit.", "tv_8", listOf("tv_8", "tv_9", "tv_10", "tv_5"), listOf("Deep media shelves", "Adjustable compartments", "Door + open bay combo", "Matching wall panels", "Base plinth or leg options"), listOf("Arctic White", "Teak Effect", "Black Matt", "Light Grey"), "1–2 weeks", null, "R2,900")
        product(db, "Built-In Cupboards", "Full-Length Wardrobe", "Every centimetre, perfectly used.", "Floor-to-ceiling built-in wardrobes that make the most of your bedroom height. Choose sliding or hinged doors, full-length mirrors, internal drawer systems, and a mix of hanging and shelving zones. Installed by our own team with minimal disruption.", "kitchen_12", listOf("kitchen_12", "kitchen_4", "kitchen_6"), listOf("Full-length mirror options", "Internal drawer systems", "Hanging + shelving zones", "Soft-close doors", "Floor-to-ceiling height"), listOf("White", "Sand", "Pewter", "Cashmere", "Stone Grey"), "1–2 weeks", "Popular", "R4,500")
        product(db, "Built-In Cupboards", "Bedroom Suite Storage", "Coordinated. Considered. Complete.", "A fully coordinated bedroom storage solution combining open display shelving, closed cupboards, and drawer pedestals in a unified design. Pair with a matching headboard panel for a truly bespoke bedroom suite.", "kitchen_6", listOf("kitchen_6", "kitchen_12"), listOf("Modular configuration", "Integrated LED lighting", "Bedside pedestals", "Headboard panel option", "Premium handles & ironmongery"), listOf("Linen", "Dove White", "Dusty Rose", "Sage", "Midnight Blue"), "2–3 weeks", null, "R6,800")
        product(db, "Cutting & Edging", "Precision Board Cutting", "Your measurements. Our precision.", "CNC-precision cutting of any PG Bison chipboard, MDF, or melamine board to your exact specifications. Ideal for contractors, cabinet-makers, and advanced DIY builders. Supply your cut-list and we do the rest — often same day.", "kitchen_3", listOf("kitchen_3", "tv_5"), listOf("±0.5mm tolerance", "All PG Bison board ranges", "Same-day turnaround (bulk orders)", "Digital cut-list accepted", "Bulk discount pricing"), listOf("All PG Bison melamine colours", "Raw chipboard", "MDF", "Supawood"), "Same day – 2 days", null, "R18/cut")
        product(db, "Cutting & Edging", "Edge Banding & Finishing", "The detail that defines the finish.", "Professional PVC and ABS edge banding applied with a hot-melt adhesive press and trimmed flush — colour-matched from the full PG Bison ABS edging range. Available on any board thickness from 16mm to 38mm.", "tv_5", listOf("tv_5", "kitchen_3"), listOf("PVC & ABS edging", "Full colour-match range", "Flush trim finish", "16mm–38mm boards", "Bulk pricing available"), listOf("Matched to all PG Bison decors", "Contrasting accent options"), "Same day – 2 days", null, "R8/linear metre")

        service(db, 1, "Kitchen Units", "Custom kitchen cabinetry")
        service(db, 2, "TV Stands", "Wall-mounted and floor-standing units")
        service(db, 3, "Built-In Cupboards", "Floor-to-ceiling wardrobes")
        service(db, 4, "Cutting & Edging", "CNC cutting and edge banding")
        service(db, 5, "General Enquiry", "Anything else")


        faq(db, 1, "Materials", "What is PG Bison?", "PG Bison is a leading South African chipboard and melamine manufacturer. We use their premium ranges for all our cabinetry, offering a vast colour and finish palette plus exceptional durability.")
        faq(db, 2, "Process", "What does your installation service include?", "Our installers handle delivery, assembly, and final installation. We measure twice, cut once, and return your home looking immaculate — no mess left behind.")
        faq(db, 3, "Services", "Do you offer warranties?", "Yes — workmanship warranty for 2 years and PG Bison material warranty as per manufacturer terms. Ask about extended protection plans for high-traffic furniture.")
        faq(db, 4, "Installation", "How long does installation take?", "Simple kitchen units typically 1–2 days. Built-in cupboards or complex layouts 2–4 days. We'll confirm the exact timeline during your consultation.")
        faq(db, 5, "Trade", "Do you offer trade discounts?", "Yes — builders, interior designers, and contractors receive 10–15% off bulk orders. Contact your local branch for pricing.")
        faq(db, 6, "Branches", "Where are your branches located?", "Woodlands has branches in Soweto, Roodepoort, and Randfontein. Each serves their local area and surrounding regions.")
        faq(db, 7, "Warranty", "Can I upgrade or modify my installed furniture?", "Absolutely. Many of our customers add features over time. Bring your original measurements and we'll work with you to expand or adapt your units.")

        branch(db, 1, "Soweto", "Gauteng", "011 234 5678", "Mon–Fri 8am–5pm, Sat 9am–2pm", "Central location serving central Johannesburg")
        branch(db, 2, "Roodepoort", "Gauteng", "011 567 8900", "Mon–Fri 8am–5pm, Sat 9am–2pm", "West location serving Roodepoort and surrounds")
        branch(db, 3, "Randfontein", "Gauteng", "011 789 0123", "Mon–Fri 8am–5pm, Sat 9am–2pm", "East location serving Randfontein and surrounds")
    }

    private fun seedUsers(db: SQLiteDatabase) {
        user(db, "Admin User", "admin@woodlandsdb.co.za", null, "admin123", Roles.ADMIN, null)
        user(db, "Soweto Manager", "soweto@woodlandsdb.co.za", null, "manager123", Roles.MANAGER_SOWETO, "Soweto")
        user(db, "Roodepoort Manager", "roodepoort@woodlandsdb.co.za", null, "manager123", Roles.MANAGER_ROODEPOORT, "Roodepoort")
        user(db, "Randfontein Manager", "randfontein@woodlandsdb.co.za", null, "manager123", Roles.MANAGER_RANDFONTEIN, "Randfontein")
        user(db, "Prototype Customer", "customer@example.com", "071 234 5678", "customer123", Roles.CUSTOMER, null)
    }

    private fun seedQuotes(db: SQLiteDatabase) {
        val c = db.rawQuery("SELECT COUNT(*) FROM quote_requests", null)
        c.moveToFirst()
        val existing = c.getInt(0)
        c.close()
        if (existing > 0) return
        val rows = listOf(
            Septuple("QT-0041", "Thabo", "Mokoena", "071 234 5678", "Soweto", "Kitchen Units", "Completed", "R18 500"),
            Septuple("QT-0040", "Sandra", "Van Wyk", "082 555 1234", "Roodepoort", "Built-In Cupboards", "In Progress", "R9 200"),
            Septuple("QT-0039", "Mpho", "Dlamini", "083 456 7890", "Soweto", "TV Stands", "Pending", "R4 800"),
            Septuple("QT-0038", "Anita", "Joubert", "084 222 9911", "Randfontein", "Cutting & Edging", "Completed", "R1 650"),
            Septuple("QT-0037", "Lebo", "Sithole", "072 333 4455", "Randfontein", "Kitchen Units", "Cancelled", "R22 000"),
            Septuple("QT-0036", "Priya", "Naidoo", "073 888 2200", "Roodepoort", "Built-In Cupboards", "In Progress", "R13 400"),
            Septuple("QT-0035", "Kagiso", "Sithole", "082 111 2222", "Soweto", "TV Stands", "Pending", "R6 100"),
            Septuple("QT-0032", "Nomsa", "Dube", "071 999 8888", "Soweto", "Kitchen Units", "In Progress", "R22 400"),
            Septuple("QT-0029", "Bongani", "Zulu", "060 333 4444", "Soweto", "Built-In Cupboards", "Completed", "R11 200")
        )
        rows.forEachIndexed { i, r ->
            val v = ContentValues().apply {
                put("quote_code", r.code)
                put("first_name", r.first)
                put("last_name", r.last)
                put("email", "${r.first.lowercase()}.${r.last.lowercase()}@example.com")
                put("phone", r.phone)
                put("branch", r.branch)
                put("service", r.service)
                put("message", "Seeded demo quote request for dashboard/reporting screens.")
                put("product_id", null as String?)
                put("created_at", System.currentTimeMillis() - (rows.size - i) * 86_400_000L)
                put("status", r.status)
                put("value", r.value)
            }
            db.insert("quote_requests", null, v)
        }
    }

    private data class Septuple(val code: String, val first: String, val last: String, val phone: String, val branch: String, val service: String, val status: String, val value: String)

    private fun product(db: SQLiteDatabase, category: String, title: String, tagline: String, description: String, image: String, gallery: List<String>, features: List<String>, finishes: List<String>, lead: String, tag: String?, price: String) {
        val v = ContentValues().apply {
            put("category", category)
            put("title", title)
            put("tagline", tagline)
            put("description", description)
            put("image", image)
            put("gallery", gallery.joinToString("|"))
            put("features", features.joinToString("|"))
            put("finishes", finishes.joinToString("|"))
            put("lead_time", lead)
            put("tag", tag)
            put("price", price)
        }
        db.insertWithOnConflict("products", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun service(db: SQLiteDatabase, id: Int, name: String, description: String) {
        db.insertWithOnConflict("services", null, ContentValues().apply { put("id", id); put("name", name); put("description", description); put("active", 1) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun testimonial(db: SQLiteDatabase, id: Int, name: String, role: String, location: String, rating: Int, review: String, project: String) {
        db.insertWithOnConflict("testimonials", null, ContentValues().apply { put("id", id); put("name", name); put("role", role); put("location", location); put("rating", rating); put("review", review); put("project", project) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun faq(db: SQLiteDatabase, id: Int, category: String, question: String, answer: String) {
        db.insertWithOnConflict("faqs", null, ContentValues().apply { put("id", id); put("category", category); put("question", question); put("answer", answer) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun branch(db: SQLiteDatabase, id: Int, name: String, region: String, phone: String, hours: String, notes: String) {
        db.insertWithOnConflict("branches", null, ContentValues().apply { put("id", id); put("name", name); put("region", region); put("phone", phone); put("hours", hours); put("notes", notes) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun user(db: SQLiteDatabase, fullName: String, email: String, phone: String?, password: String, role: String, branch: String?) {
        val v = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("full_name", fullName)
            put("email", email.lowercase())
            put("phone", phone)
            put("password_hash", Security.hash(password))
            put("role", role)
            put("branch", branch)
            put("active", 1)
            put("created_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("users", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }
}
