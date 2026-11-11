package org.koitharu.kotatsu.parsers.site.madara.pt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import org.jsoup.HttpStatusException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.test_util.mangaOf
import java.util.Collections

internal class HuntersScanTest {

	@Test
	fun genresUseTheObservedProjectPathAndGenrePrefix() = runTest {
		val context = FixtureContext(mapOf("/series-2/" to FixtureResponse(GENRES)))
		val parser = HuntersScan(context)

		val tags = parser.getFilterOptions().availableTags

		assertEquals(listOf("fantasia-2"), tags.map { it.key })
		assertEquals(listOf("Fantasia"), tags.map { it.title })
		assertEquals(listOf("GET /series-2/"), context.requests)
	}

	@Test
	fun completeChapterPaginationPreservesPublishedNumbersAndChronologicalOrder() = runTest {
		val context = FixtureContext(chapterFixtures(
			chapter("51", "Vol. 2 Capítulo 51 - A volta") + pagination(2),
			chapter("50-5", "Capítulo 50,5 - Extra") + chapter("50", "Capítulo 50"),
		))
		val parser = HuntersScan(context)

		val details = parser.getDetails(manga)
		val chapters = requireNotNull(details.chapters)

		assertEquals(listOf(50f, 50.5f, 51f), chapters.map { it.number })
		assertEquals(listOf(null, "Extra", "A volta"), chapters.map { it.title })
		assertEquals(listOf(0, 0, 2), chapters.map { it.volume })
		assertEquals(listOf(
			"/read/fixture/chapter-50?style=list",
			"/read/fixture/chapter-50-5?style=list",
			"/read/fixture/chapter-51?style=list",
		), chapters.map { it.url })
		assertEquals(manga.id, details.id)
		assertEquals(manga.url, details.url)
		assertEquals(MangaParserSource.HUNTERSSCAN, details.source)
		assertEquals(3, chapters.map { it.id }.toSet().size)
		assertTrue(chapters.all { it.uploadDate > 0 })
		assertTrue(context.requests.contains("POST /read/fixture/ajax/chapters/?t=2"))
	}

	@Test
	fun unnumberedSpecialIsNotAssignedAnInventedIndex() = runTest {
		val parser = HuntersScan(FixtureContext(chapterFixtures(chapter("extra", "Especial de Natal"))))

		val chapter = requireNotNull(parser.getDetails(manga).chapters).single()

		assertEquals(0f, chapter.number)
		assertEquals("Especial de Natal", chapter.title)
	}

	@Test
	fun detailsWithoutAChapterHolderUseTheSameCompleteChapterLoader() = runTest {
		val fixtures = chapterFixtures(chapter("50", "Capítulo 50")).toMutableMap()
		fixtures["/read/fixture/"] = FixtureResponse("<html><body><h1>Fixture</h1></body></html>")
		val parser = HuntersScan(FixtureContext(fixtures))

		val chapter = requireNotNull(parser.getDetails(manga).chapters).single()

		assertEquals(50f, chapter.number)
		assertEquals("/read/fixture/chapter-50?style=list", chapter.url)
	}

	@Test
	fun laterHttpFailureDoesNotReturnAPartialChapterList() = runTest {
		val fixtures = chapterFixtures(chapter("51", "Capítulo 51") + pagination(2)).toMutableMap()
		fixtures["/read/fixture/ajax/chapters/?t=2"] = FixtureResponse("Unavailable", code = 503)
		val parser = HuntersScan(FixtureContext(fixtures))

		try {
			parser.getDetails(manga)
			throw AssertionError("Expected the failed chapter page to reject the entire details request")
		} catch (e: HttpStatusException) {
			assertEquals(503, e.statusCode)
		}
	}

	@Test
	fun emptyAnnouncedChapterPageIsRejectedAsIncomplete() = runTest {
		val parser = HuntersScan(FixtureContext(chapterFixtures(
			chapter("51", "Capítulo 51") + pagination(2),
			"<html><body>Unexpected response</body></html>",
		)))

		try {
			parser.getDetails(manga)
			throw AssertionError("Expected the empty announced page to reject the truncated chapter list")
		} catch (e: ParseException) {
			assertTrue(e.message.orEmpty().contains("page 2"))
		}
	}

	@Test
	fun cancellationWhileParsingALaterChapterPageIsPropagated() = runTest {
		val cancellation = CancellationException("Chapter request cancelled")
		val fixtures = chapterFixtures(chapter("51", "Capítulo 51") + pagination(2)).toMutableMap()
		fixtures["/read/fixture/ajax/chapters/?t=2"] = FixtureResponse("", cancellation = cancellation)
		val parser = HuntersScan(FixtureContext(fixtures))

		try {
			parser.getDetails(manga)
			throw AssertionError("Expected cancellation to escape the chapter loader")
		} catch (e: CancellationException) {
			assertEquals("Chapter request cancelled", e.message)
		}
	}

	private class FixtureContext(fixtures: Map<String, FixtureResponse>) : MangaLoaderContext() {
		val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
		override val cookieJar = CookieJar.NO_COOKIES
		override val httpClient: OkHttpClient = OkHttpClient.Builder()
			.cookieJar(cookieJar)
			.addInterceptor(MockInterceptor(fixtures, requests))
			.build()

		@Deprecated("Provide a base url")
		override suspend fun evaluateJs(script: String): String? = error("Unexpected JavaScript evaluation")

		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? =
			error("Unexpected JavaScript evaluation")

		override fun getConfig(source: MangaSource): MangaSourceConfig = SourceConfigMock()
		override fun getDefaultUserAgent(): String = "HuntersScan fixture"
		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response =
			error("Unexpected image redraw")
		override fun createBitmap(width: Int, height: Int): Bitmap = error("Unexpected bitmap creation")
	}

	private class MockInterceptor(
		private val fixtures: Map<String, FixtureResponse>,
		private val requests: MutableList<String>,
	) : Interceptor {
		override fun intercept(chain: Interceptor.Chain): Response {
			val request = chain.request()
			val path = request.url.encodedPath + request.url.encodedQuery?.let { "?$it" }.orEmpty()
			requests += "${request.method} $path"
			val fixture = fixtures[path] ?: error("Unexpected request: ${request.method} $path")
			val body = fixture.cancellation?.let { cancellation ->
				object : ResponseBody() {
					override fun contentType() = "text/html".toMediaType()
					override fun contentLength(): Long = -1L
					override fun source(): BufferedSource = throw cancellation
				}
			} ?: fixture.html.toResponseBody("text/html; charset=utf-8".toMediaType())
			return Response.Builder()
				.request(request)
				.protocol(Protocol.HTTP_1_1)
				.code(fixture.code)
				.message("Fixture")
				.body(body)
				.build()
		}
	}

	private data class FixtureResponse(
		val html: String,
		val code: Int = 200,
		val cancellation: CancellationException? = null,
	)

	private companion object {
		val manga = mangaOf(MangaParserSource.HUNTERSSCAN, "https://readhunters.xyz/read/fixture/")
		const val DETAILS = "<html><body><h1>Fixture</h1><div class='listing-chapters_wrap'></div></body></html>"
		const val GENRES = """
			<html><body><div class="genres_wrap"><ul class="list-unstyled">
			<li><a href="https://readhunters.xyz/manga-genre/fantasia-2/">Fantasia</a></li>
			</ul></div></body></html>
		"""

		fun chapterFixtures(first: String, second: String? = null): Map<String, FixtureResponse> = buildMap {
			put("/read/fixture/", FixtureResponse(DETAILS))
			put("/read/fixture/ajax/chapters/?t=1", FixtureResponse(first))
			second?.let { put("/read/fixture/ajax/chapters/?t=2", FixtureResponse(it)) }
		}

		fun chapter(slug: String, label: String): String = """
			<li class="wp-manga-chapter">
			<a href="https://readhunters.xyz/read/fixture/chapter-$slug">$label</a>
			<span class="chapter-release-date"><i>02/10/2026</i></span>
			</li>
		"""

		fun pagination(last: Int): String = "<div class='pagination'><a data-page='$last'>$last</a></div>"
	}
}
