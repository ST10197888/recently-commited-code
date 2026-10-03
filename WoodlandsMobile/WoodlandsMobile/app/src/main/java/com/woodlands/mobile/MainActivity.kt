package com.woodlands.mobile

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    internal lateinit var db: LocalDb
    internal lateinit var session: Session
    internal lateinit var pageContainer: FrameLayout
    internal lateinit var root: LinearLayout
    internal lateinit var content: LinearLayout
    internal lateinit var bottom: LinearLayout
    internal var currentScreen = "home"
    internal var selectedProduct: Int? = null
    internal var galleryFilter = "All"
    internal var sidebarOpen = false

    // form-in-progress state used by the admin/manager management screens
    internal var editUserId: String? = null
    internal var editProductId: Int = 0
    internal var editTestimonialId: Int? = null
    internal var editFaqId: Int? = null

    internal val blue = Color.rgb(0, 71, 171)
    internal val red = Color.rgb(220, 20, 60)
    internal val navy = Color.rgb(0, 43, 107)
    internal val cream = Color.rgb(247, 245, 240)
    internal val text = Color.rgb(26, 26, 26)
    internal val muted = Color.rgb(107, 104, 96)
    internal val green = Color.rgb(21, 128, 61)
    internal val greenBg = Color.rgb(240, 253, 244)
    internal val lightBlueBg = Color.rgb(224, 234, 250)

    /** Screens that fall "under" the More tab so the bottom nav highlights the right icon. */
    private val moreFamily = setOf(
        "more", "about", "testimonials", "faqs", "contact",
        "login", "register", "profile", "settings",
        "dashboard", "quotes", "users", "userForm",
        "manageProducts", "productForm", "manageTestimonials", "testimonialForm", "manageFaqs", "faqForm"
    )

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = LocalDb(this)
        session = Session(this)
        window.statusBarColor = blue
        pageContainer = FrameLayout(this)
        setContentView(pageContainer)
        showScreen("home")
    }

    override fun onDestroy() { db.close(); super.onDestroy() }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (sidebarOpen) { closeSidebar(); return }
        if (selectedProduct != null) { selectedProduct = null; showScreen("gallery"); return }
        if (currentScreen in setOf("login", "register", "profile", "settings", "dashboard", "quotes", "users", "userForm", "manageProducts", "productForm", "manageTestimonials", "testimonialForm", "manageFaqs", "faqForm")) { showScreen("more"); return }
        if (currentScreen !in listOf("home", "gallery", "quote", "branches", "more")) { showScreen("home"); return }
        if (currentScreen != "home") showScreen("home") else super.onBackPressed()
    }

    internal fun currentUser(): AppUser? = session.userId?.let { db.findUserById(it) }

    internal fun showScreen(screen: String) {
        if (sidebarOpen) closeSidebarImmediate()
        currentScreen = screen
        if (screen in listOf("home", "gallery", "branches", "more", "about", "testimonials", "faqs", "contact")) selectedProduct = null
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cream) }
        pageContainer.removeAllViews()
        pageContainer.addView(root, FrameLayout.LayoutParams(-1, -1))
        root.addView(header())
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        bottom = bottomNav()
        root.addView(bottom)
        when (screen) {
            "home" -> homeScreen()
            "gallery" -> galleryScreen()
            "quote" -> quoteScreen()
            "branches" -> branchesScreen()
            "more" -> moreScreen()
            "about" -> aboutScreen()
            "testimonials" -> testimonialsScreen()
            "faqs" -> faqScreen()
            "contact" -> contactScreen()
            "product" -> if (selectedProduct != null) productScreen(selectedProduct!!) else showScreen("home")
            "login" -> loginScreen()
            "register" -> registerScreen()
            "profile" -> profileScreen()
            "settings" -> settingsScreen()
            "dashboard" -> dashboardScreen()
            "quotes" -> quotesScreen()
            "users" -> usersScreen()
            "userForm" -> userFormScreen()
            "manageProducts" -> manageProductsScreen()
            "productForm" -> productFormScreen()
            "manageTestimonials" -> manageTestimonialsScreen()
            "testimonialForm" -> testimonialFormScreen()
            "manageFaqs" -> manageFaqsScreen()
            "faqForm" -> faqFormScreen()
        }
        bottom.visibility = if (screen in listOf("home", "gallery", "quote", "branches", "more")) View.VISIBLE else View.GONE
    }

    // ---------- chrome: header / sidebar trigger / bottom nav ----------

    private fun header(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(10), dp(12), dp(10)); setBackgroundColor(Color.WHITE); elevation = 3f }
        val menu = TextView(this).apply { text = "☰"; textSize = 22f; setTextColor(blue); gravity = Gravity.CENTER; background = rippleBg(Color.WHITE, Color.TRANSPARENT, 21, 45); isClickable = true; setOnClickListener { openSidebar() } }
        bar.addView(menu, LinearLayout.LayoutParams(dp(42), dp(42)).apply { setMargins(dp(6), 0, 0, 0) })
        val logo = tv("WOODLANDS\nDESIGNER BOARDS", 14, blue).apply { gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); isClickable = true; setOnClickListener { showScreen("home") } }
        bar.addView(logo, LinearLayout.LayoutParams(0, dp(44), 1f))
        val me = currentUser()
        if (me != null) {
            val avatar = TextView(this).apply { text = initialsOf(me.fullName); textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); background = rippleBg(blue, Color.TRANSPARENT, 18, 60); isClickable = true; setOnClickListener { showScreen("profile") } }
            bar.addView(avatar, LinearLayout.LayoutParams(dp(36), dp(36)).apply { setMargins(0, 0, dp(8), 0) })
        }
        val quote = button("Quote", red, Color.WHITE).apply { setOnClickListener { showScreen("quote") } }
        bar.addView(quote, LinearLayout.LayoutParams(dp(82), dp(42)))
        return bar
    }

    private fun bottomNav(): LinearLayout {
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE); elevation = 8f }
        val items = listOf("⌂" to "Home", "▦" to "Gallery", "✎" to "Quote", "⌖" to "Branches", "☰" to "More")
        items.forEach { (icon, label) ->
            val isActive = active(label)
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6)) }
            val pill = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(14), dp(4), dp(14), dp(3)) }
            if (isActive) pill.background = bg(lightBlueBg, Color.TRANSPARENT, 14)
            pill.addView(tv(icon, 19, if (isActive) blue else muted).apply { gravity = Gravity.CENTER })
            pill.addView(tv(label, 10, if (isActive) blue else muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0); if (isActive) setTypeface(typeface, Typeface.BOLD) })
            cell.addView(pill)
            cell.background = rippleBg(Color.WHITE, Color.TRANSPARENT, 0, 35)
            cell.isClickable = true
            cell.setOnClickListener { when (label) { "Home" -> showScreen("home"); "Gallery" -> showScreen("gallery"); "Quote" -> showScreen("quote"); "Branches" -> showScreen("branches"); else -> showScreen("more") } }
            nav.addView(cell, LinearLayout.LayoutParams(0, dp(60), 1f))
        }
        return nav
    }
    private fun active(label: String) = when (label) { "Home" -> currentScreen == "home"; "Gallery" -> currentScreen == "gallery" || currentScreen == "product"; "Quote" -> currentScreen == "quote"; "Branches" -> currentScreen == "branches"; else -> currentScreen in moreFamily }

    // ---------- home ----------

    private fun homeScreen() {
        hero()
        sectionTitle("Explore our work", "Custom-built units and precision board services")
        val cats = listOf("Kitchen Units", "TV Stands", "Built-In Cupboards", "Cutting & Edging")
        val catImages = listOf(R.drawable.kitchen_12, R.drawable.tv_1, R.drawable.kitchen_6, R.drawable.kitchen_3)
        val catRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cats.forEachIndexed { i, c ->
            val cardV = card().apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { galleryFilter = c; showScreen("gallery") } }
            val img = ImageView(this).apply { setImageResource(catImages[i]); scaleType = ImageView.ScaleType.CENTER_CROP }
            cardV.addView(img, LinearLayout.LayoutParams(dp(94), dp(78)))
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0); gravity = Gravity.CENTER_VERTICAL }
            box.addView(tv(c, 16, blue).apply { setTypeface(typeface, Typeface.BOLD) })
            box.addView(tv(when (i) { 0 -> "Custom kitchens crafted in PG Bison melamine"; 1 -> "Wall-mounted and floor-standing entertainment units"; 2 -> "Floor-to-ceiling fitted wardrobes and storage"; else -> "CNC precision cutting and edge banding services" }, 12, muted))
            cardV.addView(box, LinearLayout.LayoutParams(0, dp(78), 1f)); catRow.addView(cardV, marginParams(12, 6, 12, 6))
        }
        content.addView(catRow)
        sectionTitle("Featured", "Popular and new products")
        productList(db.loadProducts().filter { it.tag == "Popular" || it.tag == "New" }.take(4))
        cta("Need something custom?", "All our products can be tailored to your space.", "Request a Quote") { showScreen("quote") }
        footerNote()
    }

    private fun hero() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)); setBackgroundColor(blue) }
        val image = ImageView(this).apply { setImageResource(R.drawable.kitchen_12); scaleType = ImageView.ScaleType.CENTER_CROP }
        box.addView(image, LinearLayout.LayoutParams(-1, dp(190)))
        box.addView(tv("BUILT TO LAST.", 27, Color.WHITE).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(16), 0, 0) })
        box.addView(tv("Designed to Impress.", 22, red).apply { setTypeface(typeface, Typeface.BOLD) })
        box.addView(tv("Premium custom-built kitchen units, TV stands & built-in cupboards. PG Bison certified.", 13, Color.WHITE).apply { setPadding(0, dp(8), 0, dp(14)) })
        box.addView(button("Get a Free Quote", red, Color.WHITE).apply { setOnClickListener { showScreen("quote") } })
        content.addView(box)
    }

    // ---------- gallery / product / quote / branches ----------

    private fun galleryScreen() {
        pageIntro("Services & Gallery", "Browse the catalogue and filter products by category.")
        val filters = horizontalChips(listOf("All", "Kitchen Units", "TV Stands", "Built-In Cupboards", "Cutting & Edging"), galleryFilter) { galleryFilter = it; showScreen("gallery") }
        content.addView(filters)
        val list = if (galleryFilter == "All") db.loadProducts() else db.loadProducts().filter { it.category == galleryFilter }
        content.addView(tv("Showing ${list.size} product${if (list.size == 1) "" else "s"}", 12, muted).apply { setPadding(dp(16), dp(8), dp(16), dp(8)) })
        productList(list)
        cta("Don't see exactly what you need?", "All our products are fully custom.", "Request a Custom Quote") { showScreen("quote") }
    }

    private fun productScreen(id: Int) {
        val p = db.loadProducts().firstOrNull { it.id == id } ?: run { showScreen("gallery"); return }
        pageIntro(p.title, p.category)
        val img = ImageView(this).apply { setImageResource(imageRes(p.image)); scaleType = ImageView.ScaleType.CENTER_CROP }
        content.addView(img, sizeMarginParams(-1, dp(220), 12, 0, 12, 0))
        content.addView(tv(p.tagline, 16, red).apply { setTypeface(typeface, Typeface.BOLD); setPadding(dp(16), dp(14), dp(16), dp(4)) })
        content.addView(tv(p.description, 13, muted).apply { setPadding(dp(16), 0, dp(16), dp(12)) })
        infoRow("Price", p.price); infoRow("Lead time", p.lead)
        sectionTitle("Features", "")
        p.features.forEach { bullet(it) }
        sectionTitle("Finish options", "")
        val finish = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), 0, dp(16), dp(8)) }
        p.finishes.forEach { finish.addView(chip(it), marginParams(0, 0, 6, 0)) }
        val finishScroll = HorizontalScrollView(this).apply { addView(finish) }; content.addView(finishScroll)
        cta("Interested in this product?", "Start a quote with the product pre-selected.", "Request Quote for ${p.title}") { selectedProduct = p.id; showScreen("quote") }
        sectionTitle("Related products", "")
        productList(db.loadProducts().filter { it.category == p.category && it.id != p.id }.take(3))
    }

    private fun quoteScreen() {
        pageIntro("Request a Free Quote", "Tell us about your project and we'll take it from there.")
        val me = currentUser()
        val selected = selectedProduct?.let { id -> db.loadProducts().firstOrNull { it.id == id } }
        if (selected != null) { val cardV = card().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }; cardV.addView(tv("Product selected", 11, red)); cardV.addView(tv(selected.title, 16, blue).apply { setTypeface(typeface, Typeface.BOLD) }); content.addView(cardV, marginParams(16, 0, 16, 12)) }
        val nameParts = me?.fullName?.trim()?.split(" ", limit = 2)
        val first = field("First Name", "e.g. Thabo").apply { if (nameParts != null) setText(nameParts.getOrNull(0).orEmpty()) }
        val last = field("Last Name", "e.g. Mokoena").apply { if (nameParts != null) setText(nameParts.getOrNull(1).orEmpty()) }
        val email = field("Email Address", "you@example.com").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; if (me != null) setText(me.email) }
        val phone = field("Phone Number", "071 234 5678", required = false).apply { if (me?.phone != null) setText(me.phone) }
        val branch = spinnerField("Nearest Branch", db.loadBranches().map { it.name })
        val service = spinnerField("Service Required", listOf("Kitchen Units", "TV Stands", "Built-In Cupboards", "Cutting & Edging", "General Enquiry"))
        val msg = field("Project Description", "Describe your project, measurements, finish preferences, etc.", true)
        val privacy = CheckBox(this).apply { text = "I have read the privacy notice. This prototype saves quote details on this device."; setTextColor(Color.rgb(26, 26, 26)) }
        listOf(first, last, email, phone, branch, service, msg).forEach { content.addView(it, marginParams(16, 6, 16, 6)) }
        content.addView(outlineButton("Read privacy notice", blue).apply { setOnClickListener { showPrivacyNotice() } }, marginParams(16, 6, 16, 4))
        content.addView(privacy, marginParams(16, 6, 16, 6))
        content.addView(button("Submit Request", red, Color.WHITE).apply {
            setOnClickListener {
                if (first.text.isNullOrBlank() || last.text.isNullOrBlank() || email.text.isNullOrBlank() || msg.text.isNullOrBlank()) { toast("Please complete all required fields"); return@setOnClickListener }
                if (!privacy.isChecked) { toast("Please read and acknowledge the privacy notice"); return@setOnClickListener }
                db.submitQuote(first.text.toString(), last.text.toString(), email.text.toString(), phone.text.toString(), spinnerValue(branch), spinnerValue(service), msg.text.toString(), selected?.id?.toString())
                selectedProduct = null; confirmation("Quote request received", "Thanks ${first.text}. Your request has been saved locally on this device and will show up under My Quotes.")
            }
        }, marginParams(16, 12, 16, 20))
    }

    internal fun showPrivacyNotice() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Privacy notice")
            .setMessage("This prototype stores account, quote and contact details in the app's private database on this device. Requests are not sent to the website by this mobile app. Quote and contact forms use your name, email, branch, service and message; phone is optional. To delete your account and quote records on this device, use Profile > Delete my local account and quote data. For access, correction or deletion requests about the website or other systems, email info@woodlandsdb.co.za. That address must be confirmed by the business before launch.")
            .setPositiveButton("Close", null)
            .show()
    }

    private fun branchesScreen() {
        pageIntro("Our Branches", "Find the branch closest to your project.")
        db.loadBranches().forEach { b ->
            val cardV = card().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)) }
            cardV.addView(tv("⌖  ${b.name} Branch", 18, blue).apply { setTypeface(typeface, Typeface.BOLD) })
            cardV.addView(tv(b.notes, 12, muted).apply { setPadding(0, dp(4), 0, dp(4)) })
            cardV.addView(tv("${b.region} · ${b.hours}", 12, muted))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
            actions.addView(button("Call", blue, Color.WHITE).apply { setOnClickListener { toast("Branch phone: ${b.phone}") } }, marginParams(0, 0, 8, 0))
            actions.addView(button("Open Maps", red, Color.WHITE).apply { setOnClickListener { openMaps("${b.name} Branch, Gauteng") } }, marginParams(0, 0, 8, 0))
            actions.addView(button("Quote", blue, Color.WHITE).apply { setOnClickListener { showScreen("quote") } })
            cardV.addView(actions); content.addView(cardV, marginParams(12, 6, 12, 6))
        }
        content.addView(tv("The current prototype intentionally keeps the branch phone number as 011 XXX XXXX, matching the website seed data.", 11, muted).apply { setPadding(dp(16), dp(10), dp(16), dp(20)) })
    }

    // ---------- more (account hub) ----------

    private fun moreScreen() {
        pageIntro("More", "Your account and information")
        val me = currentUser()
        if (me == null) {
            sectionTitle("Account", "Log in for order history and, for staff accounts, dashboard tools")
            content.addView(button("Login", blue, Color.WHITE).apply { setOnClickListener { showScreen("login") } }, marginParams(16, 6, 16, 6))
            content.addView(outlineButton("Register", blue).apply { setOnClickListener { showScreen("register") } }, marginParams(16, 0, 16, 12))
            sectionTitle("Quick login (prototype)", "Seeded demo accounts — same credentials as the website")
            quickLoginRow("Admin", "admin@woodlandsdb.co.za", "admin123", "Full system access")
            quickLoginRow("Soweto Manager", "soweto@woodlandsdb.co.za", "manager123", "Soweto branch")
            quickLoginRow("Roodepoort Manager", "roodepoort@woodlandsdb.co.za", "manager123", "Roodepoort branch")
            quickLoginRow("Randfontein Manager", "randfontein@woodlandsdb.co.za", "manager123", "Randfontein branch")
            quickLoginRow("Customer", "customer@example.com", "customer123", "Browsing & quotes only")
        } else {
            val cardV = card().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = initialsOf(me.fullName); textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); background = bg(blue, Color.TRANSPARENT, 22) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            info.addView(tv(me.fullName, 16, blue).apply { setTypeface(typeface, Typeface.BOLD) })
            info.addView(tv(Roles.label(me.role), 12, muted))
            row.addView(info)
            cardV.addView(row)
            content.addView(cardV, marginParams(16, 6, 16, 10))
            if (Roles.isStaff(me.role)) content.addView(button("Dashboard", blue, Color.WHITE).apply { setOnClickListener { showScreen("dashboard") } }, marginParams(16, 6, 16, 6))
            content.addView(outlineButton("My Quotes", blue).apply { setOnClickListener { showScreen("quotes") } }, marginParams(16, 0, 16, 6))
            content.addView(outlineButton("Profile", blue).apply { setOnClickListener { showScreen("profile") } }, marginParams(16, 0, 16, 6))
            content.addView(outlineButton("Settings", blue).apply { setOnClickListener { showScreen("settings") } }, marginParams(16, 0, 16, 6))
            content.addView(outlineButton("Logout", red).apply { setOnClickListener { session.clear(); toast("Signed out"); showScreen("home") } }, marginParams(16, 0, 16, 12))
        }
        sectionTitle("Information", "")
        listOf("About Us" to "Learn about Woodlands Designer Boards", "Testimonials" to "Read customer feedback", "FAQs" to "Answers about products and services", "Contact Us" to "Send a message or start a quote").forEach { (title, sub) ->
            val c = card().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); setOnClickListener { when (title) { "About Us" -> showScreen("about"); "Testimonials" -> showScreen("testimonials"); "FAQs" -> showScreen("faqs"); else -> showScreen("contact") } } }
            c.addView(tv(title, 17, blue).apply { setTypeface(typeface, Typeface.BOLD) }); c.addView(tv(sub, 12, muted).apply { setPadding(0, dp(4), 0, 0) }); content.addView(c, marginParams(16, 6, 16, 6))
        }
        footerNote()
    }

    private fun quickLoginRow(label: String, email: String, password: String, sub: String) {
        val row = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(tv(label, 14, blue).apply { setTypeface(typeface, Typeface.BOLD) })
        info.addView(tv(sub, 11, muted))
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Use", blue, Color.WHITE).apply { setOnClickListener { quickLogin(email, password) } }, LinearLayout.LayoutParams(dp(66), dp(38)))
        content.addView(row, marginParams(16, 4, 16, 4))
    }

    internal fun quickLogin(email: String, password: String) {
        val u = db.verifyCredentials(email, password)
        if (u == null) { toast("That demo account could not be found."); return }
        signIn(u)
    }

    internal fun signIn(user: AppUser) {
        session.userId = user.id
        toast("Signed in as ${user.fullName}")
        showScreen(if (Roles.isStaff(user.role)) "dashboard" else "home")
    }

    // ---------- about / testimonials / faqs / contact ----------

    private fun aboutScreen() {
        pageIntro("About Woodlands Designer Boards", "Custom cabinetry for homes, designers and contractors across Gauteng.")
        image(R.drawable.kitchen_12)
        sectionTitle("Our Story", "")
        para("Woodlands Designer Boards was founded with a single mission: to make high-quality, custom-built wooden furniture and cabinetry accessible to every South African household. Starting from a single workshop in Soweto, we've grown to three branches serving clients across the West Rand and Johannesburg South.")
        para("Every product we build uses authentic PG Bison board materials — chipboard, MDF, melamine, and Supawood. Our team works with you from initial site measurement through final installation.")
        sectionTitle("Why Choose Us", "")
        listOf("🏆 PG Bison materials" to "Board and finish options for custom cabinetry.", "🔧 CNC cutting" to "Precision cutting and edge banding services.", "👥 Project types" to "Options for homes, designers and contractors.", "📍 Gauteng service" to "Soweto, Roodepoort and Randfontein areas.").forEach { (a, b) -> featureCard(a, b) }
        cta("Ready to start?", "Let's build something around your space.", "Contact Us") { showScreen("contact") }
    }

    private fun testimonialsScreen() {
        pageIntro("Client feedback", "")
        content.addView(tv("There are no verified client testimonials published yet.", 14, text).apply { setPadding(dp(16), dp(10), dp(16), dp(20)) })
        cta("Have a project in mind?", "Send a quote request to get started.", "Request a Quote") { showScreen("quote") }
    }

    private fun faqScreen() {
        pageIntro("Frequently Asked Questions", "Filter questions by category and tap to expand an answer.")
        var filter = "All"
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chips = horizontalChips(listOf("All", "Materials", "Process", "Services", "Installation", "Trade", "Branches", "Warranty"), filter) { new -> filter = new; container.removeAllViews(); renderFaqs(container, new) }
        content.addView(chips); content.addView(container); renderFaqs(container, filter)
        cta("Still have questions?", "Drop us a message.", "Contact Us") { showScreen("contact") }
    }
    private fun renderFaqs(container: LinearLayout, filter: String) {
        val list = db.loadFaqs().filter { filter == "All" || it.category == filter }; list.forEach { f ->
            val c = card().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
            val q = tv("▸ ${f.question}", 14, blue).apply { setTypeface(typeface, Typeface.BOLD) }; val a = tv(f.answer, 12, muted).apply { visibility = View.GONE; setPadding(dp(4), dp(10), dp(4), 0) }
            c.isClickable = true
            c.setOnClickListener { a.visibility = if (a.visibility == View.VISIBLE) View.GONE else View.VISIBLE; q.text = if (a.visibility == View.VISIBLE) "▾ ${f.question}" else "▸ ${f.question}" }
            c.addView(q); c.addView(tv(f.category, 10, red)); c.addView(a); container.addView(c, marginParams(12, 4, 12, 4))
        }
    }

    private fun contactScreen() {
        pageIntro("Contact Us", "Send a message to Woodlands Designer Boards.")
        sectionTitle("Branch contacts", "Our current prototype has three branches")
        db.loadBranches().forEach { b -> content.addView(tv("${b.name}: ${b.phone} · ${b.hours}", 12, muted).apply { setPadding(dp(16), dp(3), dp(16), dp(3)) }) }
        sectionTitle("Message", "We'll use the same branch and service options as the quote flow.")
        val me = currentUser()
        val nameParts = me?.fullName?.trim()?.split(" ", limit = 2)
        val first = field("First Name", "e.g. Thabo").apply { if (nameParts != null) setText(nameParts.getOrNull(0).orEmpty()) }
        val last = field("Last Name", "e.g. Mokoena").apply { if (nameParts != null) setText(nameParts.getOrNull(1).orEmpty()) }
        val email = field("Email Address", "you@example.com").apply { if (me != null) setText(me.email) }
        val phone = field("Phone Number", "071 234 5678", required = false).apply { if (me?.phone != null) setText(me.phone) }
        val branch = spinnerField("Nearest Branch", db.loadBranches().map { it.name })
        val service = spinnerField("Service Required", listOf("Kitchen Units", "TV Stands", "Built-In Cupboards", "Cutting & Edging", "General Enquiry"))
        val msg = field("Message", "How can we help?", true)
        val privacy = CheckBox(this).apply { text = "I have read the privacy notice. This prototype saves message details on this device."; setTextColor(Color.rgb(26, 26, 26)) }
        listOf(first, last, email, phone, branch, service, msg).forEach { content.addView(it, marginParams(16, 6, 16, 6)) }
        content.addView(outlineButton("Read privacy notice", blue).apply { setOnClickListener { showPrivacyNotice() } }, marginParams(16, 6, 16, 4))
        content.addView(privacy, marginParams(16, 6, 16, 6))
        content.addView(button("Send Message", red, Color.WHITE).apply {
            setOnClickListener {
                if (first.text.isNullOrBlank() || last.text.isNullOrBlank() || email.text.isNullOrBlank() || msg.text.isNullOrBlank()) { toast("Please complete all required fields"); return@setOnClickListener }
                if (!privacy.isChecked) { toast("Please read and acknowledge the privacy notice"); return@setOnClickListener }
                db.writableDatabase.execSQL("INSERT INTO contact_submissions(first_name,last_name,email,phone,branch,service,message,created_at) VALUES(?,?,?,?,?,?,?,?)", arrayOf<Any?>(first.text.toString(), last.text.toString(), email.text.toString(), phone.text.toString(), spinnerValue(branch), spinnerValue(service), msg.text.toString(), System.currentTimeMillis()))
                confirmation("Message saved", "Your message has been saved locally in this prototype.")
            }
        }, marginParams(16, 12, 16, 20))
    }

    // ---------- shared UI building blocks ----------

    private fun productList(products: List<Product>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        products.forEach { p ->
            val c = card().apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { selectedProduct = p.id; showScreen("product") } }
            val im = ImageView(this).apply { setImageResource(imageRes(p.image)); scaleType = ImageView.ScaleType.CENTER_CROP }; c.addView(im, LinearLayout.LayoutParams(dp(112), dp(104)))
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }; info.addView(tv(p.category.uppercase(), 10, red)); info.addView(tv(p.title, 15, blue).apply { setTypeface(typeface, Typeface.BOLD) }); info.addView(tv(p.tagline, 11, muted)); info.addView(tv(p.price, 13, blue).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(5), 0, 0) }); c.addView(info, LinearLayout.LayoutParams(0, dp(104), 1f)); row.addView(c, marginParams(12, 6, 12, 6))
        }; content.addView(row)
    }

    internal fun testimonialCard(t: Testimonial) { val c = card().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)) }; c.addView(tv("★★★★★".replaceRange(t.rating, 5, ""), 16, red)); c.addView(tv("\"${t.review}\"", 13, muted).apply { setPadding(0, dp(7), 0, dp(9)) }); c.addView(tv(t.name, 13, blue).apply { setTypeface(typeface, Typeface.BOLD) }); c.addView(tv("${t.role} · ${t.location}", 11, muted)); c.addView(chip(t.project)); content.addView(c, marginParams(12, 5, 12, 5)) }
    internal fun featureCard(title: String, desc: String) { val c = card().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)) }; c.addView(tv(title, 15, blue).apply { setTypeface(typeface, Typeface.BOLD) }); c.addView(tv(desc, 12, muted).apply { setPadding(0, dp(4), 0, 0) }); content.addView(c, marginParams(12, 5, 12, 5)) }
    internal fun statsStrip() { val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(10), dp(8), dp(10)) }; listOf("15+" to "Years", "500+" to "Projects", "3" to "Branches", "100%" to "PG Bison").forEach { (a, b) -> val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }; c.addView(tv(a, 20, red).apply { setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER }); c.addView(tv(b, 10, muted).apply { gravity = Gravity.CENTER }); row.addView(c, LinearLayout.LayoutParams(0, dp(70), 1f)) }; content.addView(row) }
    internal fun sectionTitle(title: String, sub: String) { val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(8)) }; box.addView(tv(title, 20, blue).apply { setTypeface(typeface, Typeface.BOLD) }); if (sub.isNotBlank()) box.addView(tv(sub, 12, muted).apply { setPadding(0, dp(3), 0, 0) }); content.addView(box) }
    internal fun pageIntro(title: String, sub: String) { val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(18)); setBackgroundColor(blue) }; b.addView(tv(title, 25, Color.WHITE).apply { setTypeface(typeface, Typeface.BOLD) }); b.addView(tv(sub, 12, Color.WHITE).apply { setPadding(0, dp(5), 0, 0) }); content.addView(b) }
    internal fun para(s: String) { content.addView(tv(s, 13, muted).apply { setPadding(dp(16), dp(4), dp(16), dp(7)) }) }
    internal fun bullet(s: String) { content.addView(tv("✓  $s", 13, muted).apply { setPadding(dp(20), dp(4), dp(16), dp(4)) }) }
    internal fun infoRow(a: String, b: String) { val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(4), dp(16), dp(4)) }; row.addView(tv(a, 12, muted), LinearLayout.LayoutParams(0, dp(28), 1f)); row.addView(tv(b, 13, blue).apply { setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END }, LinearLayout.LayoutParams(0, dp(28), 1f)); content.addView(row) }
    internal fun cta(title: String, sub: String, label: String, onClick: () -> Unit) { val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)); setBackgroundColor(blue) }; c.addView(tv(title, 19, Color.WHITE).apply { setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER }); c.addView(tv(sub, 12, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10)) }); c.addView(button(label, red, Color.WHITE).apply { setOnClickListener { onClick() } }); content.addView(c, marginParams(12, 16, 12, 16)) }
    internal fun footerNote() { content.addView(tv("WOODLANDS DESIGNER BOARDS\nPremium custom-built units using PG Bison materials.\nSoweto · Roodepoort · Randfontein", 11, muted).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(16), dp(16), dp(24)) }) }
    internal fun image(res: Int) { content.addView(ImageView(this).apply { setImageResource(res); scaleType = ImageView.ScaleType.CENTER_CROP }, sizeMarginParams(-1, dp(180), 16, 10, 16, 10)) }

    internal fun horizontalChips(items: List<String>, selected: String, onPick: (String) -> Unit): HorizontalScrollView { val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(5), dp(12), dp(8)) }; items.forEach { i -> row.addView(chip(i, i == selected).apply { setOnClickListener { onPick(i) } }, marginParams(0, 0, 7, 0)) }; return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) } }
    internal fun chip(label: String, selected: Boolean = true): TextView = tv(label, 11, if (selected) Color.WHITE else blue).apply { setPadding(dp(12), dp(7), dp(12), dp(7)); background = rippleBg(if (selected) blue else Color.WHITE, if (selected) blue else Color.LTGRAY, 18, if (selected) 70 else 35) }
    internal fun chip(label: String): TextView = chip(label, false)
    internal fun card(): LinearLayout = LinearLayout(this).apply { background = rippleBg(Color.WHITE, Color.rgb(225, 230, 238), 12, 35); elevation = 2f }
    internal fun button(label: String, bgColor: Int, fg: Int): Button = Button(this).apply { text = label; textSize = 12f; setTextColor(fg); setAllCaps(false); background = rippleBg(bgColor, bgColor, 10, 100); minHeight = 0; minimumHeight = 0; stateListAnimator = null }
    internal fun outlineButton(label: String, color: Int): Button = Button(this).apply { text = label; textSize = 12f; setTextColor(color); setAllCaps(false); background = rippleBg(Color.WHITE, color, 10, 40); minHeight = 0; minimumHeight = 0; stateListAnimator = null }
    internal fun tv(s: String, size: Float, color: Int) = TextView(this).apply { text = s; textSize = size; setTextColor(color); includeFontPadding = true }
    internal fun tv(s: String, size: Int, color: Int) = tv(s, size.toFloat(), color)
    internal fun field(label: String, hint: String, multi: Boolean = false, required: Boolean = true): EditText = EditText(this).apply { this.hint = if (required) "$label *" else "$label (optional)"; textSize = 13f; setTextColor(this@MainActivity.text); setHintTextColor(muted); background = bg(Color.WHITE, Color.rgb(205, 212, 222), 8); setPadding(dp(12), dp(9), dp(12), dp(9)); if (multi) { minLines = 4; gravity = Gravity.TOP; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE } else { inputType = InputType.TYPE_CLASS_TEXT } }
    internal fun passwordField(label: String): EditText = field(label, label).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
    internal fun spinnerField(label: String, items: List<String>, preselect: String? = null): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(tv("$label *", 11, blue).apply { setPadding(dp(2), 0, dp(2), dp(4)) })
        val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items); tag = "spinner:$label"; background = bg(Color.WHITE, Color.rgb(205, 212, 222), 8); if (preselect != null) { val idx = items.indexOf(preselect); if (idx >= 0) setSelection(idx) } }
        box.addView(spinner, LinearLayout.LayoutParams(-1, dp(48)))
        return box
    }
    internal fun spinnerValue(container: ViewGroup): String { val s = container.getChildAt(1) as Spinner; return s.selectedItem?.toString().orEmpty() }
    internal fun confirmation(title: String, msg: String) { content.removeAllViews(); pageIntro(title, msg); content.addView(button("Back to Home", red, Color.WHITE).apply { setOnClickListener { showScreen("home") } }, marginParams(16, 20, 16, 10)); content.addView(button("Browse Gallery", blue, Color.WHITE).apply { setOnClickListener { showScreen("gallery") } }, marginParams(16, 6, 16, 10)); bottom.visibility = View.GONE }
    internal fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    internal fun openMaps(query: String) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)))) }
    internal fun marginParams(l: Int, t: Int, r: Int, b: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.setMargins(dp(l), dp(t), dp(r), dp(b)) }
    internal fun sizeMarginParams(width: Int, height: Int, l: Int, t: Int, r: Int, b: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(if (width < 0) -1 else dp(width), height).also { it.setMargins(dp(l), dp(t), dp(r), dp(b)) }
    internal fun bg(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply { setColor(fill); if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke); cornerRadius = dp(radius).toFloat() }

    /** A pressable fill that visibly darkens while touched — the app-wide "button feels tappable" treatment. */
    internal fun rippleBg(fill: Int, stroke: Int, radius: Int, rippleAlpha: Int = 60): Drawable {
        val base = GradientDrawable().apply { setColor(fill); if (stroke != Color.TRANSPARENT && stroke != fill) setStroke(dp(1), stroke); cornerRadius = dp(radius).toFloat() }
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(radius).toFloat() }
        val overlay = if (isLightColor(fill)) Color.argb(rippleAlpha, 0, 0, 0) else Color.argb((rippleAlpha * 1.3f).toInt().coerceAtMost(160), 0, 0, 0)
        return RippleDrawable(ColorStateList.valueOf(overlay), base, mask)
    }
    private fun isLightColor(color: Int): Boolean { val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255; return luminance > 0.55 }
    internal fun darkenColor(color: Int, factor: Float): Int { val r = (Color.red(color) * factor).toInt().coerceIn(0, 255); val g = (Color.green(color) * factor).toInt().coerceIn(0, 255); val bl = (Color.blue(color) * factor).toInt().coerceIn(0, 255); return Color.argb(Color.alpha(color), r, g, bl) }
    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    internal fun initialsOf(name: String): String { val parts = name.trim().split(" ").filter { it.isNotBlank() }; return if (parts.isEmpty()) "U" else parts.take(2).joinToString("") { it.first().uppercase() } }

    internal fun imageRes(key: String): Int = when (key) { "kitchen_12" -> R.drawable.kitchen_12; "kitchen_7" -> R.drawable.kitchen_7; "kitchen_10" -> R.drawable.kitchen_10; "kitchen_6" -> R.drawable.kitchen_6; "kitchen_9" -> R.drawable.kitchen_9; "kitchen_11" -> R.drawable.kitchen_11; "kitchen_4" -> R.drawable.kitchen_4; "kitchen_3" -> R.drawable.kitchen_3; "kitchen_2" -> R.drawable.kitchen_2; "tv_1" -> R.drawable.tv_1; "tv_2" -> R.drawable.tv_2; "tv_4" -> R.drawable.tv_4; "tv_8" -> R.drawable.tv_8; "tv_9" -> R.drawable.tv_9; "tv_10" -> R.drawable.tv_10; "tv_5" -> R.drawable.tv_5; else -> R.drawable.kitchen_12 }
    internal val productImageKeys = listOf("kitchen_12", "kitchen_7", "kitchen_10", "kitchen_6", "kitchen_9", "kitchen_11", "kitchen_4", "kitchen_3", "kitchen_2", "tv_1", "tv_2", "tv_4", "tv_8", "tv_9", "tv_10", "tv_5")
}
