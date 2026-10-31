package app.stringalize

import android.icu.text.PluralRules
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Downloads, stores and serves releases. Thread-safe: lookups read one immutable [Release]. */
internal class Engine(
    private val directory: File,
    private val token: String,
    private val server: String,
    private val applyImmediately: Boolean,
    private val preferredLocales: () -> List<Locale>,
    private val http: Http = UrlHttp,
    private val onDownloaded: (Int) -> Unit = {},
) {
    @Volatile
    private var active: Release? = null

    val activeRelease: Int?
        get() = active?.number

    /** Uses the release downloaded by an earlier launch, if it is in the user's language. */
    fun activateStoredRelease() {
        val state = readState() ?: return
        if (state.choose(preferredLocales()) != state.locale) return
        active = readRelease(state)
    }

    /** Fetches the manifest and downloads a newer release. Returns true when one was downloaded. */
    @Synchronized
    fun checkForUpdates(): Boolean = try {
        download()
    } catch (e: Exception) {
        // No network, a slow connection or a server problem leave the app as it was.
        Log.i(TAG, "Update check failed: $e")
        false
    }

    /** The released text for a string resource, or null to use the bundled one. */
    fun text(name: String, locales: List<Locale>): String? = current(locales)?.strings?.get(name)

    /** The released text for a plurals resource and quantity, or null to use the bundled one. */
    fun quantityText(name: String, quantity: Int, locales: List<Locale>): String? {
        val release = current(locales) ?: return null
        val forms = release.plurals[name] ?: return null
        return forms[release.pluralRules.select(quantity.toDouble())] ?: forms["other"]
    }

    private fun current(locales: List<Locale>): Release? = active?.takeIf { it.isFor(locales) }

    private fun download(): Boolean {
        val stored = readState()?.takeIf { releaseFile(it).exists() }
        val manifest = http.get("$server/ota/v1/${path(token)}/manifest.json", stored?.etag)
        val latest = when (manifest.code) {
            200 -> {
                val json = JSONObject(manifest.body)
                State(json.getInt("release"), "", json.getJSONArray("locales").strings(), json.optString("source_locale").ifEmpty { null }, manifest.etag)
            }
            304 -> stored!!
            404 -> {
                Log.i(TAG, "Nothing published yet, or the token is not valid")
                return false
            }
            else -> throw IllegalStateException("manifest answered ${manifest.code}")
        }

        val locale = latest.choose(preferredLocales()) ?: return false
        val wanted = latest.copy(locale = locale)
        if (stored != null && stored.release == wanted.release && stored.locale == locale) {
            if (stored != wanted) writeState(wanted)
            return false
        }

        val bundle = http.get("$server/ota/v1/${path(token)}/${wanted.release}/${path(locale)}/android.json", null)
        check(bundle.code == 200) { "release ${wanted.release}/$locale answered ${bundle.code}" }
        val downloaded = Release.parse(bundle.body, wanted)

        directory.mkdirs()
        writeAtomically(releaseFile(wanted), bundle.body)
        writeState(wanted)
        directory.listFiles { file -> file.name.startsWith("release-") && file != releaseFile(wanted) }?.forEach { it.delete() }

        if (applyImmediately) active = downloaded
        Log.i(TAG, "Downloaded release ${wanted.release} ($locale)")
        onDownloaded(wanted.release)
        return true
    }

    private fun readState(): State? = try {
        File(directory, "state.json").takeIf { it.exists() }?.let { State.parse(it.readText()) }
    } catch (e: Exception) {
        null
    }

    private fun writeState(state: State) {
        directory.mkdirs()
        writeAtomically(File(directory, "state.json"), state.toJson())
    }

    private fun readRelease(state: State): Release? = try {
        releaseFile(state).takeIf { it.exists() }?.let { Release.parse(it.readText(), state) }
    } catch (e: Exception) {
        Log.w(TAG, "Stored release is unreadable: $e")
        null
    }

    private fun releaseFile(state: State) = File(directory, "release-${state.release}-${state.locale.replace(Regex("[^A-Za-z0-9-]"), "_")}.json")

    private fun writeAtomically(file: File, text: String) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) {
            file.delete()
            check(temporary.renameTo(file)) { "could not write ${file.name}" }
        }
    }

    companion object {
        const val TAG = "Stringalize"

        /** FNV-1a, to keep each token's files apart without storing the token itself. */
        fun hash(text: String): String {
            var hash = -0x340d631b7bdddcdbL
            text.toByteArray().forEach { byte ->
                hash = hash xor (byte.toLong() and 0xff)
                hash *= 0x100000001b3L
            }
            return java.lang.Long.toHexString(hash)
        }

        private fun path(segment: String) = URLEncoder.encode(segment, "UTF-8")
    }
}

/** What the last manifest said, and which of its locales was downloaded. */
internal data class State(val release: Int, val locale: String, val locales: List<String>, val source: String?, val etag: String?) {
    fun choose(preferred: List<Locale>): String? = Locales.choose(preferred, locales, source)

    fun toJson(): String = JSONObject()
        .put("release", release)
        .put("locale", locale)
        .put("locales", JSONArray(locales))
        .put("source", source ?: JSONObject.NULL)
        .put("etag", etag ?: JSONObject.NULL)
        .toString()

    companion object {
        fun parse(text: String): State {
            val json = JSONObject(text)
            fun optional(name: String) = if (json.isNull(name)) null else json.optString(name).ifEmpty { null }
            return State(json.getInt("release"), json.getString("locale"), json.getJSONArray("locales").strings(), optional("source"), optional("etag"))
        }
    }
}

internal class Release(private val state: State, strings: Map<String, String>, plurals: Map<String, Map<String, String>>) {
    val number = state.release
    val strings = strings.withResourceNames()
    val plurals = plurals.withResourceNames()
    val pluralRules: PluralRules by lazy { PluralRules.forLocale(Locale.forLanguageTag(state.locale)) }

    @Volatile
    private var checked: Pair<List<Locale>, Boolean>? = null

    /** Whether this release is in the language the app shows for these locales. */
    fun isFor(locales: List<Locale>): Boolean {
        checked?.let { (seen, result) -> if (seen == locales) return result }
        val result = state.choose(locales) == state.locale
        checked = locales to result
        return result
    }

    companion object {
        // Styled text (<b>, <i>, <xliff:g>) would lose its spans, so those strings keep the bundled version.
        private val MARKUP = Regex("</?[A-Za-z][^>]*>")

        fun parse(text: String, state: State): Release {
            val json = JSONObject(text)
            val strings = json.optJSONObject("strings")?.let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }.orEmpty()
            val plurals = json.optJSONObject("plurals")?.let { obj ->
                obj.keys().asSequence().associateWith { key ->
                    val forms = obj.getJSONObject(key)
                    forms.keys().asSequence().associateWith { forms.getString(it) }
                }
            }.orEmpty()
            return Release(
                state,
                strings.filterValues { !MARKUP.containsMatchIn(it) },
                plurals.filterValues { forms -> forms.values.none { MARKUP.containsMatchIn(it) } },
            )
        }

        // Android turns dots and dashes in resource names into underscores, so a key
        // "home.title" in Stringalize answers for R.string.home_title too.
        private fun <T> Map<String, T>.withResourceNames(): Map<String, T> {
            val result = HashMap<String, T>(size * 2)
            forEach { (key, value) -> result[key.replace(Regex("[^A-Za-z0-9_]"), "_")] = value }
            result.putAll(this)
            return result
        }
    }
}

internal object Locales {
    /**
     * The published locale for the user's preferences, tried in their order: the same
     * script and region, then the same script, then the same language. With no match the
     * app shows its default resources, which hold the source language.
     */
    fun choose(preferred: List<Locale>, available: List<String>, source: String?): String? {
        val candidates = available.map { it to Locale.forLanguageTag(it) }
        for (wanted in preferred) {
            val script = script(wanted)
            val sameScript = candidates.filter { (_, locale) -> language(locale) == language(wanted) && script(locale) == script }
            sameScript.firstOrNull { (_, locale) -> locale.country == wanted.country }?.let { return it.first }
            sameScript.firstOrNull { (_, locale) -> locale.country.isEmpty() }?.let { return it.first }
            sameScript.firstOrNull()?.let { return it.first }
        }
        return source?.takeIf { it in available }
    }

    // Older Android versions still report the legacy codes for Hebrew, Indonesian and Yiddish.
    private fun language(locale: Locale) = when (val language = locale.language) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        else -> language
    }

    // Chinese is the language whose regions imply a script, and the script decides the text.
    private fun script(locale: Locale): String = when {
        locale.script.isNotEmpty() -> locale.script
        language(locale) != "zh" -> ""
        locale.country in setOf("TW", "HK", "MO") -> "Hant"
        else -> "Hans"
    }
}

internal interface Http {
    class Response(val code: Int, val body: String, val etag: String?)

    fun get(url: String, etag: String?): Response
}

internal object UrlHttp : Http {
    override fun get(url: String, etag: String?): Http.Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Stringalize-Android/${BuildConfig.VERSION}")
            etag?.let { connection.setRequestProperty("If-None-Match", it) }
            val code = connection.responseCode
            val body = if (code == 200) connection.inputStream.bufferedReader().use { it.readText() } else ""
            return Http.Response(code, body, connection.getHeaderField("ETag"))
        } finally {
            connection.disconnect()
        }
    }
}

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
