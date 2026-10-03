package com.woodlands.mobile

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

internal fun LocalDb.loadProducts(): List<Product> {
    val c = readableDatabase.query("products", null, null, null, null, null, "category,title")
    val out = mutableListOf<Product>()
    while (c.moveToNext()) out += Product(
        c.getInt(c.getColumnIndexOrThrow("id")),
        c.getString(c.getColumnIndexOrThrow("category")),
        c.getString(c.getColumnIndexOrThrow("title")),
        c.getString(c.getColumnIndexOrThrow("tagline")),
        c.getString(c.getColumnIndexOrThrow("description")),
        c.getString(c.getColumnIndexOrThrow("image")),
        c.getString(c.getColumnIndexOrThrow("gallery")).split("|").filter { it.isNotBlank() },
        c.getString(c.getColumnIndexOrThrow("features")).split("|").filter { it.isNotBlank() },
        c.getString(c.getColumnIndexOrThrow("finishes")).split("|").filter { it.isNotBlank() },
        c.getString(c.getColumnIndexOrThrow("lead_time")),
        c.getString(c.getColumnIndexOrThrow("tag")),
        c.getString(c.getColumnIndexOrThrow("price"))
    )
    c.close()
    return out
}

internal fun LocalDb.loadTestimonials(): List<Testimonial> {
    val c = readableDatabase.query("testimonials", null, null, null, null, null, "id")
    val out = mutableListOf<Testimonial>()
    while (c.moveToNext()) out += Testimonial(c.getInt(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("name")), c.getString(c.getColumnIndexOrThrow("role")), c.getString(c.getColumnIndexOrThrow("location")), c.getInt(c.getColumnIndexOrThrow("rating")), c.getString(c.getColumnIndexOrThrow("review")), c.getString(c.getColumnIndexOrThrow("project")))
    c.close()
    return out
}

internal fun LocalDb.loadFaqs(): List<Faq> {
    val c = readableDatabase.query("faqs", null, null, null, null, null, "id")
    val out = mutableListOf<Faq>()
    while (c.moveToNext()) out += Faq(c.getInt(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("category")), c.getString(c.getColumnIndexOrThrow("question")), c.getString(c.getColumnIndexOrThrow("answer")))
    c.close()
    return out
}

internal fun LocalDb.loadBranches(): List<Branch> {
    val c = readableDatabase.query("branches", null, null, null, null, null, "id")
    val out = mutableListOf<Branch>()
    while (c.moveToNext()) out += Branch(c.getInt(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("name")), c.getString(c.getColumnIndexOrThrow("region")), c.getString(c.getColumnIndexOrThrow("phone")), c.getString(c.getColumnIndexOrThrow("hours")), c.getString(c.getColumnIndexOrThrow("notes")))
    c.close()
    return out
}

private fun android.database.Cursor.toUser(): AppUser = AppUser(
    getString(getColumnIndexOrThrow("id")), getString(getColumnIndexOrThrow("full_name")), getString(getColumnIndexOrThrow("email")),
    getString(getColumnIndexOrThrow("phone")), getString(getColumnIndexOrThrow("role")), getString(getColumnIndexOrThrow("branch")),
    getInt(getColumnIndexOrThrow("active")) == 1, getLong(getColumnIndexOrThrow("created_at"))
)

internal fun LocalDb.loadUsers(): List<AppUser> {
    val c = readableDatabase.query("users", null, null, null, null, null, "full_name")
    val out = mutableListOf<AppUser>()
    while (c.moveToNext()) out += c.toUser()
    c.close()
    return out
}

internal fun LocalDb.findUserByEmail(email: String): AppUser? {
    val c = readableDatabase.query("users", null, "lower(email)=?", arrayOf(email.trim().lowercase()), null, null, null)
    val out = if (c.moveToFirst()) c.toUser() else null
    c.close()
    return out
}

internal fun LocalDb.findUserById(id: String): AppUser? {
    val c = readableDatabase.query("users", null, "id=?", arrayOf(id), null, null, null)
    val out = if (c.moveToFirst()) c.toUser() else null
    c.close()
    return out
}

internal fun LocalDb.verifyCredentials(email: String, password: String): AppUser? {
    val c = readableDatabase.query("users", null, "lower(email)=?", arrayOf(email.trim().lowercase()), null, null, null)
    if (!c.moveToFirst()) { c.close(); return null }
    val hash = c.getString(c.getColumnIndexOrThrow("password_hash"))
    val active = c.getInt(c.getColumnIndexOrThrow("active")) == 1
    val user = c.toUser()
    c.close()
    if (!active || !Security.matches(password, hash)) return null
    return user
}

internal fun LocalDb.createUser(fullName: String, email: String, phone: String?, password: String, role: String, branch: String?): String? {
    if (findUserByEmail(email) != null) return null
    val id = UUID.randomUUID().toString()
    val v = ContentValues().apply {
        put("id", id)
        put("full_name", fullName)
        put("email", email.trim().lowercase())
        put("phone", phone)
        put("password_hash", Security.hash(password))
        put("role", role)
        put("branch", branch)
        put("active", 1)
        put("created_at", System.currentTimeMillis())
    }
    writableDatabase.insert("users", null, v)
    return id
}

internal fun LocalDb.updateUserProfile(id: String, fullName: String, phone: String?) {
    val v = ContentValues().apply { put("full_name", fullName); put("phone", phone) }
    writableDatabase.update("users", v, "id=?", arrayOf(id))
}

internal fun LocalDb.updateUserPassword(id: String, newPassword: String) {
    val v = ContentValues().apply { put("password_hash", Security.hash(newPassword)) }
    writableDatabase.update("users", v, "id=?", arrayOf(id))
}

internal fun LocalDb.updateUserAdmin(id: String, fullName: String, email: String, phone: String?, role: String, branch: String?, active: Boolean) {
    val v = ContentValues().apply { put("full_name", fullName); put("email", email.trim().lowercase()); put("phone", phone); put("role", role); put("branch", branch); put("active", if (active) 1 else 0) }
    writableDatabase.update("users", v, "id=?", arrayOf(id))
}

internal fun LocalDb.deleteUser(id: String) {
    writableDatabase.delete("users", "id=?", arrayOf(id))
}

internal fun LocalDb.deleteUserData(id: String, email: String) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        db.delete("quote_requests", "lower(email)=lower(?)", arrayOf(email))
        db.delete("contact_submissions", "lower(email)=lower(?)", arrayOf(email))
        db.delete("users", "id=?", arrayOf(id))
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

private fun android.database.Cursor.toQuote(): QuoteRow = QuoteRow(
    getLong(getColumnIndexOrThrow("id")),
    getString(getColumnIndexOrThrow("quote_code")) ?: "",
    getString(getColumnIndexOrThrow("first_name")),
    getString(getColumnIndexOrThrow("last_name")),
    getString(getColumnIndexOrThrow("email")),
    getString(getColumnIndexOrThrow("phone")),
    getString(getColumnIndexOrThrow("branch")),
    getString(getColumnIndexOrThrow("service")),
    getString(getColumnIndexOrThrow("message")),
    getString(getColumnIndexOrThrow("product_id")),
    getLong(getColumnIndexOrThrow("created_at")),
    getString(getColumnIndexOrThrow("status")) ?: "Pending",
    getString(getColumnIndexOrThrow("value"))
)

internal fun LocalDb.loadQuotes(): List<QuoteRow> {
    val c = readableDatabase.query("quote_requests", null, null, null, null, null, "created_at DESC")
    val out = mutableListOf<QuoteRow>()
    while (c.moveToNext()) out += c.toQuote()
    c.close()
    return out
}

internal fun LocalDb.submitQuote(firstName: String, lastName: String, email: String, phone: String, branch: String, service: String, message: String, productId: String?) {
    val code = "QT-" + (1000 + (System.currentTimeMillis() % 9000)).toString()
    val v = ContentValues().apply {
        put("quote_code", code)
        put("first_name", firstName)
        put("last_name", lastName)
        put("email", email)
        put("phone", phone)
        put("branch", branch)
        put("service", service)
        put("message", message)
        put("product_id", productId)
        put("created_at", System.currentTimeMillis())
        put("status", "Pending")
        put("value", null as String?)
    }
    writableDatabase.insert("quote_requests", null, v)
}

internal fun LocalDb.updateQuoteStatus(id: Long, status: String) {
    writableDatabase.update("quote_requests", ContentValues().apply { put("status", status) }, "id=?", arrayOf(id.toString()))
}

internal fun LocalDb.saveProduct(p: Product, isNew: Boolean) {
    val v = ContentValues().apply {
        put("category", p.category)
        put("title", p.title)
        put("tagline", p.tagline)
        put("description", p.description)
        put("image", p.image)
        put("gallery", p.gallery.joinToString("|"))
        put("features", p.features.joinToString("|"))
        put("finishes", p.finishes.joinToString("|"))
        put("lead_time", p.lead)
        put("tag", p.tag)
        put("price", p.price)
    }
    if (isNew) {
        writableDatabase.insert("products", null, v)
    } else {
        writableDatabase.update("products", v, "id=?", arrayOf(p.id.toString()))
    }
}

internal fun LocalDb.deleteProduct(id: Int) {
    writableDatabase.delete("products", "id=?", arrayOf(id.toString()))
}

internal fun LocalDb.saveTestimonial(t: Testimonial, isNew: Boolean) {
    val v = ContentValues().apply { put("name", t.name); put("role", t.role); put("location", t.location); put("rating", t.rating); put("review", t.review); put("project", t.project) }
    if (isNew) writableDatabase.insert("testimonials", null, v)
    else writableDatabase.update("testimonials", v, "id=?", arrayOf(t.id.toString()))
}

internal fun LocalDb.deleteTestimonial(id: Int) {
    writableDatabase.delete("testimonials", "id=?", arrayOf(id.toString()))
}

internal fun LocalDb.saveFaq(f: Faq, isNew: Boolean) {
    val v = ContentValues().apply { put("category", f.category); put("question", f.question); put("answer", f.answer) }
    if (isNew) writableDatabase.insert("faqs", null, v)
    else writableDatabase.update("faqs", v, "id=?", arrayOf(f.id.toString()))
}

internal fun LocalDb.deleteFaq(id: Int) {
    writableDatabase.delete("faqs", "id=?", arrayOf(id.toString()))
}

internal fun LocalDb.updateProducts(products: List<Product>) {
    val db = writableDatabase

    db.beginTransaction()

    try {
        db.delete("products", null, null)

        products.forEach { p ->
            val values = ContentValues().apply {
                put("id", p.id)
                put("category", p.category)
                put("title", p.title)
                put("tagline", p.tagline)
                put("description", p.description)
                put("image", p.image)
                put("gallery", p.gallery.joinToString("|"))
                put("features", p.features.joinToString("|"))
                put("finishes", p.finishes.joinToString("|"))
                put("lead_time", p.lead)
                put("tag", p.tag)
                put("price", p.price)
            }

            db.insert("products", null, values)
        }

        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

internal fun LocalDb.updateTestimonials(testimonials: List<Testimonial>) {
    val db = writableDatabase

    db.beginTransaction()

    try {
        db.delete("testimonials", null, null)

        testimonials.forEach { t ->
            val values = ContentValues().apply {
                put("id", t.id)
                put("name", t.name)
                put("role", t.role)
                put("location", t.location)
                put("rating", t.rating)
                put("review", t.review)
                put("project", t.project)
            }

            db.insert("testimonials", null, values)
        }

        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

internal fun LocalDb.updateFaqs(faqs: List<Faq>) {
    val db = writableDatabase

    db.beginTransaction()

    try {
        db.delete("faqs", null, null)

        faqs.forEach { f ->
            val values = ContentValues().apply {
                put("id", f.id)
                put("category", f.category)
                put("question", f.question)
                put("answer", f.answer)
            }

            db.insert("faqs", null, values)
        }

        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

internal fun LocalDb.loadFaqsByCategory(category: String): List<Faq> {
    val c = readableDatabase.query(
        "faqs",
        null,
        "category=?",
        arrayOf(category),
        null,
        null,
        "id"
    )

    val out = mutableListOf<Faq>()

    while (c.moveToNext()) {
        out += Faq(
            c.getInt(c.getColumnIndexOrThrow("id")),
            c.getString(c.getColumnIndexOrThrow("category")),
            c.getString(c.getColumnIndexOrThrow("question")),
            c.getString(c.getColumnIndexOrThrow("answer"))
        )
    }

    c.close()

    return out
}

internal fun LocalDb.updateBranches(branches: List<Branch>) {
    val db = writableDatabase

    db.beginTransaction()

    try {
        db.delete("branches", null, null)

        branches.forEach { b ->
            val values = ContentValues().apply {
                put("id", b.id)
                put("name", b.name)
                put("region", b.region)
                put("phone", b.phone)
                put("hours", b.hours)
                put("notes", b.notes)
            }

            db.insert("branches", null, values)
        }

        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}
