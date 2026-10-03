package com.woodlands.mobile

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LocalDb(context: Context) : SQLiteOpenHelper(context, "woodlands_mobile.db", null, 4) {

    companion object {
        @Volatile private var instance: LocalDb? = null

        fun shared(context: Context): LocalDb =
            instance ?: synchronized(this) {
                instance ?: LocalDb(context.applicationContext).also { instance = it }
            }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        createTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        listOf("products", "services", "testimonials", "faqs", "branches", "quote_requests", "contact_submissions", "users", "outbox")
            .forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        createTables(db)
    }

    private fun createTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE products(id INTEGER PRIMARY KEY AUTOINCREMENT, remote_id TEXT UNIQUE, category TEXT, title TEXT, tagline TEXT, description TEXT, image TEXT, gallery TEXT, features TEXT, finishes TEXT, lead_time TEXT, tag TEXT, price TEXT)")
        db.execSQL("CREATE TABLE testimonials(id INTEGER PRIMARY KEY AUTOINCREMENT, remote_id TEXT UNIQUE, name TEXT, role TEXT, location TEXT, rating INTEGER, review TEXT, project TEXT)")
        db.execSQL("CREATE TABLE faqs(id INTEGER PRIMARY KEY AUTOINCREMENT, remote_id TEXT UNIQUE, category TEXT, question TEXT, answer TEXT)")
        db.execSQL("CREATE TABLE branches(id INTEGER PRIMARY KEY AUTOINCREMENT, remote_id TEXT UNIQUE, name TEXT, region TEXT, phone TEXT, hours TEXT, notes TEXT)")
        db.execSQL("CREATE TABLE quote_requests(id INTEGER PRIMARY KEY AUTOINCREMENT, remote_id TEXT UNIQUE, quote_code TEXT, first_name TEXT, last_name TEXT, email TEXT, phone TEXT, branch TEXT, service TEXT, message TEXT, product_id TEXT, created_at INTEGER, status TEXT DEFAULT 'Pending', value TEXT)")
        db.execSQL("CREATE TABLE contact_submissions(id INTEGER PRIMARY KEY AUTOINCREMENT, first_name TEXT, last_name TEXT, email TEXT, phone TEXT, branch TEXT, service TEXT, message TEXT, created_at INTEGER)")
        db.execSQL("CREATE TABLE users(id TEXT PRIMARY KEY, full_name TEXT, email TEXT, phone TEXT, role TEXT, branch TEXT, active INTEGER DEFAULT 1, created_at INTEGER)")
        db.execSQL("CREATE TABLE outbox(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, method TEXT, path TEXT, body TEXT, local_ref TEXT, attempts INTEGER DEFAULT 0)")
    }
}