package app.stringalize

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class EngineTest {
    private lateinit var directory: File
    private val server = FakeServer()
    private var preferred = listOf(Locale.GERMANY)

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("stringalize").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        Stringalize.engine = null
    }

    private fun engine(applyImmediately: Boolean = false) =
        Engine(directory, "ota_test", "https://example.test", applyImmediately, { preferred }, server)

    @Test
    fun aDownloadedReleaseIsUsedFromTheNextLaunch() {
        server.publish(1, "Hallo %1\$s")
        val first = engine()
        first.activateStoredRelease()
        assertTrue(first.checkForUpdates())
        assertNull(first.activeRelease)
        assertNull(first.text("greeting", preferred))

        val second = engine()
        second.activateStoredRelease()
        assertEquals(1, second.activeRelease)
        assertEquals("Hallo %1\$s", second.text("greeting", preferred))
        assertNull(second.text("missing", preferred))
    }

    @Test
    fun applyImmediatelyUsesTheReleaseRightAway() {
        server.publish(1, "Hallo")
        val engine = engine(applyImmediately = true)
        assertTrue(engine.checkForUpdates())
        assertEquals("Hallo", engine.text("greeting", preferred))
    }

    @Test
    fun nothingIsDownloadedWhileTheManifestIsUnchanged() {
        server.publish(1, "Hallo")
        val engine = engine()
        assertTrue(engine.checkForUpdates())
        assertFalse(engine.checkForUpdates())
        assertEquals("\"r1\"", server.lastEtag)

        server.publish(2, "Guten Tag")
        assertTrue(engine.checkForUpdates())
        val next = engine().apply { activateStoredRelease() }
        assertEquals("Guten Tag", next.text("greeting", preferred))
        assertEquals(listOf("release-2-de.json", "state.json"), directory.list()!!.sorted())
    }

    @Test
    fun failuresLeaveTheStoredReleaseInPlace() {
        server.publish(1, "Hallo")
        engine().checkForUpdates()
        server.failing = true
        val engine = engine().apply { activateStoredRelease() }
        assertFalse(engine.checkForUpdates())
        assertEquals("Hallo", engine.text("greeting", preferred))
    }

    @Test
    fun aReleaseInAnotherLanguageIsNotUsed() {
        server.publish(1, "Hallo")
        engine().checkForUpdates()

        preferred = listOf(Locale.ITALY)
        val engine = engine().apply { activateStoredRelease() }
        assertNull(engine.activeRelease)

        // With no published Italian the app shows its default resources: the source language.
        assertTrue(engine.checkForUpdates())
        assertEquals("en", server.downloads.last())
    }

    @Test
    fun theLanguageOnScreenDecidesWhetherReleasedTextApplies() {
        server.publish(1, "Hallo")
        engine().checkForUpdates()
        val engine = engine().apply { activateStoredRelease() }
        assertEquals("Hallo", engine.text("greeting", listOf(Locale.GERMAN)))
        assertNull(engine.text("greeting", listOf(Locale.FRANCE, Locale.ENGLISH)))
    }

    @Test
    fun pluralsFollowTheLanguagesRules() {
        preferred = listOf(Locale.forLanguageTag("pl-PL"))
        server.locales = listOf("en", "pl")
        server.publish(1, "Cześć", plurals = """{"files": {"one": "%d plik", "few": "%d pliki", "many": "%d plików", "other": "%d pliku"}}""")
        engine().checkForUpdates()
        val engine = engine().apply { activateStoredRelease() }
        assertEquals("pl", server.downloads.last())
        assertEquals("%d plik", engine.quantityText("files", 1, preferred))
        assertEquals("%d pliki", engine.quantityText("files", 3, preferred))
        assertEquals("%d plików", engine.quantityText("files", 5, preferred))
        assertEquals("%d pliki", engine.quantityText("files", 22, preferred))
        assertNull(engine.quantityText("other", 1, preferred))
    }

    @Test
    fun dottedKeysAnswerForAndroidResourceNames() {
        server.publish(1, "Hallo", extra = """, "home.title": "Start", "styled": "<b>Fett</b>"""")
        engine().checkForUpdates()
        val engine = engine().apply { activateStoredRelease() }
        assertEquals("Start", engine.text("home_title", preferred))
        assertEquals("Start", engine.text("home.title", preferred))
        assertNull(engine.text("styled", preferred))
    }

    @Test
    fun resourcesReturnReleasedText() {
        server.publish(1, "Hallo", extra = """, "ok": "Jawohl"""")
        engine().checkForUpdates()
        Stringalize.engine = engine().apply { activateStoredRelease() }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val german = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply { setLocale(Locale.GERMANY) })
        val wrapped = Stringalize.wrap(german)
        assertEquals("Jawohl", wrapped.getString(android.R.string.ok))
        assertEquals("Jawohl", wrapped.resources.getText(android.R.string.ok).toString())
        assertEquals(german.getString(android.R.string.cancel), wrapped.getString(android.R.string.cancel))
    }

    @Test
    fun choosingALocale() {
        fun choose(vararg preferred: String, available: List<String>, source: String? = "en") =
            Locales.choose(preferred.map(Locale::forLanguageTag), available, source)

        assertEquals("pt-BR", choose("pt-BR", available = listOf("en", "pt", "pt-BR")))
        assertEquals("pt", choose("pt-PT", available = listOf("en", "pt", "pt-BR")))
        assertEquals("pt-BR", choose("pt-PT", available = listOf("en", "pt-BR")))
        assertEquals("zh-Hant", choose("zh-TW", available = listOf("en", "zh-Hans", "zh-Hant")))
        assertEquals("zh-Hans", choose("zh-Hans-CN", available = listOf("en", "zh-Hans", "zh-Hant")))
        assertEquals("en", choose("zh-TW", available = listOf("en", "zh-Hans")))
        assertEquals("de", choose("fr-FR", "de-AT", available = listOf("en", "de")))
        assertEquals("he", choose("iw-IL", available = listOf("en", "he")))
        assertNull(choose("fr", available = listOf("de"), source = "en"))
    }

    private class FakeServer : Http {
        var release = 0
        var locales = listOf("de", "en")
        var bodies = mapOf<String, String>()
        var failing = false
        var lastEtag: String? = null
        val downloads = mutableListOf<String>()

        fun publish(number: Int, greeting: String, plurals: String = "{}", extra: String = "") {
            release = number
            bodies = locales.associateWith { locale ->
                """{"release": $number, "locale": "$locale", "platform": "android", "strings": {"greeting": "$greeting"$extra}, "plurals": $plurals}"""
            }
        }

        override fun get(url: String, etag: String?): Http.Response {
            if (failing) throw java.io.IOException("offline")
            val path = url.removePrefix("https://example.test/ota/v1/ota_test/")
            if (path == "manifest.json") {
                lastEtag = etag
                val current = "\"r$release\""
                if (etag == current) return Http.Response(304, "", current)
                val list = locales.joinToString { "\"$it\"" }
                return Http.Response(200, """{"release": $release, "locales": [$list], "source_locale": "en"}""", current)
            }
            val (number, locale) = path.removeSuffix("/android.json").split("/")
            downloads += locale
            val body = bodies[locale]?.takeIf { number.toInt() == release } ?: return Http.Response(404, "", null)
            return Http.Response(200, body, null)
        }
    }
}
