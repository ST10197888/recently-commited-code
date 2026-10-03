package com.woodlands.mobile

import android.content.Context

class Session(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("woodlands_mobile_session", Context.MODE_PRIVATE)

    var userId: String?
        get() = prefs.getString("user_id", null)
        set(value) { prefs.edit().putString("user_id", value).apply() }

    val isLoggedIn: Boolean get() = userId != null

    fun clear() {
        prefs.edit().remove("user_id").apply()
        LocalDb.shared(appContext).clearPrivateData()
        SyncManager.forgetPrivateHashes()
    }
}