package com.woodlands.mobile

import android.content.Context

/** Keeps track of which local account is signed in, the same way the site keeps an auth cookie. */
class Session(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("woodlands_mobile_session", Context.MODE_PRIVATE)

    var userId: String?
        get() = prefs.getString("user_id", null)
        set(value) { prefs.edit().putString("user_id", value).apply() }

    val isLoggedIn: Boolean get() = userId != null

    fun clear() { prefs.edit().remove("user_id").apply() }
}
