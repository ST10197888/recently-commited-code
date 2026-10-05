package com.woodlands.mobile

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Base64
import java.io.File

object SyncManager {
    private const val TAG = "WoodlandsSync"
    private const val MAX_ATTEMPTS = 5

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    @Volatile private var ready = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val rerun = AtomicBoolean(false)

    @Volatile var onChanged: ((Boolean) -> Unit)? = null

    private class PushOutcome(val allDone: Boolean, val processed: Int)

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("woodlands_sync", Context.MODE_PRIVATE)
        ready = true
    }

    fun forgetPrivateHashes() {
        if (!ready) return
        prefs.edit().remove("h_users").remove("h_quotes").apply()
    }

    fun syncAll() {
        if (!ready) return
        if (!running.compareAndSet(false, true)) {
            rerun.set(true)
            return
        }
        scope.launch {
            try {
                do {
                    rerun.set(false)
                    runSync()
                } while (rerun.get())
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed", e)
            } finally {
                running.set(false)
            }
        }
    }

    fun login(email: String, password: String, onResult: (Result<AppUser>) -> Unit) {
        scope.launch {
            val result = doLogin(email, password)
            mainHandler.post { onResult(result) }
        }
    }

    fun register(fullName: String, email: String, phone: String?, password: String, onResult: (Result<AppUser>) -> Unit) {
        scope.launch {
            val body = JSONObject()
                .put("fullName", fullName.trim())
                .put("email", email.trim())
                .put("password", password)
                .put("phone", phone ?: "")
            val result: Result<AppUser> = when (val r = ApiClient.post("/api/auth/register", body.toString())) {
                is ApiResult.Success -> doLogin(email, password)
                is ApiResult.Failure -> Result.failure(Exception(r.message))
                is ApiResult.Offline -> Result.failure(Exception("You're offline. Connect to the internet to register."))
            }
            mainHandler.post { onResult(result) }
        }
    }

    private fun doLogin(email: String, password: String): Result<AppUser> {
        val body = JSONObject().put("email", email.trim()).put("password", password)
        return when (val r = ApiClient.post("/api/auth/login", body.toString())) {
            is ApiResult.Success -> try {
                val profile = JSONObject(r.body).getJSONObject("user")
                val db = LocalDb.shared(appContext)
                db.upsertUser(profile)
                val user = db.findUserById(profile.getString("id"))
                if (user == null) {
                    Result.failure(Exception("Could not read your profile."))
                } else {
                    Session(appContext).userId = user.id
                    syncAll()
                    Result.success(user)
                }
            } catch (e: JSONException) {
                Result.failure(Exception("Unexpected response from the server."))
            }
            is ApiResult.Failure -> Result.failure(Exception(r.message))
            is ApiResult.Offline -> Result.failure(Exception("You're offline. Connect to the internet to sign in."))
        }
    }

    fun isOnline(): Boolean {
        if (!ready) return false
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    internal fun checkConnection(syncAfter: Boolean, onResult: (ConnectionStatus) -> Unit) {
        scope.launch {
            val online = isOnline()
            val status = if (!online) {
                ConnectionStatus(false, false, "No internet connection on this device.")
            } else {
                when (val r = ApiClient.get("/api/branches")) {
                    is ApiResult.Success -> ConnectionStatus(true, true, "The app can reach the Woodlands database.")
                    is ApiResult.Failure -> ConnectionStatus(true, false, "The server returned an error (${r.code}).")
                    is ApiResult.Offline -> ConnectionStatus(true, false, "The Woodlands server can't be reached.")
                }
            }
            mainHandler.post { onResult(status) }
            if (syncAfter && status.database) syncAll()
        }
    }
    internal fun checkApi(onResult: (List<EndpointStatus>) -> Unit) {
        scope.launch {
            val targets = listOf(
                "Products" to "/api/products",
                "Branches" to "/api/branches",
                "FAQs" to "/api/faqs",
                "Testimonials" to "/api/testimonials",
                "Quote requests" to "/api/quote-requests?email=status-check",
                "Users" to "/api/app-users?email=status-check"
            )
            val out = mutableListOf<EndpointStatus>()
            var serverDown = !isOnline()
            for ((name, path) in targets) {
                if (serverDown) {
                    out += EndpointStatus(name, false, false, "Offline")
                    continue
                }
                when (val r = ApiClient.get(path)) {
                    is ApiResult.Success -> out += EndpointStatus(name, true, true, "Online")
                    is ApiResult.Failure -> out += EndpointStatus(name, false, true, "Error ${r.code}")
                    is ApiResult.Offline -> {
                        serverDown = true
                        out += EndpointStatus(name, false, false, "Offline")
                    }
                }
            }
            mainHandler.post { onResult(out) }
        }
    }

    private fun runSync() {
        val db = LocalDb.shared(appContext)
        val firstLoad = db.loadBranches().isEmpty() && db.loadProducts().isEmpty()
        val outcome = push(db)
        if (!outcome.allDone) {
            if (outcome.processed > 0) publishChange(firstLoad)
            return
        }
        val results = listOf(
            pullProducts(db),
            pullTestimonials(db),
            pullFaqs(db),
            pullBranches(db),
            pullAccountData(db)
        )
        if (outcome.processed > 0 || results.any { it }) publishChange(firstLoad)
    }

    private fun publishChange(firstLoad: Boolean) {
        mainHandler.post { onChanged?.invoke(firstLoad) }
    }

    private fun push(db: LocalDb): PushOutcome {
        var processed = 0
        for (op in db.pendingOps()) {
            val result = execute(op)
            when {
                result is ApiResult.Success -> {
                    db.removeOp(op.id)
                    processed++
                }
                result is ApiResult.Offline -> return PushOutcome(false, processed)
                result is ApiResult.Failure && result.code in 400..499 && result.code != 408 && result.code != 429 -> {
                    Log.w(TAG, "Dropped ${op.method} ${op.path}: ${result.code} ${result.message}")
                    db.removeOp(op.id)
                    processed++
                }
                else -> {
                    if (op.attempts + 1 >= MAX_ATTEMPTS) {
                        Log.w(TAG, "Gave up on ${op.method} ${op.path} after ${op.attempts + 1} attempts")
                        db.removeOp(op.id)
                        processed++
                    } else {
                        db.bumpAttempts(op.id)
                        return PushOutcome(false, processed)
                    }
                }
            }
        }
        return PushOutcome(true, processed)
    }

    private fun execute(op: PendingOp): ApiResult = when (op.kind) {
        OP_PROMOTE -> promote(op)
        OP_BRANCH_IMAGE -> uploadBranchImage(op)
        else -> when (op.method) {
            "POST" -> ApiClient.post(op.path, op.body ?: "{}")
            "PUT" -> ApiClient.put(op.path, op.body ?: "{}")
            "DELETE" -> ApiClient.delete(op.path)
            else -> ApiClient.get(op.path)
        }
    }

    private fun uploadBranchImage(op: PendingOp): ApiResult {
        val file = File(op.body ?: "")
        if (!file.exists()) return ApiResult.Failure(404, "Image file is missing")
        val payload = JSONObject()
            .put("contentType", "image/jpeg")
            .put("data", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
        val result = ApiClient.put(op.path, payload.toString())
        if (result is ApiResult.Success) {
            val url = try { JSONObject(result.body).optString("image") } catch (e: JSONException) { "" }
            val remoteId = op.path.removePrefix("/api/branches/").removeSuffix("/image")
            if (url.isNotBlank()) LocalDb.shared(appContext).setBranchImage(remoteId, url)
        }
        return result
    }

    private fun promote(op: PendingOp): ApiResult {
        val body = JSONObject(op.body ?: "{}")
        val email = body.optString("email")
        val lookup = ApiClient.get("/api/app-users?email=" + enc(email))
        if (lookup !is ApiResult.Success) return lookup
        val rows = try { JSONArray(lookup.body) } catch (e: JSONException) { JSONArray() }
        val id = rows.optJSONObject(0)?.optString("id").orEmpty()
        if (id.isBlank()) return ApiResult.Failure(404, "User not found")
        body.remove("email")
        return ApiClient.put("/api/app-users/$id", body.toString())
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun fetchArray(path: String): Pair<JSONArray, String>? {
        val r = ApiClient.get(path)
        if (r !is ApiResult.Success) return null
        return try { Pair(JSONArray(r.body), r.body) } catch (e: JSONException) { null }
    }

    private fun objects(arr: JSONArray): List<JSONObject> =
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("id").isNotBlank() }

    private fun changedSince(key: String, body: String): Boolean {
        val hash = body.hashCode().toString()
        if (prefs.getString(key, null) == hash) return false
        prefs.edit().putString(key, hash).apply()
        return true
    }

    private fun pullProducts(db: LocalDb): Boolean {
        val (arr, body) = fetchArray("/api/products") ?: return false
        val rows = objects(arr).map { o ->
            val price = o.str("price") ?: ""
            ContentValues().apply {
                put("remote_id", o.optString("id"))
                put("category", o.str("category") ?: "")
                put("title", o.str("title") ?: "")
                put("tagline", o.str("tagline") ?: "")
                put("description", o.str("description") ?: "")
                put("image", o.str("image") ?: "")
                put("gallery", o.list("gallery").joinToString("|"))
                put("features", o.list("features").joinToString("|"))
                put("finishes", o.list("finishes").joinToString("|"))
                put("lead_time", o.str("lead_time") ?: "")
                put("tag", o.str("tag"))
                put("price", if (o.optBoolean("is_from_price", false) && price.isNotBlank()) "From $price" else price)
            }
        }
        db.reconcile("products", rows)
        return changedSince("h_products", body)
    }

    private fun pullTestimonials(db: LocalDb): Boolean {
        val (arr, body) = fetchArray("/api/testimonials") ?: return false
        val rows = objects(arr).map { o ->
            ContentValues().apply {
                put("remote_id", o.optString("id"))
                put("name", o.str("name") ?: "")
                put("role", o.str("role") ?: "")
                put("location", o.str("location") ?: "")
                put("rating", o.optInt("rating", 5))
                put("review", o.str("review") ?: "")
                put("project", o.str("project") ?: "")
            }
        }
        db.reconcile("testimonials", rows)
        return changedSince("h_testimonials", body)
    }

    private fun pullFaqs(db: LocalDb): Boolean {
        val (arr, body) = fetchArray("/api/faqs") ?: return false
        val rows = objects(arr).map { o ->
            ContentValues().apply {
                put("remote_id", o.optString("id"))
                put("category", o.str("category") ?: "")
                put("question", o.str("question") ?: "")
                put("answer", o.str("answer") ?: "")
            }
        }
        db.reconcile("faqs", rows)
        return changedSince("h_faqs", body)
    }

    private fun pullBranches(db: LocalDb): Boolean {
        val (arr, body) = fetchArray("/api/branches") ?: return false
        val rows = objects(arr).map { o ->
            ContentValues().apply {
                put("remote_id", o.optString("id"))
                put("name", o.str("name") ?: "")
                put("region", o.str("region") ?: "")
        if (o.has("address") && !o.isNull("address")) put("address", o.optString("address"))
                put("phone", o.str("phone") ?: "")
                put("hours", o.str("hours") ?: "")
                put("notes", o.str("notes") ?: "")
                put("image", o.str("image") ?: "")
            }
        }
        db.reconcile("branches", rows)
        return changedSince("h_branches", body)
    }

    private fun pullAccountData(db: LocalDb): Boolean {
        val userId = Session(appContext).userId ?: return false
        val me = db.findUserById(userId) ?: return false
        val isAdmin = me.role == Roles.ADMIN
        var changed = false

        if (isAdmin) {
            fetchArray("/api/app-users")?.let { (arr, body) ->
                db.reconcileUsers(objects(arr).map { userValues(it) })
                if (changedSince("h_users", body)) changed = true
            }
        } else {
            fetchArray("/api/app-users?email=" + enc(me.email))?.let { (arr, body) ->
                objects(arr).firstOrNull { it.optString("id") == me.id }?.let { db.upsertUser(it) }
                if (changedSince("h_users", body)) changed = true
            }
        }

        val branch = Roles.branchFor(me.role)
        val quotePath = when {
            isAdmin -> "/api/quote-requests"
            branch != null -> "/api/quote-requests?branch=" + enc(branch)
            else -> "/api/quote-requests?email=" + enc(me.email)
        }
        fetchArray(quotePath)?.let { (arr, body) ->
            val rows = objects(arr).map { o ->
                ContentValues().apply {
                    put("remote_id", o.optString("id"))
                    put("quote_code", o.str("quote_code") ?: "")
                    put("first_name", o.str("first_name") ?: "")
                    put("last_name", o.str("last_name") ?: "")
                    put("email", o.str("email") ?: "")
                    put("phone", o.str("phone") ?: "")
                    put("branch", o.str("branch") ?: "")
                    put("service", o.str("service") ?: "")
                    put("message", o.str("message") ?: "")
                    put("product_id", o.str("product_id"))
                    put("created_at", parseMillis(o.str("created_at")))
                    put("status", o.str("status") ?: "Pending")
                    put("value", o.str("value"))
                }
            }
            db.reconcile("quote_requests", rows)
            if (changedSince("h_quotes", body)) changed = true
        }
        return changed
    }
}