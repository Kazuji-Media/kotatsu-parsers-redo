package org.koitharu.kotatsu.parsers.site.pt

import kotlinx.coroutines.test.runTest
import okhttp3.Response
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.site.madara.pt.LeitorDeManga

internal class MangaCatalogParserCompletenessTest {

	@Test
	fun detailsParsesSmallMediumAndLongChapterLists() = runTest {
		for (chapterCount in listOf(10, 50, 250)) {
			val details = LeitorDeManga(StaticDetailsContext(detailsHtml(chapterCount, chapterCount)))
				.getDetails(manga)
			val chapters = details.chapters.orEmpty()

			assertEquals(chapterCount, chapters.size)
			assertEquals((1..chapterCount).map(Int::toFloat), chapters.map { it.number })
			assertEquals("Chapter 1", chapters.first().title)
			assertTrue(chapters.first().uploadDate > 0L)
			assertEquals(chapterCount, chapters.map { it.id }.distinct().size)
		}
	}

	@Test
	fun detailsRejectsExplicitlyIncompleteChapterScan() = runTest {
		val parser = LeitorDeManga(StaticDetailsContext(detailsHtml(total = null, captured = 2, complete = false)))
		val error = try {
			parser.getDetails(manga)
			throw AssertionError("Expected incomplete chapter scan to be rejected")
		} catch (e: ParseException) {
			e
		}

		assertTrue(error.message.orEmpty().contains("incomplete", ignoreCase = true))
	}

	@Test
	fun detailsRejectsMissingCompletionMetadata() = runTest {
		val parser = LeitorDeManga(StaticDetailsContext("<html><body><h1>Dandadan</h1></body></html>"))

		assertParseException { parser.getDetails(manga) }
	}

	@Test
	fun detailsRejectsChapterCountThatDoesNotMatchParsedLinks() = runTest {
		val html = detailsHtml(total = null, captured = 3).replace("data-kotatsu-chapter-count=\"3\"", "data-kotatsu-chapter-count=\"2\"")
		val parser = LeitorDeManga(StaticDetailsContext(html))

		assertParseException { parser.getDetails(manga) }
	}

	@Test
	fun detailsDeduplicatesOverlappingChapterLinks() = runTest {
		val html = """
			<html data-kotatsu-chapter-total="2" data-kotatsu-chapter-count="2" data-kotatsu-chapter-complete="true">
				<body><main><h1>Dandadan</h1>
					<a data-kotatsu-chapter-url="/manga/dandadan/1" data-kotatsu-chapter-number="1"
						data-kotatsu-chapter-text="Capítulo 1 — First">Capítulo 1</a>
					<a data-kotatsu-chapter-url="/manga/dandadan/2" data-kotatsu-chapter-number="2"
						data-kotatsu-chapter-text="Capítulo 2 — Second">Capítulo 2</a>
					<a data-kotatsu-chapter-url="/manga/dandadan/2" data-kotatsu-chapter-number="2"
						data-kotatsu-chapter-text="Capítulo 2 — Second">Capítulo 2</a>
				</main></body>
			</html>
		""".trimIndent()
		val details = LeitorDeManga(StaticDetailsContext(html)).getDetails(manga)

		assertEquals(listOf(1f, 2f), details.chapters.orEmpty().map { it.number })
	}

	private class StaticDetailsContext(
		private val html: String,
	) : MangaLoaderContext() {

		private val delegate = MangaLoaderContextMock

		override val httpClient get() = delegate.httpClient
		override val cookieJar get() = delegate.cookieJar

		@Deprecated("Provide a base url")
		override suspend fun evaluateJs(script: String): String? = evaluateJs("", script, 10_000L)

		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String =
			JSONObject.quote(html)

		override fun getConfig(source: MangaSource): MangaSourceConfig = delegate.getConfig(source)
		override fun getDefaultUserAgent(): String = delegate.getDefaultUserAgent()

		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response =
			delegate.redrawImageResponse(response, redraw)

		override fun createBitmap(width: Int, height: Int): Bitmap = delegate.createBitmap(width, height)
	}

	private companion object {
		suspend fun assertParseException(block: suspend () -> Unit): ParseException {
			try {
				block()
			} catch (e: ParseException) {
				return e
			}
			throw AssertionError("Expected a parse exception")
		}

		val manga = Manga(
			id = 1L,
			title = "Dandadan",
			altTitles = emptySet(),
			url = "/manga/dandadan",
			publicUrl = "https://leitordemangas.com/manga/dandadan",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = MangaParserSource.LEITORDEMANGA,
		)

		fun detailsHtml(total: Int?, captured: Int, complete: Boolean? = true): String = buildString {
			append("<html data-kotatsu-chapter-total=\"").append(total ?: "")
				.append("\" data-kotatsu-chapter-count=\"").append(captured).append('"')
			if (complete != null) append(" data-kotatsu-chapter-complete=\"").append(complete).append('"')
			append("><body><main><h1>Dandadan</h1><p>Por Yukinobu Tatsu</p><ul>")
			for (number in captured downTo 1) {
				append("<li><a data-kotatsu-chapter-url=\"/manga/dandadan/").append(number)
					.append("\" data-kotatsu-chapter-number=\"").append(number)
					.append("\" data-kotatsu-chapter-text=\"Capítulo ").append(number).append(" — Chapter ")
					.append(number).append("\" data-kotatsu-upload-date=\"2026-11-01T00:00:00Z\">")
					.append("Capítulo ").append(number).append("</a></li>")
			}
			append("</ul></main></body></html>")
		}
	}
}
