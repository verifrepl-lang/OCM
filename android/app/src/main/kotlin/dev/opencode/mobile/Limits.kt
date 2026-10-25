package dev.opencode.mobile

/**
 * Recognises "the anonymous free-model limit for this IP is used up" in what the server reports, so a spent quota can
 * be answered with a fresh exit address (see ServerService: it rotates the VPN subscription node and restarts) instead
 * of being mistaken for a network failure or ignored until the person notices the error themselves.
 *
 * Two things must line up before anything rotates: the payload has to be an ERROR answer (a failed run, an assistant
 * message that finished with an error object), and its text has to talk about a limit. A person merely typing
 * "rate limit exceeded" into the chat never matches, because their words arrive in ordinary text parts with no error
 * around them.
 */
object Limits {
    private val TEXT = Regex(
        "rate[\\s_-]?limit|too[\\s_-]?many[\\s_-]?requests|\\b429\\b|usage[\\s_-]?limit|free[\\s_-]?usage|" +
                "insufficient[\\s_-]?quota|quota[\\s_-]?exceeded|freeusagelimit|gousagelimit|creditlimit|" +
                "free[\\s_-]?usage[\\s_-]?limit[\\s_-]?exceeded" +
                "|квот[а-яё]*\\s[^\"\\n]{0,80}?(?:исчерпан|превышен)|лимит[а-яё]*\\s[^\"\\n]{0,80}?(?:исчерпан|превышен|закончил)",
        RegexOption.IGNORE_CASE,
    )

    private val FAILURE_EVENTS = setOf("session.step.failed", OcEvent.SESSION_FAILED)

    /** The final say of a failed run: an assistant message that completed carrying an error object. */
    private const val MESSAGE_UPDATED = "message.updated"

    fun isLimit(text: String): Boolean = TEXT.containsMatchIn(text)

    /** True for a failure event, or a retry status, whose payload talks about a limit. */
    fun carriesLimit(type: String, raw: String): Boolean {
        val relevant = type in FAILURE_EVENTS || (type == "session.status" && raw.contains("retry", ignoreCase = true))

        return relevant && isLimit(raw)
    }

    /**
     * True when this looks like an assistant answer that died on a spent limit: an error object named like a provider
     * API failure is present and its text talks about quotas. Ordinary chat text can mention the words, but it never
     * arrives inside `"error":{"name":"APIError"` the way a failed provider call does.
     */
    fun failedOnLimit(type: String, raw: String): Boolean {
        if (type == MESSAGE_UPDATED) {
            val isErrorAnswer = raw.contains("\"error\":") && raw.contains("APIError")

            return isErrorAnswer && isLimit(raw)
        }

        return carriesLimit(type, raw)
    }
}
