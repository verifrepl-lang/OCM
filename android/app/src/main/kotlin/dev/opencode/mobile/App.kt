package dev.opencode.mobile

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle

class App : Application() {
    lateinit var paths: Paths
        private set

    lateinit var prefs: Prefs
        private set

    lateinit var profiles: Profiles
        private set

    lateinit var guest: GuestAccounts
        private set

    lateinit var runtime: RuntimeInstaller
        private set

    lateinit var ota: OtaManager
        private set

    lateinit var vpn: VpnSubscriptionManager
        private set

    private var started = 0

    /** True while any of the app's screens is visible. Notifications are only needed when it is not. */
    val inForeground: Boolean get() = started > 0

    override fun onCreate() {
        super.onCreate()

        instance = this
        Log.setStage("Application.onCreate: detecting process")

        // The crash screen runs in its own process, which also creates this class. It must stay free of the app's setup.
        if (processName().endsWith(":crash")) return

        // Install this before any filesystem/preferences setup so early startup failures are captured too.
        Log.installCrashHandler(this)
        Log.setStage("Application.onCreate: initializing private paths")
        paths = Paths(this)
        paths.ensure()
        Log.setStage("Application.onCreate: initializing app log")
        Log.init(paths.logs)
        Log.setStage("Application.onCreate: initializing preferences")
        prefs = Prefs(this)
        Log.setStage("Application.onCreate: loading profiles")
        profiles = Profiles(paths.profilesFile)
        Log.setStage("Application.onCreate: loading guest accounts")
        guest = GuestAccounts(paths.guestsFile)
        Log.setStage("Application.onCreate: initializing runtime installer")
        runtime = RuntimeInstaller(this, paths)
        Log.setStage("Application.onCreate: initializing OTA state")
        ota = OtaManager(this, paths, prefs)
        Log.setStage("Application.onCreate: initializing VPN subscriptions")
        vpn = VpnSubscriptionManager(paths, prefs)
        // The build can carry a default node list (assets/vpn-default-nodes.txt, filled from repo secrets); it is
        // imported once on its own thread, so a cold start never waits on it.
        Thread({ runCatching { vpn.seedDefaults(this) } }, "vpn-defaults").apply { isDaemon = true; start() }
        // Update checks fall back to the running tunnel when GitHub cannot be reached directly.
        ota.fallbackProxies = {
            listOfNotNull(
                vpn.proxyUrl.takeIf { vpn.listening() },
            )
        }
        // A device with a broken notification service must still let the app open; startForeground failures are
        // handled where they happen.
        Log.setStage("Application.onCreate: initializing notifications")
        runCatching { Notifier.init(this) }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) { started-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        Log.i("app", "started ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), server ${ota.current}")
        Log.setStage("Application initialized")
    }

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName() ?: ""
        } else {
            runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000') }.getOrDefault("")
        }

    /**
     * Reads the signed-in Console account from the running server and records it as the active profile's name.
     *
     * The lookup is careful in both directions. While the server warms up its registry it answers without the
     * integration; treating that as "nobody is signed in" once wiped the account name and put a just-signed-in
     * profile back onto the shared bucket, so every limit error then claimed the person had no account (fresh
     * Console accounts included). The saved state may only change when the lookup itself has completed.
     */
    fun syncAccountAsync() {
        val snap = ServerState.snapshot

        if (snap.phase != Phase.RUNNING || snap.profileId.isEmpty() || prefs.remote) return

        // A permanent failure (e.g. a server build without the integration) must not be retried on every server
        // restart and every onResume - that is where the every-minute "sync failed" spam in user logs came from.
        if (snap.generation == accountSyncFailedFor) return

        Thread({
            var generation = snap.generation

            runCatching {
                repeat(3) { n ->
                    // The server can restart mid-sync; always talk to the current run or exit.
                    val now = ServerState.snapshot

                    if (now.phase != Phase.RUNNING || now.generation != generation) return@Thread

                    try {
                        // A short warmup: the sync is background work, so it must not hold a thread for minutes
                        // waiting for the plugin activation. If it is still warming up, the next onResume sync
                        // finds the integration already registered and reads the account.
                        val login = ConsoleLogin(HttpTransport(now.baseUrl, prefs.password), warmupAttempts = 3)
                        val info = login.info() ?: return@Thread

                        // Connections read newest first (the upstream list is reversed), so the first one is whose
                        // tokens are active - that is the account actually spending its quota.
                        val accounts = login.signedInAccounts(info)

                        if (accounts.isNotEmpty()) {
                            // The integration is registered and stores a sign-in: name the profile after it.
                            profiles.setAccount(snap.profileId, accounts.first().label)
                            prefs.signedIn = true
                            // The profile runs on its own Console account now: its quota is its own, not the
                            // world-shared anonymous bucket.
                            guest.markPersonal(snap.profileId, accounts.first().label)
                        } else {
                            // The integration answered with no stored sign-in: a fact (a real sign-out),
                            // not a warm-up race, so it is safe to record.
                            profiles.setAccount(snap.profileId, null)
                            guest.markAnonymous(snap.profileId)
                        }

                        return@Thread
                    } catch (e: Exception) {
                        val message = e.message ?: e.javaClass.simpleName

                        Log.w("account", "sync failed: $message")

                        // Deterministic errors (missing integration, bad credentials) will never succeed; park this
                        // server generation instead of hammering the endpoint every restart.
                        if (!LoginFault.isRetryable(message) || n == 2) {
                            accountSyncFailedFor = generation

                            return@Thread
                        }

                        Thread.sleep(3000)

                        if (ServerState.snapshot.generation != generation) return@Thread

                        generation = ServerState.snapshot.generation
                    }
                }
            }
        }, "account-sync").apply { isDaemon = true; start() }
    }

    /** Server generation whose account sync ended in a permanent failure; no retry until the server restarts anew. */
    @Volatile
    private var accountSyncFailedFor = -1

    companion object {
        lateinit var instance: App
            private set
    }
}
