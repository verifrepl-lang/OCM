package app.opencodemob

import android.content.Context

/** Tiny settings store (SharedPreferences). The chat key and model choice live here. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("oc", Context.MODE_PRIVATE)
    fun get(c: Context, k: String, d: String = ""): String = sp(c).getString(k, d) ?: d
    fun put(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    fun flag(c: Context, k: String, d: Boolean = true): Boolean = sp(c).getBoolean(k, d)
    fun putFlag(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
}
