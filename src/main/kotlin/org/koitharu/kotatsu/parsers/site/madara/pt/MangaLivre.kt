package org.koitharu.kotatsu.parsers.site.madara.pt

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.EnumSet

@MangaSourceParser("MANGALIVRE", "Manga Livre", "pt")
internal class MangaLivre(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGALIVRE, "mangalivre.blog", 24) {

	private val mangaPath = "/manga/"
	private val genrePath = "/genre/"
	private val chapterPath = "/capitulo/"

	init {
		paginator.firstPage = 0
		searchPaginator.firstPage = 0
	}

	override val withoutAjax = true

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = false,
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
	)

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val document = webClient.httpGet("https://$domain/").parseHtml()
		return document.select("a[href*='$genrePath']").mapNotNullToSet { anchor ->
			val href = anchor.attrAsRelativeUrlOrNull("href") ?: return@mapNotNullToSet null
			val slug = href.substringAfter(genrePath).substringBefore('/').substringBefore('?')
			val title = anchor.text().trim().replace(Regex("\\s+\\d+$"), "").trim()
			if (slug.isBlank() || title.isBlank()) {
				return@mapNotNullToSet null
			}
			MangaTag(slug, title, source)
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val tag = filter.tags.firstOrNull()?.key
		val basePath = if (tag.isNullOrBlank()) mangaPath else "$genrePath${tag.trim('/')}/"
		val pagePath = if (page > 0) "page/${page + 1}/" else ""
		val url = buildString {
			append("https://").append(domain).append(basePath).append(pagePath)
			val params = buildList {
				filter.query?.takeIf(String::isNotBlank)?.let { add("s=${it.urlEncoded()}") }
				when (order) {
					SortOrder.POPULARITY -> add("orderby=popular")
					SortOrder.UPDATED -> add("orderby=updated")
					else -> Unit
				}
			}
			if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	private fun parseMangaList(document: Document): List<Manga> {
		val links = document.select("a[href]").filter { anchor ->
			val href = anchor.attr("href").substringBefore('?').trimEnd('/')
			val slug = href.substringAfter(mangaPath, "")
			slug.isNotBlank() && !slug.startsWith("page/") && !slug.contains('/')
		}.distinctBy { it.attr("href").substringBefore('?').trimEnd('/') }

		return links.mapNotNull { anchor ->
			val href = anchor.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val path = href.substringBefore('?').trimEnd('/')
			val card = anchor.parents().firstOrNull { parent ->
				parent.selectFirst("img") != null
			} ?: anchor.parent() ?: anchor
			val title = sequenceOf(
				anchor.attr("title").trim(),
				anchor.text().trim(),
				card.selectFirst("h2, h3, h4")?.text()?.trim().orEmpty(),
			).firstOrNull(String::isNotBlank) ?: return@mapNotNull null
			val tags = card.select("a[href*='$genrePath']").mapNotNullToSet { tagAnchor ->
				val tagHref = tagAnchor.attrAsRelativeUrlOrNull("href") ?: return@mapNotNullToSet null
				val slug = tagHref.substringAfter(genrePath).substringBefore('/').substringBefore('?')
				val tagTitle = tagAnchor.text().trim().replace(Regex("\\s+\\d+$"), "").trim()
				if (slug.isBlank() || tagTitle.isBlank()) null else MangaTag(slug, tagTitle, source)
			}
			Manga(
				id = generateUid(path),
				url = path,
				publicUrl = path.toAbsoluteUrl(domain),
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = card.selectFirst("img")?.src(),
				tags = tags,
				state = null,
				authors = emptySet(),
				source = source,
			)
		}.distinctBy { it.id }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val url = manga.url.toAbsoluteUrl(domain)
		val document = webClient.httpGet(url).parseHtml()
		val title = document.selectFirst("h1")?.textOrNull() ?: manga.title
		val description = document.selectFirst(
			".synopsis, .manga-synopsis, .description, .manga-description, .entry-content",
		)?.html() ?: document.selectFirst("h2:contains(Sinopse), h3:contains(Sinopse), h4:contains(Sinopse)")
			?.parent()?.nextElementSibling()?.html()
		val tags = document.select("a[href*='$genrePath']").mapNotNullToSet { anchor ->
			val href = anchor.attrAsRelativeUrlOrNull("href") ?: return@mapNotNullToSet null
			val slug = href.substringAfter(genrePath).substringBefore('/').substringBefore('?')
			val tagTitle = anchor.text().trim().replace(Regex("\\s+\\d+$"), "").trim()
			if (slug.isBlank() || tagTitle.isBlank()) null else MangaTag(slug, tagTitle, source)
		}
		val chapters = parseChapters(document)
		val completeChapters = if (
			chapters.isNotEmpty() &&
			"carregar mais capítulo" in document.body().text().lowercase()
		) {
			val readerUrl = chapters.first().url.toAbsoluteUrl(domain)
			val readerDocument = webClient.httpGet(readerUrl).parseHtml()
			parseChapters(readerDocument).ifEmpty { chapters }
		} else {
			chapters
		}
		val pageText = document.body().text().lowercase()
		val state = when {
			"em andamento" in pageText || "ongoing" in pageText -> MangaState.ONGOING
			"completo" in pageText || "finalizado" in pageText || "completed" in pageText -> MangaState.FINISHED
			else -> null
		}
		return manga.copy(
			title = title,
			publicUrl = url,
			description = description,
			tags = tags,
			chapters = completeChapters,
			state = state,
			contentRating = if (isNsfwSource) ContentRating.ADULT else ContentRating.SAFE,
		)
	}

	private fun parseChapters(document: Document): List<MangaChapter> {
		val links = document.select("a[href*='$chapterPath']")
		return links.mapChapters(reversed = true) { _, anchor ->
			val href = anchor.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
			val path = href.substringBefore('?').trimEnd('/')
			val label = anchor.text().trim().ifBlank { anchor.attr("title").trim() }
			val number = Regex("(?i)(?:cap(?:ítulo|itulo)?\\.?\\s*)(\\d+(?:[.,]\\d+)?)")
				.find(label)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()
				?: Regex("(\\d+(?:[.,]\\d+)?)").find(path)?.groupValues?.get(1)
				?.replace(',', '.')?.toFloatOrNull()
			MangaChapter(
				id = generateUid(path),
				title = label.ifBlank { null },
				number = number ?: 0f,
				volume = 0,
				url = path,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}.distinctBy { it.id }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = chapter.url.toAbsoluteUrl(domain)
		val document = webClient.httpGet(url).parseHtml()
		val images = document.select(
			"img[alt^=Página], img[alt^=Page], .reader-content img, .reading-content img, .chapter-content img, .chapter-images img",
		)
		val pages = images.mapNotNull { image ->
			val imageUrl = image.src() ?: return@mapNotNull null
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}.distinctBy { it.id }
		if (pages.isEmpty()) {
			throw ParseException("Nenhuma página do capítulo foi encontrada", url)
		}
		return pages
	}
}
