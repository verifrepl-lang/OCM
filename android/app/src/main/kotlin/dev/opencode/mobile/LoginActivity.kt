package dev.opencode.mobile

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Connects the profile's personal quota: a Console account (device-code sign-in in the browser) or the profile's own
 * Zen API key, pasted right here. With either one every model request is sent under the profile's own credential
 * instead of the world-shared anonymous token. Opened when the person asks for it; skippable with one tap.
 */
class LoginActivity : Activity() {
    private val app: App get() = application as App
    private lateinit var palette: Palette
    private lateinit var status: TextView
    private lateinit var codeBox: LinearLayout
    private lateinit var code: TextView
    private lateinit var buttons: LinearLayout

    private var unsubscribe: (() -> Unit)? = null
    private var attempt: LoginAttempt? = null

    @Volatile
    private var alive = true

    @Volatile
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        palette = Palette(this)

        val column = Ui.vertical(this).apply {
            setPadding(Ui.dp(this@LoginActivity, 24), Ui.dp(this@LoginActivity, 32), Ui.dp(this@LoginActivity, 24), Ui.dp(this@LoginActivity, 24))
        }

        column.addView(Ui.icon(this, R.drawable.ic_user, palette.text, 40), Ui.params(Ui.dp(this, 40), Ui.dp(this, 40), bottom = Ui.dp(this, 16)))
        column.addView(Ui.text(this, tr("Личный аккаунт OpenCode", "Your personal OpenCode account"), 22f, palette.text, bold = true), Ui.params(bottom = Ui.dp(this, 8)))
        column.addView(
            Ui.text(
                this,
                tr(
                    "Регистрация этого устройства — персональный API-ключ. Вставьте свой ключ из консоли OpenCode: каждый запрос пойдёт под ним, и никакой общий лимит на всех пользователей вас не касается. Вход через аккаунт (Google или GitHub) остаётся запасным способом — той же личной квоты. Ключ не обязателен, но без него бесплатные модели делят один лимит на всех.",
                    "Registering this device means a personal API key. Paste your key from the OpenCode console: every request goes under it, and the one limit shared by all users never touches you. Signing in with an account (Google or GitHub) stays as the other way to the same personal quota. A key is optional, but without one the free models share one limit among everyone.",
                ),
                14f,
                palette.muted,
            ),
            Ui.params(bottom = Ui.dp(this, 20)),
        )

        codeBox = Ui.card(this, palette).apply { visibility = View.GONE }
        codeBox.addView(Ui.text(this, tr("Код подтверждения", "Confirmation code"), 12f, palette.faint))
        code = Ui.text(this, "", 28f, palette.text, bold = true, mono = true).apply { setPadding(0, Ui.dp(this@LoginActivity, 6), 0, Ui.dp(this@LoginActivity, 6)) }
        codeBox.addView(code)
        codeBox.addView(Ui.text(this, tr("Если страница спросит код, он совпадает с этим.", "If the page asks for a code, it is this one."), 12f, palette.muted))
        column.addView(codeBox, Ui.params(bottom = Ui.dp(this, 16)))

        status = Ui.text(this, tr("Регистрация устройства: вставьте ключ или войдите в аккаунт.", "Device registration: paste a key or sign in to an account."), 14f, palette.text)
        column.addView(status, Ui.params(bottom = Ui.dp(this, 16)))

        buttons = Ui.vertical(this)
        column.addView(buttons, Ui.params())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(palette.bg)
            isFillViewport = true
            addView(column)
        })

        window.statusBarColor = palette.bg
        window.navigationBarColor = palette.bg

        showButtons()
        ServerService.send(this)
        unsubscribe = ServerState.listen { onState(it) }
    }

    override fun onDestroy() {
        alive = false
        unsubscribe?.invoke()
        super.onDestroy()
    }

    private fun onState(s: Snapshot) {
        when (s.phase) {
            Phase.RUNNING -> if (attempt == null && !running) status.text = tr("Сервер работает. Выберите способ ниже.", "The server is up. Pick a way below.")
            Phase.FAILED -> {
                status.text = tr("Сервер не запустился: ", "The server did not start: ") + s.error.orEmpty()
                showButtons(retry = true)
            }
            else -> status.text = if (s.message.isNotEmpty()) s.message else tr("Ожидание сервера...", "Waiting for the server...")
        }
    }

    private fun begin(s: Snapshot) {
        running = true
        status.text = tr("Запрашиваю страницу входа...", "Requesting the sign-in page...")
        showButtons()

        val login = ConsoleLogin(HttpTransport(s.baseUrl, app.prefs.password))

        Thread({
            try {
                val started = login.start()

                runOnUiThread {
                    attempt = started
                    code.text = started.code.orEmpty()
                    codeBox.visibility = if (started.code != null) View.VISIBLE else View.GONE
                    status.text = tr("Ждём, пока вы войдёте в браузере...", "Waiting for you to sign in in the browser...")
                    showButtons()
                    Browser.open(this, started.url)
                }

                poll(login, started)
            } catch (e: Exception) {
                Log.w("login", "start failed: ${e.message}")
                runOnUiThread {
                    status.text = tr("Не удалось начать вход: ", "Could not start the sign-in: ") + LoginFault.friendly(e.message ?: "")
                    showButtons(retry = true)
                }
            } finally {
                running = false
            }
        }, "login").apply { isDaemon = true; start() }
    }

    private fun poll(login: ConsoleLogin, started: LoginAttempt) {
        var errors = 0

        while (alive) {
            try {
                Thread.sleep(2000)

                when (val result = login.status(started)) {
                    LoginStatus.Complete -> {
                        runOnUiThread { done() }

                        return
                    }
                    LoginStatus.Expired -> {
                        runOnUiThread {
                            attempt = null
                            codeBox.visibility = View.GONE
                            status.text = tr("Время входа истекло. Начните заново.", "The sign-in timed out. Start again.")
                            showButtons(retry = true)
                        }

                        return
                    }
                    is LoginStatus.Failed -> {
                        runOnUiThread {
                            attempt = null
                            codeBox.visibility = View.GONE
                            status.text = if (result.message.contains("access_denied")) {
                                tr("Вход отклонён на странице.", "The sign-in was declined on the page.")
                            } else {
                                tr("Вход не удался: ", "Sign-in failed: ") + result.message
                            }
                            showButtons(retry = true)
                        }

                        return
                    }
                    LoginStatus.Pending -> errors = 0
                }
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                // A few failed polls in a row are normal while the phone switches apps; keep trying.
                if (++errors > 15) {
                    runOnUiThread {
                        attempt = null
                        status.text = tr("Потеряна связь с сервером: ", "Lost the connection to the server: ") + (e.message ?: "")
                        showButtons(retry = true)
                    }

                    return
                }
            }
        }
    }

    private fun done() {
        app.prefs.signedIn = true
        // The profile has a personal Console account now: record it before the sync names the account, so the
        // quota hints switch from "the shared bucket" to "your own limit" right away.
        app.guest.markPersonal(app.profiles.active.id, app.profiles.active.account)
        UiBus.reloadRequested = true
        app.syncAccountAsync()
        // The running server baked the shared anonymous token into the provider while it came up without an
        // account. The sign-in re-resolves that live, but if its one Console fetch fails, the previously
        // chosen shared token stays on the provider and every request - a fresh account's included - still
        // falls into the world bucket. Restarting, the same thing the API-key path does, makes the very next
        // request go under the account for sure.
        ServerService.send(this, ServerService.ACTION_RESTART)
        Ui.toast(this, tr("Вход выполнен", "Signed in"))
        finish()
    }

    /** Asks for the profile's own Zen API key and applies it: personal quota without a Console sign-in. */
    private fun askForKey() = ApiKey.ask(this, close = true)

    private fun showButtons(retry: Boolean = false) {
        buttons.removeAllViews()

        val current = attempt

        if (retry) {
            buttons.addView(
                Ui.button(this, tr("Повторить", "Try again"), palette, primary = true) {
                    attempt = null

                    val s = ServerState.snapshot

                    if (s.phase == Phase.RUNNING) begin(s) else ServerService.send(this, ServerService.ACTION_RESTART)
                },
                Ui.params(bottom = Ui.dp(this, 8)),
            )
        }

        if (current != null) {
            buttons.addView(
                Ui.button(this, tr("Открыть страницу входа", "Open the sign-in page"), palette, primary = true) { Browser.open(this, current.url) },
                Ui.params(bottom = Ui.dp(this, 8)),
            )
            buttons.addView(
                Ui.button(this, tr("Другой аккаунт", "Another account"), palette) { Browser.open(this, current.url, private = true) },
                Ui.params(bottom = Ui.dp(this, 8)),
            )
        }

        // Registration is the key first: the primary action pastes the profile's own credential, no account needed.
        buttons.addView(
            Ui.button(this, tr("У меня есть API-ключ", "I have an API key"), palette, primary = true) { askForKey() },
            Ui.params(bottom = Ui.dp(this, 8)),
        )

        buttons.addView(
            Ui.button(this, tr("Войти через браузер (Google или GitHub)", "Sign in via the browser (Google or GitHub)"), palette) {
                val s = ServerState.snapshot

                if (s.phase == Phase.RUNNING) begin(s) else ServerService.send(this, ServerService.ACTION_RESTART)
            },
            Ui.params(bottom = Ui.dp(this, 8)),
        )

        val hasKey = app.profiles.active.zenApiKey != null

        if (hasKey) {
            buttons.addView(
                Ui.button(this, tr("Убрать сохранённый ключ", "Remove the saved key"), palette) { ApiKey.remove(this) },
                Ui.params(bottom = Ui.dp(this, 8)),
            )
        }

        buttons.addView(Ui.button(this, tr("Отмена", "Cancel"), palette) { finish() }, Ui.params())

        buttons.gravity = Gravity.CENTER_HORIZONTAL
    }
}
