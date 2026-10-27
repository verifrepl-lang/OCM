package app.opencodemob

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Model catalog. The live list is fetched from OpenCode's public catalog
 * (provider "opencode", free models only); a static fallback keeps the app
 * usable offline. "live" models are the free ones proven to answer today —
 * the chat falls through them when a pool is exhausted (HTTP 429).
 */
object ZenModels {
    const val DEFAULT_MODEL = "glm-5.3-flash-free"
    private const val CATALOG = "https://models.opencode.ai/api.json"

    /** Free models that answered on 2026-10-27; order = fallback order. */
    val FALLBACK_FREE = listOf(
        "glm-5.3-flash-free",
        "deepseek-v4.1-flash-free",
        "mimo-v2.6-flash-free",
        "nemotron-3.5-lightning-free",
        "flint-40b-free",
        "nemotron-3.5-super-vl-free",
        "big-pickle",
        "kimi-k2.5-free",
        "qwen3.6-plus-free",
        "ling-3.1-flash-free",
    )

    /** Pretty names for the fallback ids the catalog may not know yet. */
    private val FALLBACK_NAMES = mapOf(
        "glm-5.3-flash-free" to "GLM 5.3 Flash (free)",
        "deepseek-v4.1-flash-free" to "DeepSeek V4.1 Flash (free)",
        "mimo-v2.6-flash-free" to "MiMo V2.6 Flash (free)",
        "nemotron-3.5-lightning-free" to "Nemotron 3.5 Lightning (free)",
        "flint-40b-free" to "Flint 40B (free)",
        "nemotron-3.5-super-vl-free" to "Nemotron 3.5 Super VL (free)",
        "big-pickle" to "Big Pickle (free)",
        "kimi-k2.5-free" to "Kimi K2.5 (free)",
        "qwen3.6-plus-free" to "Qwen 3.6 Plus (free)",
        "ling-3.1-flash-free" to "Ling 3.1 Flash (free)",
        "minimax-m3.1-flash-free" to "MiniMax M3.1 Flash (free)",
        "minimax-m3-free" to "MiniMax M3 (free)",
        "glm-5-free" to "GLM 5 (free)",
        "glm-4.7-free" to "GLM 4.7 (free)",
        "grok-code" to "Grok Code Fast (free)",
        "beam-free" to "Beam (free)",
        "exo-free" to "Exo (free)",
        "hy3-free" to "Hy3 (free)",
        "longcat-2.5-preview-free" to "LongCat 2.5 (free)",
        "ll-alpha-free" to "LL Alpha (free)",
        "space-bunny-free" to "Space Bunny (free)",
        "cat-jam-free" to "Cat Jam (free)",
        "fledge-alpha-free" to "Fledge Alpha (free)",
        "laguna-s-2.1-free" to "Laguna S 2.1 (free)",
        "mimo-v2-pro-free" to "MiMo V2 Pro (free)",
        "mimo-v2-flash-free" to "MiMo V2 Flash (free)",
        "north-mini-code-free" to "North Mini Code (free)",
        "step-5-preview-free" to "Step 5 (free)",
        "deepseek-v4-flash-free" to "DeepSeek V4 Flash (free)",
        "trinity-large-preview-free" to "Trinity Large (free)",
    )

    @Volatile private var catalogCache: JSONArray? = null

    /**
     * Full model list for the picker: [{id, name, free}]. Catalog first,
     * fallback list merged in so nothing disappears when the catalog is stale.
     */
    fun list(activity: MainActivity): JSONArray {
        catalogCache?.let { return it }
        val out = JSONObject()
        fun add(id: String, name: String, free: Boolean) {
            if (out.has(id)) return
            out.put(id, JSONObject().put("id", id).put("name", name).put("free", free))
        }
        try {
            val c = URL(CATALOG).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "OpenCode-Mobile")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val prov = j.optJSONObject("opencode") ?: throw IllegalStateException("no opencode provider")
            val models = prov.optJSONObject("models") ?: throw IllegalStateException("no models")
            val keys = models.keys()
            for (k in keys) {
                val m = models.optJSONObject(k) ?: continue
                val cost = m.optJSONObject("cost")
                val free = k.contains("free", ignoreCase = true) ||
                    (cost != null && cost.optDouble("input", 1.0) == 0.0 && cost.optDouble("output", 1.0) == 0.0)
                add(k, m.optString("name", k), free)
            }
        } catch (_: Exception) { }
        for (id in FALLBACK_FREE) add(id, FALLBACK_NAMES[id] ?: id, true)
        for ((id, name) in FALLBACK_NAMES) add(id, name, true)
        val arr = JSONArray()
        // free models first, then the rest
        val freeArr = mutableListOf<JSONObject>(); val paidArr = mutableListOf<JSONObject>()
        for (k in out.keys()) { val o = out.getJSONObject(k); (if (o.getBoolean("free")) freeArr else paidArr).add(o) }
        freeArr.sortedBy { it.getString("id") }.forEach { arr.put(it) }
        paidArr.sortedBy { it.getString("id") }.forEach { arr.put(it) }
        catalogCache = arr
        return arr
    }

    /** Free model ids in fallback order (catalog free list, fallback appended). */
    fun live(activity: MainActivity): List<String> {
        val arr = list(activity)
        val free = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getBoolean("free")) free.add(o.getString("id"))
        }
        for (id in FALLBACK_FREE) if (!free.contains(id)) free.add(id)
        return free
    }
}
