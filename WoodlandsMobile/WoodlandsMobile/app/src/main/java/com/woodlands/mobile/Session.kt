package com.woodlands.mobile

import android.content.Context

class Session(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("woodlands_mobile_session", Context.MODE_PRIVATE)

    var userId: String?
        get() = prefs.getString("user_id", null)
        set(value) { prefs.edit().putString("user_id", value).apply() }

    // JWT issued by the API at login. Also handed to ApiClient so every request is authenticated.
    var token: String?
        get() = prefs.getString("api_token", null)
        set(value) {
            prefs.edit().putString("api_token", value).apply()
            ApiClient.authToken = value
        }

    val isLoggedIn: Boolean get() = userId != null

    // Loads the saved token into ApiClient (called when the app starts).
    fun restoreToken() {
        ApiClient.authToken = token
    }

    fun clear() {
        prefs.edit().remove("user_id").remove("api_token").apply()
        ApiClient.authToken = null
        LocalDb.shared(appContext).clearPrivateData()
        SyncManager.forgetPrivateHashes()
    }
}