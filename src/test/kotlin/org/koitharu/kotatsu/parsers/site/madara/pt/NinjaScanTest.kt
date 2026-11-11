package org.koitharu.kotatsu.parsers.site.madara.pt

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
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.util.generateUid

internal class NinjaScanTest {

    @Test
    fun detailsPreserveRealChapterNumbersAndIdentity() = runTest {
        val context = FixtureContext(DETAILS_WITH_CHAPTERS)
        val parser = NinjaScan(context)

        val details = parser.getDetails(manga)
        val chapters = requireNotNull(details.chapters)

        assertEquals(listOf(0f, 0f, 651.5f, 652f, 652.5f), chapters.map { it.number })
        assertEquals(manga.id, details.id)
        assertEquals(MangaParserSource.NINJASCAN, details.source)
        assertEquals("/manga/principe-dragao-sagrado/capitulo-652-5/?style=list", chapters.last().url)
        assertEquals(parser.generateUid("/manga/principe-dragao-sagrado/capitulo-652-5/"), chapters.last().id)
        assertEquals(MangaParserSource.NINJASCAN, chapters.last().source)
        assertTrue(chapters.last().uploadDate > 0L)
        assertEquals(listOf("GET /manga/principe-dragao-sagrado/"), context.requests)
    }

    @Test
    fun ajaxChaptersUseNumbersFromTitlesInsteadOfListPositions() = runTest {
        val context = FixtureContext(DETAILS_WITHOUT_CHAPTERS, CHAPTERS)
        val parser = NinjaScan(context)

        val details = parser.getDetails(manga)

        assertEquals(listOf(0f, 0f, 651.5f, 652f, 652.5f), details.chapters.orEmpty().map { it.number })
        assertEquals(
            listOf(
                "GET /manga/principe-dragao-sagrado/",
                "POST /manga/principe-dragao-sagrado/ajax/chapters/",
            ),
            context.requests,
        )
    }

    @Test
    fun detailsRefreshAuthorsAlternativesAndRating() = runTest {
        val parser = NinjaScan(FixtureContext(DETAILS_WITH_CHAPTERS))

        val details = parser.getDetails(manga)

        assertEquals("Príncipe Dragão Sagrado", details.title)
        assertEquals(setOf("Yuan Zun", "Dragon Prince Yuan", "元尊"), details.altTitles)
        assertEquals(setOf("Tian Can Tou Du", "Dr. Da Ji"), details.authors)
        assertEquals(0.92f, details.rating, 0.0001f)
        assertEquals(MangaState.FINISHED, details.state)
    }

    private class FixtureContext(
        private val detailHtml: String,
        private val chaptersHtml: String = "",
    ) : MangaLoaderContext() {

        val requests = mutableListOf<String>()

        override val cookieJar: CookieJar = CookieJar.NO_COOKIES
        override val httpClient: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requests += "${request.method} ${request.url.encodedPath}"
                val body = when (request.url.encodedPath) {
                    "/manga/principe-dragao-sagrado/" -> detailHtml
                    "/manga/principe-dragao-sagrado/ajax/chapters/" -> chaptersHtml
                    else -> error("Unexpected request: ${request.url}")
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()

        @Deprecated("Provide a base url")
        override suspend fun evaluateJs(script: String): String? = error("No WebView needed for fixture")

        override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? =
            error("No WebView needed for fixture")

        override fun getConfig(source: MangaSource): MangaSourceConfig = SourceConfigMock()

        override fun getDefaultUserAgent(): String = "NinjaScanTest"

        override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response =
            error("No images are decoded in metadata tests")

        override fun createBitmap(width: Int, height: Int): Bitmap = error("No bitmap needed for fixture")
    }

    private companion object {
        val manga = Manga(
            id = 17L,
            title = "Título da listagem",
            altTitles = emptySet(),
            url = "/manga/principe-dragao-sagrado/",
            publicUrl = "https://ninjacomics.xyz/manga/principe-dragao-sagrado/",
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = MangaParserSource.NINJASCAN,
        )

        // Minimal fixtures reproduce the observed NinjaComics Madara selectors.
        // Zero, comma decimal and unnumbered special exercise edge cases synthetically.
        const val CHAPTERS = """
            <ul>
                <li class="wp-manga-chapter">
                    <a href="/manga/principe-dragao-sagrado/capitulo-652-5/">Capítulo 652.5 - Final!</a>
                    <span class="chapter-release-date"><i>29 de novembro de 2024</i></span>
                </li>
                <li class="wp-manga-chapter">
                    <a href="/manga/principe-dragao-sagrado/capitulo-652/">Capítulo 652</a>
                </li>
                <li class="wp-manga-chapter">
                    <a href="/manga/principe-dragao-sagrado/capitulo-651-5/">Capítulo 651,5</a>
                </li>
                <li class="wp-manga-chapter">
                    <a href="/manga/principe-dragao-sagrado/capitulo-0/">Capítulo 0</a>
                </li>
                <li class="wp-manga-chapter">
                    <a href="/manga/principe-dragao-sagrado/especial/">Especial de aniversário 2024</a>
                </li>
            </ul>
        """

        const val METADATA = """
            <h1>Príncipe Dragão Sagrado</h1>
            <div class="post-content_item">
                <div class="summary-heading"><h5>Alternative</h5></div>
                <div class="summary-content">Yuan Zun, Dragon Prince Yuan, 元尊</div>
            </div>
            <div class="post-content_item">
                <div class="summary-heading"><h5>Status</h5></div>
                <div class="summary-content">Completed</div>
            </div>
            <div class="author-content"><a>Tian Can Tou Du</a></div>
            <div class="artist-content"><a>Dr. Da Ji</a></div>
            <span id="averagerate">4.6</span>
        """

        val DETAILS_WITH_CHAPTERS = "<html><body>$METADATA<div class='listing-chapters_wrap'>$CHAPTERS</div></body></html>"
        val DETAILS_WITHOUT_CHAPTERS = "<html><body>$METADATA</body></html>"
    }
}
