package org.koitharu.kotatsu.parsers.site.pt

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.site.madara.pt.BorutoExplorer
import org.koitharu.kotatsu.parsers.util.generateUid
import java.util.Calendar

internal class BorutoExplorerTest {

    @Test
    fun detailsPreserveIdentityAndParseRealNumbersVolumesAndTitleDates() = runTest {
        val parser = BorutoExplorer(FixtureContext(DETAILS))
        val details = parser.getDetails(manga)
        val chapters = details.chapters.orEmpty()

        assertEquals(manga.id, details.id)
        assertEquals(manga.url, details.url)
        assertEquals(manga.source, details.source)
        assertEquals(listOf(186f, 186.5f, 187f), chapters.map { it.number })
        assertEquals(listOf(21, 21, 21), chapters.map { it.volume })
        assertEquals(listOf(null, "Extra", "Implorando pela vida…!!"), chapters.map { it.title })
        val last = chapters.last()
        assertEquals(parser.generateUid(CHAPTER_PATH), last.id)
        assertEquals("$CHAPTER_PATH?style=list", last.url)
        assertEquals(chapters.map { it.id }, parser.getDetails(manga).chapters.orEmpty().map { it.id })
        val date = Calendar.getInstance().apply { timeInMillis = last.uploadDate }
        assertEquals(2026, date.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, date.get(Calendar.MONTH))
        assertEquals(29, date.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun detailsKeepAjaxChapterFallbackWhenChapterListIsNotInline() = runTest {
        val context = FixtureContext(DETAILS_WITHOUT_CHAPTERS, ajaxHtml = AJAX_CHAPTERS)
        val parser = BorutoExplorer(context)

        val chapter = parser.getDetails(manga).chapters.orEmpty().single()

        assertEquals(187f, chapter.number)
        assertEquals(21, chapter.volume)
        assertEquals("Implorando pela vida…!!", chapter.title)
        assertTrue(chapter.uploadDate > 0L)
        assertEquals(parser.generateUid(CHAPTER_PATH), chapter.id)
        assertTrue(context.requests.contains("POST $MANGA_PATH/ajax/chapters/"))
    }

    @Test
    fun readerReturnsOnlyChapterImagesInObservedOrderAndTrimsSourceUrls() = runTest {
        val parser = BorutoExplorer(FixtureContext(DETAILS, readerHtml = READER))
        val chapter = parser.getDetails(manga).chapters.orEmpty().last()

        val pages = parser.getPages(chapter)

        assertEquals(20, pages.size)
        assertEquals(IMAGE_NAMES.map { "$IMAGE_BASE/$it" }, pages.map { parser.getPageUrl(it) })
        assertTrue(pages.all { it.source == manga.source })
        assertEquals(20, pages.map { it.id }.distinct().size)
    }

    @Test
    fun readerWithoutImagesFailsWithChapterUrl() = runTest {
        val parser = BorutoExplorer(FixtureContext(DETAILS, readerHtml = "<div class='reading-content'></div>"))

        val error = try {
            parser.getPages(chapter)
            throw AssertionError("Expected empty reader to fail")
        } catch (e: ParseException) {
            e
        }

        assertEquals("https://$DOMAIN$CHAPTER_PATH?style=list", error.url)
        assertTrue(error.message.orEmpty().contains("did not return any chapter images"))
    }

    @Test
    fun textOnlyChapterReportsUnsupportedContent() = runTest {
        val parser = BorutoExplorer(
            FixtureContext(DETAILS, readerHtml = "<div class='reading-content'><p>Texto da novel.</p></div>"),
        )

        val error = try {
            parser.getPages(chapter)
            throw AssertionError("Expected a text chapter to fail")
        } catch (e: ParseException) {
            e
        }

        assertTrue(error.message.orEmpty().contains("text chapters are not supported"))
    }

    @Test
    fun loginGateKeepsAuthenticationError() = runTest {
        val parser = BorutoExplorer(FixtureContext(DETAILS, readerHtml = "<div class='login-required'></div>"))

        try {
            parser.getPages(chapter)
            throw AssertionError("Expected authentication error")
        } catch (_: AuthRequiredException) {
            // A login gate must remain actionable rather than become a missing-image error.
        }
    }

    private class FixtureContext(
        private val detailsHtml: String,
        private val ajaxHtml: String? = null,
        private val readerHtml: String? = null,
    ) : MangaLoaderContext() {
        val requests = mutableListOf<String>()

        override val cookieJar: CookieJar = CookieJar.NO_COOKIES
        override val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val operation = "${request.method} ${request.url.encodedPath}"
            requests += operation
            val html = when (operation) {
                "GET $MANGA_PATH" -> detailsHtml
                "POST $MANGA_PATH/ajax/chapters/" -> checkNotNull(ajaxHtml) { "Unexpected AJAX request" }
                "GET $CHAPTER_PATH" -> checkNotNull(readerHtml) { "Unexpected reader request" }
                else -> throw AssertionError("Unexpected fixture request: $operation")
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }.build()

        override fun getConfig(source: MangaSource): MangaSourceConfig = SourceConfigMock()
        override fun getDefaultUserAgent(): String = "BorutoExplorerFixture/1.0"

        @Deprecated("Provide a base url")
        override suspend fun evaluateJs(script: String): String? = error("No JavaScript expected")
        override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? =
            error("No JavaScript expected")

        override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response =
            error("No image transformation expected")
        override fun createBitmap(width: Int, height: Int): Bitmap = error("No bitmap expected")
    }

    private companion object {
        const val DOMAIN = "leitor.borutoexplorer.com.br"
        const val MANGA_PATH = "/manga/naruto-versao-colorida"
        const val CHAPTER_PATH = "$MANGA_PATH/volume-21/capitulo-187/"
        const val IMAGE_BASE = "https://$DOMAIN/wp-content/uploads/WP-manga/data/" +
            "manga_671563161221b/9ef2a17344f6ad21f2af61f56791d5f8"

        val manga = Manga(
            id = 42L,
            title = "Naruto (Versão Colorida)",
            altTitles = emptySet(),
            url = MANGA_PATH,
            publicUrl = "https://$DOMAIN$MANGA_PATH",
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = MangaParserSource.BORUTOEXPLORER,
        )
        val chapter = MangaChapter(
            id = 187L,
            title = null,
            number = 187f,
            volume = 21,
            url = "$CHAPTER_PATH?style=list",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = MangaParserSource.BORUTOEXPLORER,
        )

        // Minimal sanitized snapshot of selectors observed in the browser on 2026-10-02.
        // Fractional chapter and alternate og:url add explicit contract edge cases to that shape.
        val DETAILS_WITHOUT_CHAPTERS = """
            <html><head><meta property="og:url" content="https://$DOMAIN$MANGA_PATH/"></head><body>
            <h1>Naruto (Versão Colorida)</h1>
            <div class="description-summary"><div class="summary__content">Sinopse de teste.</div></div>
            </body></html>
        """.trimIndent()
        val DETAILS = DETAILS_WITHOUT_CHAPTERS.replace("</body>", """
            <div class="listing-chapters_wrap"><ul>
            <li>VOLUME 21</li>
            <li class="wp-manga-chapter">
                <a href="$CHAPTER_PATH">Capítulo 187 - Implorando pela vida…!!</a>
                <span class="chapter-release-date"><a title="29 de setembro de 2026"></a></span>
            </li>
            <li class="wp-manga-chapter">
                <a href="$MANGA_PATH/volume-21/capitulo-186.5/">Capítulo 186,5 - Extra</a>
                <span class="chapter-release-date"><i>28 de setembro de 2026</i></span>
            </li>
            <li class="wp-manga-chapter">
                <a href="$MANGA_PATH/volume-21/capitulo-186/">Capítulo 186</a>
                <span class="chapter-release-date"><i>27 de setembro de 2026</i></span>
            </li>
            </ul></div></body>
        """.trimIndent())
        val AJAX_CHAPTERS = """
            <ul><li class="wp-manga-chapter">
                <a href="$CHAPTER_PATH">Capítulo 187 - Implorando pela vida…!!</a>
                <span class="chapter-release-date"><i>29 de setembro de 2026</i></span>
            </li></ul>
        """.trimIndent()
        val IMAGE_NAMES = listOf("0.png") + (126..142).map { "21-$it.png" } +
            listOf("21-143-144.png", "21-145.png")
        val READER = """
            <html><body>
            <img class="wp-manga-chapter-img" src="https://cdn.example/logo.png">
            <div class="reading-content">
            <img src="https://cdn.example/ad.png">
            ${IMAGE_NAMES.joinToString("\n") { "<div class='page-break'><img class='wp-manga-chapter-img' src=' $IMAGE_BASE/$it '></div>" }}
            </div></body></html>
        """.trimIndent()
    }
}
