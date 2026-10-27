package app.opencodemob

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Direct client for the OpenCode free tier ("Zen") anonymous lane, the same
 * wire protocol the opencode2api gateway speaks:
 *
 *   POST https://opencode.ai/inference/openai/v1/chat/completions
 *   Authorization: Bearer public            (the literal credential, case-sensitive)
 *   User-Agent: opencode/latest/2.1.1/cli   (v2 gate fingerprints this shape)
 *   x-opencode-client: cli
 *   x-opencode-session / x-opencode-session-id / x-session-affinity / X-Session-Id:
 *       canonical session id "ses_" + 12 hex + 14 base62 where the 12 hex chars
 *       are a DESCENDING millisecond timestamp (the low 12 bits inverted, so a
 *       counter-1 id always ends in "ffe"). A wrong shape or an implausible
 *       date answers 403/429 FreeTierError even when quota is available.
 *   x-opencode-project: fresh random 40-hex per request (the free tier ledges
 *       its quota per project id, a shared id arrives pre-burned).
 *
 * The response is standard OpenAI chat-completions SSE with interleaved
 * reasoning in delta.reasoning_content. Free models each keep their own quota
 * pool per egress IP, so on 429 the client falls through the model chain.
 */
object ZenClient {
    const val BASE = "https://opencode.ai/inference/openai/v1"
    private const val UA = "opencode/latest/2.1.1/cli"
    private const val ANON_KEY = "public"

    private val jobs = ConcurrentHashMap<String, Thread>()
    private val conns = ConcurrentHashMap<String, HttpURLConnection>()

    @Volatile var promptCache: String? = null

    // ---------- identity ----------

    private const val B62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    /** 14-char base62 rendering of a 10-byte hash slice. */
    private fun base62(n: Long, width: Int): String {
        var v = n
        val out = CharArray(width)
        for (i in width - 1 downTo 0) { out[i] = B62[(v % 62).toInt()]; v /= 62 }
        return String(out)
    }

    /**
     * Canonical session id: ses_ + descending timestamp + 14 base62. The
     * timestamp is today's UTC midnight minus a hash-derived offset inside a
     * 30-day window, shifted into the top of a 48-bit word, OR-ed with counter
     * 1 and bitwise-NOT-ed — exactly the layout the official client mints and
     * the free tier validates.
     */
    fun canonicalSessionId(signal: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val h = md.digest(("ses\u0000" + signal).toByteArray())
        val window = 30L * 24 * 3600 * 1000
        var seed: Long = 0
        for (i in 0 until 4) seed = (seed shl 8) or (h[i].toLong() and 0xFF)
        val offset = (seed and 0x7FFFFFFFL) % window
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        val baseMs = f.parse(f.format(Date())).time
        val current = (baseMs - offset) shl 12 or 1L
        val inverted = current.inv() and 0xFFFFFFFFFFFFL
        var r: Long = 0
        for (i in 6 until 14) r = (r shl 8) or (h[i].toLong() and 0xFF)
        return "ses_" + String.format("%012x", inverted) + base62(r, 14)
    }

    private fun freshProjectId(): String {
        val buf = ByteArray(20)
        java.security.SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    // ---------- request ----------

    /**
     * Starts a streaming chat. payload: {id, model, messages:[{role,content}],
     * key?}. Falls through the free-model chain on 429/403/5xx/400-unavailable
     * when the caller did not pin a key. Events go to emit(name, json):
     *   chat {id, delta}            text chunk
     *   chat {id, reasoning}        reasoning chunk
     *   chat {id, model}            model that actually started streaming
     *   chat {id, done, usage?}     stream finished
     *   chat {id, error, status?}   every model in the chain failed
     */
    fun chat(activity: MainActivity, payload: String) {
        val p = try { JSONObject(payload) } catch (e: Exception) { JSONObject() }
        val id = p.optString("id", "c" + System.currentTimeMillis())
        val model = p.optString("model", ZenModels.DEFAULT_MODEL)
        val key = p.optString("key", "").trim()
        val messages = p.optJSONArray("messages") ?: JSONArray()
        cancel(id)
        val t = thread(start = false, name = "zen-$id") {
            var lastErr = ""
            var lastStatus = 0
            val chain = buildChain(activity, model, key.isEmpty())
            for (m in chain) {
                // fresh identity per attempt: never reuse a session that already saw an error
                val session = canonicalSessionId("ocm-" + id + "-" + m + "-" + System.nanoTime())
                val conn = try {
                    open(m, session, freshProjectId(), key, body(m, messages, activity))
                } catch (e: Exception) {
                    lastErr = e.message ?: e.toString(); lastStatus = 0; continue
                }
                conns[id] = conn
                try {
                    val code = conn.responseCode
                    if (code in 200..299) {
                        activity.emit("chat", JSONObject().put("id", id).put("model", m))
                        stream(conn, id, activity)
                        return@thread
                    }
                    val errText = try { conn.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null } ?: ""
                    lastStatus = code
                    lastErr = humanError(code, errText)
                    // 4xx that a model switch cannot fix: 401 (bad key), 413, 422...
                    if (code == 401 || code == 413 || code == 422) break
                    if (code == 400 && errText.contains("FreeTierError")) break
                } catch (e: Exception) {
                    if (jobs[id] == null) return@thread // cancelled
                    lastErr = e.message ?: e.toString(); lastStatus = 0
                } finally {
                    try { conn.disconnect() } catch (_: Exception) {}
                    conns.remove(id, conn)
                }
            }
            activity.emit("chat", JSONObject().put("id", id).put("error", lastErr.ifEmpty { "Сервер не ответил" }).put("status", lastStatus))
        }
        jobs[id] = t
        t.start()
    }

    fun cancel(id: String) {
        jobs.remove(id)?.interrupt()
        conns.remove(id)?.let { try { it.disconnect() } catch (_: Exception) {} }
    }

    fun shutdownAll() {
        for (k in jobs.keys) cancel(k)
    }

    /** [selected, otherLive free models...] for anonymous requests. */
    private fun buildChain(activity: MainActivity, model: String, freeOnly: Boolean): List<String> {
        val chain = mutableListOf(model)
        if (freeOnly) {
            val live = ZenModels.live(activity)
            for (m in live) if (m != model && !chain.contains(m)) chain.add(m)
        }
        return chain.take(6)
    }

    private fun body(model: String, messages: JSONArray, activity: MainActivity): ByteArray {
        val prompt = promptCache ?: run {
            val t = activity.assets.open("opencode_prompt_v2.txt").bufferedReader().use { it.readText() }
            promptCache = t; t
        }
        // The free tier only accepts agent-shaped traffic: it wants the
        // official CLI system prompt ahead of the conversation.
        val arr = JSONArray()
        var hasSystem = false
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") == "system") hasSystem = true
            arr.put(m)
        }
        if (!hasSystem) arr.put(0, JSONObject().put("role", "system").put("content", prompt))
        val b = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("max_completion_tokens", 32000)
            .put("messages", arr)
        return b.toString().toByteArray(Charsets.UTF_8)
    }

    private fun open(model: String, session: String, project: String, key: String, bodyBytes: ByteArray): HttpURLConnection {
        val c = URL("$BASE/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        // HTTP/1.1 like the official client: the h2 path is fingerprinted less predictably
        c.setRequestProperty("Accept", "*/*")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("x-opencode-client", "cli")
        c.setRequestProperty("x-session-affinity", session)
        c.setRequestProperty("X-Session-Id", session)
        c.setRequestProperty("x-opencode-session", session)
        c.setRequestProperty("x-opencode-session-id", session)
        c.setRequestProperty("x-opencode-project", project)
        c.setRequestProperty("Authorization", "Bearer " + (key.ifEmpty { ANON_KEY }))
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 120_000
        c.setChunkedStreamingMode(8192)
        c.outputStream.use { it.write(bodyBytes) }
        return c
    }

    private fun stream(conn: HttpURLConnection, id: String, activity: MainActivity) {
        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        var usage: JSONObject? = null
        while (true) {
            val line = try { reader.readLine() } catch (e: Exception) { null } ?: break
            if (jobs[id] == null) break // cancelled
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data == "[DONE]") break
            val j = try { JSONObject(data) } catch (_: Exception) { continue }
            j.optJSONObject("error")?.let { e ->
                val msg = e.optString("message", "Ошибка потока")
                activity.emit("chat", JSONObject().put("id", id).put("error", msg))
                return
            }
            j.optJSONObject("usage")?.let { usage = it }
            val choices = j.optJSONArray("choices") ?: continue
            for (i in 0 until choices.length()) {
                val delta = choices.optJSONObject(i)?.optJSONObject("delta") ?: continue
                val rc = delta.optString("reasoning_content", "")
                if (rc.isNotEmpty()) activity.emit("chat", JSONObject().put("id", id).put("reasoning", rc))
                val c = delta.optString("content", "")
                if (c.isNotEmpty()) activity.emit("chat", JSONObject().put("id", id).put("delta", c))
            }
        }
        val done = JSONObject().put("id", id).put("done", true)
        usage?.let { done.put("usage", it) }
        activity.emit("chat", done)
    }

    private fun humanError(code: Int, text: String): String {
        val detail = try {
            val j = JSONObject(text)
            val e = j.optJSONObject("error")
            e?.optString("message") ?: j.optString("message")
        } catch (_: Exception) { "" }
        return when {
            code == 401 -> "Ключ отклонён сервером (401). Проверьте ключ в настройках"
            code == 429 -> "Лимиты этой сети исчерпаны для всех бесплатных моделей. Смените сеть (Wi-Fi ↔ мобильный интернет, 10 сек авиарежима) или введите ключ opencode Zen"
            code == 403 && detail.contains("country", true) -> "Модель недоступна в вашей стране, переключаюсь на другую…"
            code >= 500 -> "Сервер OpenCode временно недоступен ($code)"
            detail.isNotEmpty() -> detail
            else -> "HTTP $code"
        }
    }
}
