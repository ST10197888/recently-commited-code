package com.woodlands.mobile

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Account deletion, matching the website: the customer confirms their password, the request goes to an
 * administrator for approval, and the account stays active until it is approved. These calls talk to the
 * Node API directly (no offline queue) because a request has to reach the server to mean anything.
 * Every function here blocks, so call them from a background thread.
 */
internal object AccountDeletionApi {
    data class PendingRequest(val id: String, val createdAt: Long)
    data class State(val keepData: Boolean, val pending: PendingRequest?)

    private const val OFFLINE = "You're offline. Connect to the internet and try again."
    private const val WRONG_PASSWORD = "The password you entered is incorrect."

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun rows(body: String): List<JSONObject> = try {
        val arr = JSONArray(body)
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    } catch (e: JSONException) {
        emptyList()
    }

    private fun failure(r: ApiResult, fallback: String): Exception = when (r) {
        is ApiResult.Failure -> Exception(r.message)
        is ApiResult.Offline -> Exception(OFFLINE)
        is ApiResult.Success -> Exception(fallback)
    }

    /** The "keep a recoverable copy" setting stored on the account. */
    fun loadRecovery(me: AppUser): Result<Boolean> {
        val r = ApiClient.get("/api/app-users?email=" + enc(me.email))
        if (r !is ApiResult.Success) return Result.failure(failure(r, "Unable to load your account details."))
        val mine = rows(r.body).firstOrNull { it.optString("id") == me.id }
            ?: return Result.failure(Exception("Unable to load your account details."))
        return Result.success(mine.optBoolean("keep_data_on_delete", false))
    }

    /** The user's open deletion request, if any. Only a row whose status is Pending counts. */
    fun loadPending(me: AppUser): Result<PendingRequest?> {
        val r = ApiClient.get("/api/deletion-requests?status=Pending&user_id=" + enc(me.id))
        if (r !is ApiResult.Success) return Result.failure(failure(r, "Unable to load your deletion request."))
        val match = rows(r.body).firstOrNull {
            it.optString("user_id") == me.id && it.optString("status").equals("Pending", ignoreCase = true)
        }
        return Result.success(match?.let { PendingRequest(it.optString("id"), parseMillis(it.str("created_at"))) })
    }

    fun loadState(me: AppUser): Result<State> {
        val keep = loadRecovery(me).getOrElse { return Result.failure(it) }
        val pending = loadPending(me).getOrElse { return Result.failure(it) }
        return Result.success(State(keep, pending))
    }

    /** Re-checks the password against the same login the website uses, without touching the session. */
    fun verifyPassword(me: AppUser, password: String): Result<Unit> {
        val body = JSONObject().put("email", me.email).put("password", password)
        return when (val r = ApiClient.post("/api/auth/login", body.toString())) {
            is ApiResult.Success -> try {
                val id = JSONObject(r.body).getJSONObject("user").getString("id")
                if (id == me.id) Result.success(Unit) else Result.failure(Exception(WRONG_PASSWORD))
            } catch (e: JSONException) {
                Result.failure(Exception("Unexpected response from the server."))
            }
            is ApiResult.Failure ->
                Result.failure(Exception(if (r.code == 400 || r.code == 401) WRONG_PASSWORD else r.message))
            is ApiResult.Offline -> Result.failure(Exception(OFFLINE))
        }
    }

    fun submit(me: AppUser, keepData: Boolean, message: String = ""): Result<Unit> {
        val body = JSONObject()
            .put("user_id", me.id)
            .put("email", me.email)
            .put("full_name", me.fullName)
            .put("role", me.role)
            .put("keep_data", keepData)
            .put("message", message)
        val r = ApiClient.post("/api/deletion-requests", body.toString())
        return if (r is ApiResult.Success) Result.success(Unit)
        else Result.failure(failure(r, "Unable to send your request. Please try again."))
    }

    fun cancel(requestId: String): Result<Unit> {
        val r = ApiClient.put("/api/deletion-requests/" + enc(requestId) + "/cancel", "{}")
        return if (r is ApiResult.Success) Result.success(Unit)
        else Result.failure(failure(r, "Unable to cancel the request."))
    }

    fun setRecovery(userId: String, enabled: Boolean): Result<Unit> {
        val body = JSONObject().put("keep_data_on_delete", enabled)
        val r = ApiClient.put("/api/app-users/" + enc(userId), body.toString())
        return if (r is ApiResult.Success) Result.success(Unit)
        else Result.failure(failure(r, "Unable to update your recovery setting."))
    }

    /** True when a previous account with this email left a saved copy that can be restored. */
    fun archiveAvailable(me: AppUser): Boolean {
        val r = ApiClient.get("/api/account-archive/available?email=" + enc(me.email))
        return r is ApiResult.Success && try {
            JSONObject(r.body).optBoolean("available", false)
        } catch (e: JSONException) { false }
    }

    fun sendRestoreCode(me: AppUser): Result<Unit> {
        val r = ApiClient.post("/api/account-archive/request-code", JSONObject().put("email", me.email).toString())
        return if (r is ApiResult.Success) Result.success(Unit)
        else Result.failure(failure(r, "Unable to send a code right now."))
    }

    fun restoreData(me: AppUser, code: String): Result<Unit> {
        val body = JSONObject().put("email", me.email).put("user_id", me.id).put("code", code.trim())
        val r = ApiClient.post("/api/account-archive/restore", body.toString())
        return if (r is ApiResult.Success) Result.success(Unit)
        else Result.failure(failure(r, "Unable to restore your data."))
    }
}

private val DANGER = Color.rgb(179, 38, 30)

private fun MainActivity.onUi(block: () -> Unit) {
    runOnUiThread { if (!isFinishing && !isDestroyed) block() }
}

/** "Delete my account" card for the profile screen. */
internal fun MainActivity.addDeletionCard(me: AppUser) {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = bg(Color.WHITE, Color.rgb(236, 196, 192), 12)
    }
    box.addView(tv("Delete my account", 18, DANGER).apply { setTypeface(Typeface.SERIF, Typeface.BOLD) })
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    box.addView(body, marginParams(0, 8, 0, 0))
    content.addView(box, marginParams(16, 0, 16, 24))

    if (me.role == Roles.ADMIN) {
        body.addView(tv("Administrator accounts can't be deleted from here. Ask another administrator to remove this account under Users.", 12, muted))
        return
    }

    body.addView(tv("Checking your account…", 12, muted))
    thread {
        val state = AccountDeletionApi.loadState(me)
        onUi {
            // The user may have moved to another screen while this was loading.
            if (content.indexOfChild(box) < 0) return@onUi
            body.removeAllViews()
            state
                .onSuccess { renderDeletionBody(me, body, it) }
                .onFailure {
                    body.addView(tv(it.message ?: "Unable to load your account details.", 12, muted))
                    body.addView(outlineButton("Try again", DANGER).apply { setOnClickListener { showScreen("profile") } }, marginParams(0, 12, 0, 0))
                }
        }
    }
}

private fun MainActivity.renderDeletionBody(me: AppUser, body: LinearLayout, state: AccountDeletionApi.State) {
    val pending = state.pending

    if (pending != null) {
        val sent = if (pending.createdAt > 0)
            "sent on " + SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(pending.createdAt))
        else "sent"
        body.addView(
            tv("Your deletion request was $sent and is waiting for administrator approval. Your account stays active until then.", 13, text).apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = bg(Color.rgb(250, 245, 238), Color.rgb(226, 205, 178), 10)
            }
        )
        val cancel = outlineButton("Cancel request", text)
        cancel.setOnClickListener {
            cancel.isEnabled = false
            cancel.text = "Cancelling…"
            thread {
                val result = AccountDeletionApi.cancel(pending.id)
                onUi {
                    result
                        .onSuccess {
                            toast("Your deletion request was cancelled.")
                            if (currentScreen == "profile") showScreen("profile")
                        }
                        .onFailure {
                            toast(it.message ?: "Unable to cancel the request.")
                            cancel.isEnabled = true
                            cancel.text = "Cancel request"
                        }
                }
            }
        }
        body.addView(cancel, marginParams(0, 14, 0, 0))
        return
    }

    body.addView(tv("Deleting your account needs administrator approval. Once approved, your account and every quote request submitted with your email address are permanently removed. You can register again with the same email afterwards.", 12, muted))

    val recovery = SpannableStringBuilder("Recovery is ")
    val state1 = if (state.keepData) "on" else "off"
    val start = recovery.length
    recovery.append(state1)
    recovery.setSpan(StyleSpan(Typeface.BOLD), start, recovery.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    recovery.append(if (state.keepData) ": a copy of your data will be kept so you can restore it later." else ": your data will not be kept.")
    body.addView(TextView(this).apply { text = recovery; textSize = 13f; setTextColor(this@renderDeletionBody.text) }, marginParams(0, 12, 0, 0))
    body.addView(
        tv("Change in Settings", 12, blue).apply {
            setTypeface(typeface, Typeface.BOLD)
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { showScreen("settings") }
        },
        marginParams(0, 6, 0, 0)
    )

    val send = outlineButton("Request account deletion", DANGER)
    send.setOnClickListener { showDeletionDialog(me, state) }
    body.addView(send, marginParams(0, 14, 0, 0))
}

private fun MainActivity.showDeletionDialog(me: AppUser, state: AccountDeletionApi.State) {
    val activity = this

    val messageField = field("Message to the administrator", "", multi = true, required = false).apply { minLines = 2 }

    val password = passwordField("Your password").apply {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            setAutofillHints(android.view.View.AUTOFILL_HINT_PASSWORD)
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
        }
    }

    // Suggests the phrase as soon as they start typing; they still have to pick it or type it in full.
    val phrase = android.widget.AutoCompleteTextView(activity).apply {
        hint = "Type \"Delete account\" to confirm *"
        textSize = 14f
        setTextColor(this@showDeletionDialog.text)
        setHintTextColor(muted)
        background = bg(Color.WHITE, Color.rgb(201, 201, 193), 10)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        setSingleLine()
        threshold = 1
        setAdapter(android.widget.ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, listOf("Delete account")))
    }

    val holder = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(tv("Your request goes to an administrator. Your account stays active until it is approved. Fields marked * are required.", 12, muted))
        addView(messageField, marginParams(0, 12, 0, 0))
        addView(password, marginParams(0, 10, 0, 0))
        addView(phrase, marginParams(0, 10, 0, 0))
    }

    val dialog = AlertDialog.Builder(activity)
        .setTitle("Request account deletion")
        .setView(android.widget.ScrollView(activity).apply { addView(holder) })
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Send request", null)   // click handled below so the popup can stay open on errors
        .create()

    dialog.setOnShowListener {
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        ok.setOnClickListener {
            val typedPassword = password.text?.toString().orEmpty()
            if (typedPassword.isBlank()) {
                password.error = "Enter your password"
                password.requestFocus()
                return@setOnClickListener
            }
            if (!phrase.text.toString().trim().equals("delete account", ignoreCase = true)) {
                dialog.dismiss()
                android.widget.Toast.makeText(
                    activity, "Your delete request was not sent for approval.", android.widget.Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val note = messageField.text?.toString()?.trim().orEmpty()
            ok.isEnabled = false
            ok.text = "Sending…"
            thread {
                val verified = AccountDeletionApi.verifyPassword(me, typedPassword)
                if (verified.isFailure) {
                    val msg = verified.exceptionOrNull()?.message ?: "Unable to check your password."
                    onUi {
                        ok.isEnabled = true
                        ok.text = "Send request"
                        if (msg.contains("password", ignoreCase = true)) password.error = msg else toast(msg)
                    }
                    return@thread
                }
                val result = AccountDeletionApi.submit(me, state.keepData, note)
                onUi {
                    result
                        .onSuccess {
                            dialog.dismiss()
                            toast("Your deletion request was sent to an administrator. Your account stays active until it is approved.")
                            if (currentScreen == "profile") showScreen("profile")
                        }
                        .onFailure {
                            ok.isEnabled = true
                            ok.text = "Send request"
                            toast(it.message ?: "Unable to send your request. Please try again.")
                        }
                }
            }
        }
    }
    dialog.show()
}

/** "Account recovery" card for the settings screen: the keep-a-copy switch the deletion card refers to. */
internal fun MainActivity.addRecoveryCard(me: AppUser) {
    sectionTitle("Account recovery", "")
    val card = card().apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    labels.addView(tv("Keep a recoverable copy if my account is deleted", 13, text).apply { setTypeface(typeface, Typeface.BOLD) })
    labels.addView(
        tv("When an administrator approves a deletion, your profile details and quote requests are saved so you can restore them if you register again with this email. Nothing is restored unless you ask for it.", 11, muted)
            .apply { setPadding(0, dp(4), 0, 0) }
    )
    val status = tv("Loading…", 11, muted).apply { setPadding(0, dp(6), 0, 0) }
    labels.addView(status)

    val toggle = Switch(this).apply { isEnabled = false }
    row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.setMargins(dp(12), 0, 0, 0) })
    card.addView(row)
    content.addView(card, marginParams(16, 6, 16, 14))

    var programmatic = false
    toggle.setOnCheckedChangeListener { button, checked ->
        if (programmatic) return@setOnCheckedChangeListener
        button.isEnabled = false
        thread {
            val result = AccountDeletionApi.setRecovery(me.id, checked)
            onUi {
                button.isEnabled = true
                result
                    .onSuccess {
                        toast(
                            if (checked) "Recovery is on. A copy of your data will be kept if your account is deleted."
                            else "Recovery is off. Your data will not be kept if your account is deleted."
                        )
                    }
                    .onFailure {
                        programmatic = true
                        button.isChecked = !checked
                        programmatic = false
                        toast(it.message ?: "Unable to update your recovery setting.")
                    }
            }
        }
    }

    thread {
        val result = AccountDeletionApi.loadRecovery(me)
        onUi {
            if (content.indexOfChild(card) < 0) return@onUi
            result
                .onSuccess {
                    programmatic = true
                    toggle.isChecked = it
                    programmatic = false
                    toggle.isEnabled = true
                    status.visibility = android.view.View.GONE
                }
                .onFailure { status.text = it.message ?: "Couldn't load this setting." }
        }
    }
}

/** "Saved data found" card for the settings screen: email a code, then restore (matches the website). */
internal fun MainActivity.addRestoreCard(me: AppUser) {
    val box = card().apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        visibility = android.view.View.GONE
    }
    box.addView(tv("Saved data found for your email", 13, text).apply { setTypeface(typeface, Typeface.BOLD) })
    box.addView(
        tv("A previous account with this email address left a saved copy of its details and quotes. Restoring is optional. We email a 6-digit code to this address first.", 11, muted)
            .apply { setPadding(0, dp(4), 0, dp(10)) }
    )

    val sendCode = outlineButton("Email me a code", blue)
    sendCode.setOnClickListener {
        sendCode.isEnabled = false
        thread {
            val result = AccountDeletionApi.sendRestoreCode(me)
            onUi {
                sendCode.isEnabled = true
                result
                    .onSuccess { toast("We emailed a 6-digit code to your address. It expires in 15 minutes.") }
                    .onFailure { toast(it.message ?: "Unable to send a code right now.") }
            }
        }
    }
    box.addView(sendCode)

    val code = field("6-digit code", "").apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(android.text.InputFilter.LengthFilter(6))
    }
    box.addView(code, marginParams(0, 12, 0, 0))

    val restore = button("Restore my data", blue, Color.WHITE)
    restore.setOnClickListener {
        val typed = code.text?.toString()?.trim().orEmpty()
        if (typed.isEmpty()) {
            toast("Enter the 6-digit code from your email.")
            return@setOnClickListener
        }
        restore.isEnabled = false
        thread {
            val result = AccountDeletionApi.restoreData(me, typed)
            onUi {
                restore.isEnabled = true
                result
                    .onSuccess {
                        toast("Your saved data was restored to your account.")
                        SyncManager.syncAll()
                        if (currentScreen == "settings") showScreen("settings")
                    }
                    .onFailure { toast(it.message ?: "Unable to restore your data.") }
            }
        }
    }
    box.addView(restore, marginParams(0, 8, 0, 0))
    content.addView(box, marginParams(16, 6, 16, 14))

    thread {
        val available = AccountDeletionApi.archiveAvailable(me)
        onUi {
            if (content.indexOfChild(box) >= 0 && available) box.visibility = android.view.View.VISIBLE
        }
    }
}