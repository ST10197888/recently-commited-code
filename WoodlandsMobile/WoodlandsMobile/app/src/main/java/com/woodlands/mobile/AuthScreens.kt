package com.woodlands.mobile

import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.CheckBox

internal fun MainActivity.loginScreen() {
    pageIntro("Login", "Sign in to your Woodlands Designer Boards account.")
    val email = field("Email Address", "you@example.com").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
    val password = passwordField("Password")
    listOf(email, password).forEach { content.addView(it, marginParams(16, 8, 16, 8)) }
    content.addView(button("Login", blue, Color.WHITE).apply {
        setOnClickListener {
            if (email.text.isNullOrBlank() || password.text.isNullOrBlank()) { toast("Please enter your email and password"); return@setOnClickListener }
            val user = db.verifyCredentials(email.text.toString(), password.text.toString())
            if (user == null) { toast("Invalid email or password."); return@setOnClickListener }
            signIn(user)
        }
    }, marginParams(16, 14, 16, 8))
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    row.addView(tv("Don't have an account?", 12, muted))
    row.addView(tv("  Register", 12, blue).apply { setTypeface(typeface, Typeface.BOLD); isClickable = true; setOnClickListener { showScreen("register") } })
    content.addView(row, marginParams(16, 4, 16, 20))

    sectionTitle("Prototype accounts", "For testing — the same accounts seeded on the website")
    listOf(
        Triple("Admin", "admin@woodlandsdb.co.za", "admin123"),
        Triple("Manager (any branch)", "soweto@woodlandsdb.co.za", "manager123"),
        Triple("Customer", "customer@example.com", "customer123")
    ).forEach { (label, e, p) ->
        content.addView(tv("$label — $e / $p", 11, muted).apply { setPadding(dp(16), dp(2), dp(16), dp(2)) })
    }
}

internal fun MainActivity.registerScreen() {
    pageIntro("Create an Account", "Register to save your details and track your quotes.")
    val fullName = field("Full Name", "e.g. Thabo Mokoena")
    val email = field("Email Address", "you@example.com").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
    val phone = field("Phone Number", "071 234 5678", required = false)
    val password = passwordField("Password (min 8 characters)")
    val confirm = passwordField("Confirm Password")
    val privacy = CheckBox(this).apply { text = "I have read the privacy notice. Account details are stored locally in this prototype."; setTextColor(Color.rgb(26, 26, 26)) }
    listOf(fullName, email, phone, password, confirm).forEach { content.addView(it, marginParams(16, 6, 16, 6)) }
    content.addView(outlineButton("Read privacy notice", blue).apply { setOnClickListener { showPrivacyNotice() } }, marginParams(16, 6, 16, 4))
    content.addView(privacy, marginParams(16, 6, 16, 6))
    content.addView(button("Register", red, Color.WHITE).apply {
        setOnClickListener {
            if (fullName.text.isNullOrBlank() || email.text.isNullOrBlank() || password.text.isNullOrBlank()) { toast("Please complete all required fields"); return@setOnClickListener }
            if (!privacy.isChecked) { toast("Please read and acknowledge the privacy notice"); return@setOnClickListener }
            if (password.text.toString().length < 8) { toast("Password must be at least 8 characters"); return@setOnClickListener }
            if (password.text.toString() != confirm.text.toString()) { toast("Passwords do not match"); return@setOnClickListener }
            val id = db.createUser(fullName.text.toString(), email.text.toString(), phone.text?.toString(), password.text.toString(), Roles.CUSTOMER, null)
            if (id == null) { toast("An account with that email already exists"); return@setOnClickListener }
            val user = db.findUserById(id)!!
            toast("Welcome, ${user.fullName}!")
            signIn(user)
        }
    }, marginParams(16, 14, 16, 8))
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    row.addView(tv("Already have an account?", 12, muted))
    row.addView(tv("  Login", 12, blue).apply { setTypeface(typeface, Typeface.BOLD); isClickable = true; setOnClickListener { showScreen("login") } })
    content.addView(row, marginParams(16, 4, 16, 20))
    content.addView(tv("New accounts are created with the Customer role, exactly like registering on the website. Admin and manager accounts are provisioned by an administrator.", 11, muted).apply { setPadding(dp(16), 0, dp(16), dp(20)) })
}

internal fun MainActivity.profileScreen() {
    val activity = this
    val me = currentUser()
    if (me == null) { showScreen("login"); return }
    pageIntro("My Profile", "View and update your account details.")
    val cardV = card().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
    cardV.addView(tv("Role", 10, muted)); cardV.addView(tv(Roles.label(me.role), 14, blue).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(8)) })
    cardV.addView(tv("Branch", 10, muted)); cardV.addView(tv(me.branch ?: "All branches / Not applicable", 13, text))
    content.addView(cardV, marginParams(16, 6, 16, 14))

    sectionTitle("Edit details", "")
    val fullName = field("Full Name", "Full name").apply { setText(me.fullName) }
    val email = field("Email Address", "Email").apply { setText(me.email); isEnabled = false; alpha = 0.6f }
    val phone = field("Phone Number", "Phone number").apply { setText(me.phone.orEmpty()) }
    listOf(fullName, email, phone).forEach { content.addView(it, marginParams(16, 6, 16, 6)) }
    content.addView(tv("Email addresses can't be changed in this prototype (matches the website's account model).", 11, muted).apply { setPadding(dp(16), 0, dp(16), dp(4)) })
    content.addView(button("Save Changes", blue, Color.WHITE).apply {
        setOnClickListener {
            if (fullName.text.isNullOrBlank()) { toast("Full name is required"); return@setOnClickListener }
            db.updateUserProfile(me.id, fullName.text.toString(), phone.text?.toString())
            toast("Profile updated")
            showScreen("profile")
        }
    }, marginParams(16, 10, 16, 20))

    sectionTitle("Change password", "")
    val newPass = passwordField("New Password (min 8 characters)")
    val confirmPass = passwordField("Confirm New Password")
    listOf(newPass, confirmPass).forEach { content.addView(it, marginParams(16, 6, 16, 6)) }
    content.addView(button("Update Password", navy, Color.WHITE).apply {
        setOnClickListener {
            val p = newPass.text.toString()
            if (p.length < 8) { toast("Password must be at least 8 characters"); return@setOnClickListener }
            if (p != confirmPass.text.toString()) { toast("Passwords do not match"); return@setOnClickListener }
            db.updateUserPassword(me.id, p)
            toast("Password updated")
            newPass.setText(""); confirmPass.setText("")
        }
    }, marginParams(16, 6, 16, 24))

    content.addView(outlineButton("Delete my local account and quote data", red).apply {
        setOnClickListener {
            android.app.AlertDialog.Builder(activity)
                .setTitle("Delete local account data?")
                .setMessage("This removes your account and quote records saved on this device. It cannot delete data held by the website or its service providers. Contact the business by email for those requests.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete local data") { _, _ ->
                    db.deleteUserData(me.id, me.email)
                    session.clear()
                    toast("Local account and quote data deleted")
                    showScreen("home")
                }
                .show()
        }
    }, marginParams(16, 0, 16, 24))
}

internal fun MainActivity.settingsScreen() {
    val me = currentUser()
    if (me == null) { showScreen("login"); return }
    pageIntro("Settings", "Account and application settings.")

    sectionTitle("My Account", "")
    val accountCard = card().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
    listOf("Name" to me.fullName, "Email" to me.email, "Role" to Roles.label(me.role), "Branch" to (me.branch ?: "All branches / Not applicable")).forEach { (label, value) ->
        accountCard.addView(tv(label.uppercase(), 10, muted).apply { setPadding(0, dp(8), 0, 0) })
        accountCard.addView(tv(value, 14, if (label == "Role") blue else text).apply { setTypeface(typeface, Typeface.BOLD) })
    }
    content.addView(accountCard, marginParams(16, 6, 16, 14))

    sectionTitle("Security", "")
    listOf(
        "Password hashing enabled" to "Passwords are salted and SHA-256 hashed before being stored on this device.",
        "Local-only storage" to "Account data lives in this app's private SQLite database and is not sent to the website.",
        "Role-based access" to "Screens and actions are shown or hidden based on your account's role, the same as the website."
    ).forEach { (title, sub) ->
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = bg(greenBg, Color.rgb(190, 230, 200), 10) }
        c.addView(tv(title, 13, green).apply { setTypeface(typeface, Typeface.BOLD) })
        c.addView(tv(sub, 11, Color.rgb(60, 110, 70)).apply { setPadding(0, dp(3), 0, 0) })
        content.addView(c, marginParams(16, 6, 16, 6))
    }

    content.addView(outlineButton("Edit Profile", blue).apply { setOnClickListener { showScreen("profile") } }, marginParams(16, 16, 16, 6))
    content.addView(outlineButton("Logout", red).apply { setOnClickListener { session.clear(); toast("Signed out"); showScreen("home") } }, marginParams(16, 0, 16, 24))
}
