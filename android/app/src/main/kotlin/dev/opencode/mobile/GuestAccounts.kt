package dev.opencode.mobile

import java.io.File
import java.security.SecureRandom

/**
 * The guest account system, rebuilt from scratch on the ground truth verified against the live OpenCode gateway
 * (October 2026):
 *
 *  - The free (anonymous) capacity of the Zen gateway is ONE bucket shared by every OpenCode user in the world.
 *    It is spent through `Authorization: Bearer public`, and nothing an install can choose - not the IP address,
 *    not the User-Agent, not a fresh install - moves the needle: a brand-new IP with zero usage history is answered
 *    `429 FreeUsageLimitError` exactly the same. A fresh install is NOT a fresh allowance.
 *  - A quota of one's own exists only for a personal OpenCode Console account (OAuth through Google or GitHub) or a
 *    paid plan. That is what "a personal guest account" means in practice, so this system does two things: it gives
 *    every profile an identity of its own for local bookkeeping (never shared, never reused), and it walks every
 *    person to their personal Console account - the only thing that actually separates one phone's quota from the
 *    world's shared bucket.
 *
 * The store is a small JSON file per profile set, Android-free, so the rules can be tested on the JVM.
 */
enum class GuestState {
    /** No Console account connected: requests fall into the world-shared anonymous bucket. */
    ANONYMOUS,

    /** A personal Console account is connected: requests carry its token and its personal quota. */
    PERSONAL,
}

data class GuestAccount(
    val profileId: String,
    /** The install-local identity of this guest: generated once per profile, never shared or reused. */
    val identity: String,
    /** A short human label for logs, diagnostics and widgets ("Guest a1b2"). */
    val label: String,
    val state: GuestState,
    /** The Console account label (usually an email) once a personal account is connected. */
    val account: String?,
    val updatedAt: Long,
)

class GuestAccounts(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val items = HashMap<String, GuestAccount>()

    init {
        load()
    }

    /** The guest account of [profileId], created on first use. Every profile gets one of its own. */
    fun identity(profileId: String): GuestAccount = synchronized(this) {
        items[profileId]?.let { return@synchronized it }

        val suffix = newSuffix()
        val account = GuestAccount(
            profileId = profileId,
            identity = "guest_$suffix",
            label = tr("Гость ", "Guest ") + suffix,
            state = GuestState.ANONYMOUS,
            account = null,
            updatedAt = clock(),
        )

        items[profileId] = account
        save()

        account
    }

    /** True when this profile runs on its own Console account rather than the shared anonymous bucket. */
    fun isPersonal(profileId: String): Boolean = synchronized(this) {
        items[profileId]?.state == GuestState.PERSONAL
    }

    /** The Console account label of the profile, or null while it sits on the shared bucket. */
    fun account(profileId: String): String? = synchronized(this) {
        items[profileId]?.account
    }

    /** Records that the profile connected a personal Console account (the sign-in completed). */
    fun markPersonal(profileId: String, account: String?) {
        synchronized(this) {
            val current = identity(profileId)

            items[profileId] = current.copy(state = GuestState.PERSONAL, account = account, updatedAt = clock())
            save()
        }
    }

    /** Records that the profile is back on the shared bucket (the account was removed or signed out). */
    fun markAnonymous(profileId: String) {
        synchronized(this) {
            val current = identity(profileId)

            items[profileId] = current.copy(state = GuestState.ANONYMOUS, account = null, updatedAt = clock())
            save()
        }
    }

    private fun load() {
        if (!file.exists()) return

        val root = runCatching { MiniJson.obj(file.readText()) }.getOrNull() ?: return
        val list = root["guests"] as? List<*> ?: return

        for (value in list) {
            val map = value as? Map<*, *> ?: continue
            val profileId = map["profileId"] as? String ?: continue
            val identity = map["identity"] as? String ?: continue

            if (!IDENTITY.matches(identity)) continue

            items[profileId] = GuestAccount(
                profileId = profileId,
                identity = identity,
                label = (map["label"] as? String)?.takeIf { it.isNotBlank() } ?: identity,
                state = if (map["state"] == "personal") GuestState.PERSONAL else GuestState.ANONYMOUS,
                account = map["account"] as? String,
                updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L,
            )
        }
    }

    private fun save() {
        val out = StringBuilder("{\"guests\":[")

        items.values.sortedBy { it.profileId }.forEachIndexed { i, g ->
            if (i > 0) out.append(',')
            out.append("{\"profileId\":").append(MiniJson.quote(g.profileId))
            out.append(",\"identity\":").append(MiniJson.quote(g.identity))
            out.append(",\"label\":").append(MiniJson.quote(g.label))
            out.append(",\"state\":").append(MiniJson.quote(if (g.state == GuestState.PERSONAL) "personal" else "anonymous"))
            out.append(",\"account\":").append(if (g.account == null) "null" else MiniJson.quote(g.account))
            out.append(",\"updatedAt\":").append(g.updatedAt).append('}')
        }

        out.append("]}")
        file.parentFile?.mkdirs()

        // Write beside the target and rename, so a crash never leaves a half-written store.
        val temp = File(file.parentFile, file.name + ".tmp")

        temp.writeText(out.toString())

        if (!temp.renameTo(file)) {
            file.writeText(out.toString())
            temp.delete()
        }
    }

    companion object {
        private val IDENTITY = Regex("^guest_[a-z0-9]{6}$")

        fun newSuffix(): String {
            val raw = ByteArray(3)

            SecureRandom().nextBytes(raw)

            return raw.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Turns what the server says about a failed request into the honest reason, so the app can name the right fix
 * instead of a generic "quota error". The texts mirror what the gateway actually sends (verified live):
 * `FreeUsageLimitError`, `RegionError` and the personal-account limits.
 */
object QuotaSignal {
    enum class Reason {
        /** The world-shared anonymous free bucket is drained. A personal account is the only way out. */
        FREE_BUCKET_SHARED,

        /** The same, but for a profile that runs its own account: the personal allowance is spent. */
        PERSONAL_LIMIT,

        /** The model is not served to this country at all - no account changes that. */
        REGION_BLOCKED,

        /** A plain rate limit: wait, or pick another model. */
        RATE_LIMIT,

        /** Something else; show it as it is. */
        OTHER,
    }

    /**
     * Reads a server error (the message and, when known, the session-error type such as `provider.free-limit`)
     * into a reason. [hasAccount] separates the shared bucket from a personal allowance: both surface as
     * free-usage errors, but they mean different things and have different fixes.
     */
    fun decode(message: String, type: String? = null, hasAccount: Boolean): Reason {
        val text = ((type ?: "") + " " + message).lowercase()

        if ("regionerror" in text || "not available in your country" in text) return Reason.REGION_BLOCKED

        if ("freeusagelimit" in text || "free usage limit" in text || "free-limit" in text || "free usage" in text) {
            return if (hasAccount) Reason.PERSONAL_LIMIT else Reason.FREE_BUCKET_SHARED
        }

        if ("rate limit" in text || "ratelimit" in text || "429" in text || "too many requests" in text) return Reason.RATE_LIMIT

        if ("quota" in text || "usage limit" in text || "лимит" in text || "квот" in text) {
            return if (hasAccount) Reason.PERSONAL_LIMIT else Reason.FREE_BUCKET_SHARED
        }

        return Reason.OTHER
    }

    /** The honest one-liner for [reason]: what happened and what actually helps. Shown under the server's own text. */
    fun explanation(reason: Reason): String = when (reason) {
        Reason.FREE_BUCKET_SHARED -> tr(
            "Без аккаунта бесплатные модели - общий лимит на всех пользователей OpenCode в мире, и он обычно уже исчерпан. Свой лимит даёт только личный аккаунт: подключите его одной кнопкой ниже.",
            "Without an account the free models share one limit across all OpenCode users worldwide, and it is usually spent already. Only a personal account brings a limit of your own: connect it with the button below.",
        )
        Reason.PERSONAL_LIMIT -> tr(
            "Это лимит вашего личного аккаунта, а не общий. Подождите сброса, выберите другую модель или пополните план в OpenCode.",
            "This is your personal account's limit, not the shared one. Wait for the reset, pick another model, or top up your OpenCode plan.",
        )
        Reason.REGION_BLOCKED -> tr(
            "Модель не обслуживается в вашей стране - это ограничение сервера, аккаунт его не снимает. Выберите другую модель.",
            "The model is not served in your country - a server-side restriction no account removes. Pick another model.",
        )
        Reason.RATE_LIMIT -> tr(
            "Слишком много запросов подряд. Подождите немного и попробуйте снова, либо выберите другую модель.",
            "Too many requests in a row. Wait a little and try again, or pick another model.",
        )
        Reason.OTHER -> tr(
            "Это сообщение сервера; приложение показывает его как есть.",
            "This is the server's own message; the app shows it as it is.",
        )
    }
}
