package org.koitharu.kotatsu.parsers.site.madara.pt

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.requireSrc
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import java.text.SimpleDateFormat

@MangaSourceParser("BORUTOEXPLORER", "BorutoExplorer", "pt")
internal class BorutoExplorer(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.BORUTOEXPLORER, "leitor.borutoexplorer.com.br", 10) {
    override val datePattern: String = "dd 'de' MMMMM 'de' yyyy"

    override suspend fun getDetails(manga: Manga): Manga = super.getDetails(manga).copy(url = manga.url)

    override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
        val elements = doc.select(selectChapter)
        if (elements.isEmpty()) return loadChapters(manga.url, doc)
        val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
        return elements.mapChapters(reversed = true) { _, element ->
            val link = element.selectFirstOrThrow("a")
            val href = link.attrAsRelativeUrl("href")
            val date = element.selectFirst(".chapter-release-date a[title]")?.attr("title")
                ?.takeIf(String::isNotBlank)
                ?: element.selectFirst("a.c-new-tag")?.attr("title")?.takeIf(String::isNotBlank)
                ?: element.selectFirst(selectDate)?.text()
            enrichChapter(
                MangaChapter(
                    id = generateUid(href),
                    title = link.text(),
                    number = 0f,
                    volume = 0,
                    url = href + stylePage,
                    uploadDate = parseChapterDate(dateFormat, date),
                    source = source,
                    scanlator = null,
                    branch = null,
                ),
            )
        }
    }

    override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> =
        super.loadChapters(mangaUrl, document).map(::enrichChapter)

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = chapter.url.toAbsoluteUrl(domain)
        val document = webClient.httpGet(url).parseHtml()
        if (document.selectFirst(selectRequiredLogin) != null) throw AuthRequiredException(source)
        if (document.getElementById("chapter-protector-data") != null) return super.getPages(chapter)
        val reader = document.selectFirst(".reading-content")
            ?: throw ParseException("BorutoExplorer reader was not found", url)
        val pages = reader.select("img.wp-manga-chapter-img").map { image ->
            val imageUrl = image.requireSrc().toRelativeUrl(domain)
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
        if (pages.isEmpty()) {
            throw ParseException(
                if (reader.text().isNotBlank()) {
                    "BorutoExplorer text chapters are not supported by this manga reader"
                } else {
                    "BorutoExplorer did not return any chapter images"
                },
                url,
            )
        }
        return pages
    }

    private fun enrichChapter(chapter: MangaChapter): MangaChapter {
        val label = chapter.title.orEmpty().trim()
        val number = CHAPTER_URL.find(chapter.url)?.groupValues?.get(1)
            ?: CHAPTER_LABEL.find(label)?.groupValues?.get(1)
        val title = label.replaceFirst(CHAPTER_LABEL, "").trim().trimStart('-', '–', '—', ':').trim()
        return chapter.copy(
            number = number?.replace(',', '.')?.toFloatOrNull() ?: 0f,
            volume = VOLUME_URL.find(chapter.url)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            title = title.takeIf(String::isNotEmpty),
        )
    }

    private companion object {
        val CHAPTER_URL = Regex("/capitulo-([0-9]+(?:[.,][0-9]+)?)(?=/|\\?|$)", RegexOption.IGNORE_CASE)
        val VOLUME_URL = Regex("/volume-([0-9]+)(?=/|$)", RegexOption.IGNORE_CASE)
        val CHAPTER_LABEL = Regex("^(?:cap[ií]tulo|cap\\.?)\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
    }
}
