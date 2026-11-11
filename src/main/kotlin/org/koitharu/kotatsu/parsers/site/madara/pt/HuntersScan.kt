package org.koitharu.kotatsu.parsers.site.madara.pt

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Protocol
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.network.OkHttpWebClient
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.removeSuffix
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat

@Broken
@MangaSourceParser("HUNTERSSCAN", "HuntersScan", "pt")
internal class HuntersScan(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.HUNTERSSCAN, "readhunters.xyz", pageSize = 50) {

	override val datePattern = "dd/MM/yyyy"
	override val tagPrefix = "manga-genre/"
	override val listUrl = "series-2/"

	// Custom HTTP/1.1 client to fix 421 SNI mismatch error
	private val http11Client = context.httpClient.newBuilder()
		.protocols(listOf(Protocol.HTTP_1_1))
		.build()

	// Custom webClient using HTTP/1.1
	private val http11WebClient = OkHttpWebClient(
		httpClient = http11Client,
		mangaSource = source,
	)

	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		return fetchAllChapters(manga)
	}

	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		return fetchAllChapters(
			Manga(
				id = generateUid(mangaUrl),
				url = mangaUrl,
				publicUrl = mangaUrl.toAbsoluteUrl(domain),
				title = "",
				altTitles = emptySet(),
				authors = emptySet(),
				tags = emptySet(),
				rating = RATING_UNKNOWN,
				state = MangaState.ONGOING,
				coverUrl = null,
				contentRating = ContentRating.SAFE,
				source = source,
			),
		)
	}

	private suspend fun fetchAllChapters(manga: Manga): List<MangaChapter> = coroutineScope {
		val baseUrl = "${manga.url.toAbsoluteUrl(domain).removeSuffix('/')}/ajax/chapters/?t="

		val firstPageDoc = http11WebClient.httpPost(baseUrl + "1", emptyMap()).parseHtml()
		val totalPages = extractTotalPages(firstPageDoc)
		val firstPageChapters = parseChapterPage(firstPageDoc, 1, requireChapters = totalPages > 1)

		if (totalPages <= 1) {
			return@coroutineScope firstPageChapters.mapChapters(reversed = true) { _, chapter -> chapter }
		}

		val remainingPagesChapters = (2..totalPages).chunked(MAX_PARALLELISM).flatMap { batch ->
			batch.map { page ->
				async {
					val doc = http11WebClient.httpPost(baseUrl + page, emptyMap()).parseHtml()
					parseChapterPage(doc, page, requireChapters = true)
				}
			}.awaitAll().flatten()
		}

		val allChapters = firstPageChapters + remainingPagesChapters
		allChapters.mapChapters(reversed = true) { _, chapter -> chapter }
	}

	private fun parseChapterPage(doc: Document, page: Int, requireChapters: Boolean): List<MangaChapter> {
		val elements = doc.select(selectChapter)
		if (requireChapters && elements.isEmpty()) {
			throw ParseException("HuntersScan chapter page $page is empty", doc.location())
		}
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		return elements.map { parseChapterElement(it, dateFormat) }
	}

	private fun extractTotalPages(doc: Document): Int {
		val pagination = doc.selectFirst(".pagination") ?: return 1

		return pagination.select("a[data-page]").mapNotNull { it.attr("data-page").toIntOrNull() }.maxOrNull() ?: 1
	}

	private fun parseChapterElement(li: Element, dateFormat: SimpleDateFormat): MangaChapter {
		val a = li.selectFirstOrThrow("a")
		val href = a.attrAsRelativeUrl("href")
		val label = a.ownText().ifEmpty { a.text() }.trim()
		val numberMatch = CHAPTER_NUMBER.find(label) ?: LEADING_NUMBER.find(label)
		val volumeMatch = VOLUME_NUMBER.find(label)
		val title = label.replace(CHAPTER_NUMBER, "").replace(VOLUME_NUMBER, "")
			.replace(LEADING_NUMBER, "").trim(' ', '-', ':', '|', '–', '—').takeIf { it.isNotEmpty() }

		return MangaChapter(
			id = generateUid(href),
			title = title,
			number = numberMatch?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull() ?: 0f,
			volume = volumeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0,
			url = href + stylePage,
			uploadDate = parseChapterDate(dateFormat, li.selectFirst(selectDate)?.textOrNull()),
			source = source,
			scanlator = null,
			branch = null,
		)
	}

	private companion object {
		const val MAX_PARALLELISM = 4
		val CHAPTER_NUMBER = Regex(
			"""\b(?:cap[ií]tulo|cap\.?|chapter|ch\.?)\s*#?\s*(\d+(?:[.,]\d+)?)""",
			RegexOption.IGNORE_CASE,
		)
		val LEADING_NUMBER = Regex("""^\s*#?(\d+(?:[.,]\d+)?)(?=\s|[-:|–—]|$)""")
		val VOLUME_NUMBER = Regex("""\b(?:volume|vol\.?)\s*(\d+)""", RegexOption.IGNORE_CASE)
	}
}

