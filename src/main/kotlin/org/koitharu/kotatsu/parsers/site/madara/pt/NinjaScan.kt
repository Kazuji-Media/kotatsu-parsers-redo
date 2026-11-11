package org.koitharu.kotatsu.parsers.site.madara.pt

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.selectLast
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl

@MangaSourceParser("NINJASCAN", "NinjaComics", "pt")
internal class NinjaScan(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.NINJASCAN, "ninjacomics.xyz") {

    override val datePattern: String = "dd 'de' MMMM 'de' yyyy"

    override suspend fun getDetails(manga: Manga): Manga {
        val document = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val href = document.selectFirst("head meta[property='og:url']")?.attr("content")
            ?.toRelativeUrl(domain) ?: manga.url
        val chapters = if (document.select(selectTestAsync).isEmpty()) {
            loadChapters(href, document)
        } else {
            getChapters(manga, document)
        }
        val stateText = document.selectFirst(selectState)?.selectLast("div.summary-content")?.text()?.lowercase().orEmpty()
        val state = when (stateText) {
            in ongoing -> MangaState.ONGOING
            in finished -> MangaState.FINISHED
            in abandoned -> MangaState.ABANDONED
            in paused -> MangaState.PAUSED
            else -> null
        }
        val alternatives = document.selectFirst(selectAlt)?.textOrNull()
            ?.split(',', ';')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
        val authors = document.select(".author-content a, .artist-content a, .mg_author a, .mg_artists a")
            .mapNotNull { it.textOrNull() }.toSet()
        val rating = document.selectFirst("#averagerate, .post-rating span.total_votes")?.textOrNull()
            ?.replace(',', '.')?.toFloatOrNull()?.takeIf { it in 0f..5f }?.div(5f)
        return manga.copy(
            title = document.selectFirst("h1")?.textOrNull() ?: manga.title,
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            tags = document.select(selectGenre).mapToSet { createMangaTag(it) }.filterNotNull().toSet(),
            description = document.select(selectDesc).html(),
            altTitles = alternatives ?: manga.altTitles,
            authors = authors.ifEmpty { manga.authors },
            rating = rating ?: manga.rating,
            state = state,
            chapters = chapters,
            contentRating = if (document.selectFirst(".adult-confirm") != null || isNsfwSource) {
                ContentRating.ADULT
            } else {
                ContentRating.SAFE
            },
        )
    }

    override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> =
        super.getChapters(manga, doc).withRealNumbers()

    override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> =
        super.loadChapters(mangaUrl, document).withRealNumbers()

    private fun List<MangaChapter>.withRealNumbers(): List<MangaChapter> = map { chapter ->
        val number = chapter.title?.let { CHAPTER_NUMBER.find(it)?.groupValues?.get(1) }
            ?.replace(',', '.')?.toFloatOrNull() ?: 0f
        chapter.copy(number = number)
    }

    private companion object {
        val CHAPTER_NUMBER = Regex("""\bcap(?:[ií]tulo|\.?)\s*(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)
    }
}
