package app.stringalize

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Live updates for the strings of an Android app.
 *
 * Call [start] once in `Application.onCreate`, and wrap each Activity's base context with [wrap].
 * Releases published in Stringalize are downloaded in the background and used from the next launch.
 */
object Stringalize {
    const val DEFAULT_SERVER = "https://stringalize.app"

    /** Called on the main thread when a release has been downloaded. */
    fun interface ReleaseListener {
        fun onReleaseDownloaded(release: Int)
    }

    /** Called on the main thread with `true` when a newer release was downloaded. */
    fun interface UpdateCallback {
        fun onResult(downloaded: Boolean)
    }

    // Settable inside the module so tests can install an engine.
    @Volatile
    internal var engine: Engine? = null

    private val listeners = CopyOnWriteArrayList<ReleaseListener>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Stringalize").apply { isDaemon = true } }

    /**
     * Starts live updates. Later calls are ignored.
     *
     * @param token the project's live updates token, starting with `ota_`.
     * @param applyImmediately use a new release as soon as it is downloaded, instead of from the next launch.
     *   Text already on screen does not change until it is set again.
     * @param server the address of the Stringalize server.
     */
    @JvmStatic
    @JvmOverloads
    fun start(context: Context, token: String, applyImmediately: Boolean = false, server: String = DEFAULT_SERVER) {
        val app = context.applicationContext ?: context
        synchronized(this) {
            if (engine != null) return
            val engine = Engine(
                directory = File(app.filesDir, "stringalize/${Engine.hash(token)}"),
                token = token,
                server = server.trimEnd('/'),
                applyImmediately = applyImmediately,
                preferredLocales = { app.resources.configuration.locales.toList() },
                onDownloaded = { release -> main.post { listeners.forEach { it.onReleaseDownloaded(release) } } },
            )
            // Reading the stored release is a small local file. It happens before the first
            // screen so that every string of this launch comes from the same release.
            engine.activateStoredRelease()
            this.engine = engine
        }
        worker.execute { engine?.checkForUpdates() }
    }

    /**
     * Wraps a context so that its resources return released strings.
     * Use it in `attachBaseContext` of each Activity, or of a base class they share:
     *
     * ```
     * override fun attachBaseContext(base: Context) = super.attachBaseContext(Stringalize.wrap(base))
     * ```
     */
    @JvmStatic
    fun wrap(base: Context): Context = if (base is StringalizeContext) base else StringalizeContext(base)

    /** The number of the release in use, or `null` while the app shows only its bundled strings. */
    @JvmStatic
    val release: Int?
        get() = engine?.activeRelease

    /** Checks for a newer release now, for example when the app returns to the foreground. */
    @JvmStatic
    @JvmOverloads
    fun checkForUpdates(callback: UpdateCallback? = null) {
        val engine = engine
        if (engine == null) {
            callback?.let { main.post { it.onResult(false) } }
            return
        }
        worker.execute {
            val downloaded = engine.checkForUpdates()
            callback?.let { main.post { it.onResult(downloaded) } }
        }
    }

    @JvmStatic
    fun addListener(listener: ReleaseListener) {
        listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: ReleaseListener) {
        listeners.remove(listener)
    }
}

internal fun android.os.LocaleList.toList(): List<java.util.Locale> = (0 until size()).map { get(it) }
