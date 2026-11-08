package com.goingmerry.gmsingular

import android.content.Context

interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

class SharedPreferencesKeyValueStore(context: Context, name: String) : KeyValueStore {

    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        check(prefs.edit().putString(key, value).commit()) { "Could not write $key" }
    }

    override fun remove(key: String) {
        check(prefs.edit().remove(key).commit()) { "Could not remove $key" }
    }
}
