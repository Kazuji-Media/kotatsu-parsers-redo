package org.koitharu.kotatsu.parsers.site.madara.pt

import org.json.JSONArray
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrlOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.Locale

internal abstract class MangaCatalogParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	private val catalogPath: String,
	private val searchPath: String,
	private val supportsAlphabeticalSort: Boolean = false,
) : PagedMangaParser(context, source, PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain(domain)

	override val defaultSortOrder: SortOrder
		get() = SortOrder.POPULARITY

	override val availableSortOrders: Set<SortOrder> = if (supportsAlphabeticalSort) {
		setOf(SortOrder.POPULARITY, SortOrder.ALPHABETICAL)
	} else {
		setOf(SortOrder.POPULARITY)
	}

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(isSearchSupported = true)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.set("Accept-Language", "pt-BR,pt;q=0.9")
		.build()

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim()?.takeIf(String::isNotEmpty)
		val url = buildString {
			append("https://")
			append(domain)
			append(if (query == null) catalogPath else searchPath)
			val queryParameters = buildList {
				if (query != null) add("q=${query.urlEncoded()}")
				if (supportsAlphabeticalSort && order == SortOrder.ALPHABETICAL) add("sort=title")
			}
			if (queryParameters.isNotEmpty()) append('?').append(queryParameters.joinToString("&"))
		}
		val document = loadRenderedDocument(url, listScript(page))
		ensureAccessIsAvailable(document, url)
		val capturedPage = document.selectFirst("[data-kotatsu-list-page]")
			?.attr("data-kotatsu-list-page")
			?.toIntOrNull() ?: 1
		if (capturedPage < page) return emptyList()
		return parseMangaList(document)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaUrl = manga.publicUrl.ifBlank { manga.url.toAbsoluteUrl(domain) }
		val document = loadRenderedDocument(mangaUrl, DETAILS_SCRIPT)
		ensureAccessIsAvailable(document, mangaUrl)
		val chapterScan = document.selectFirst("[data-kotatsu-chapter-count]")
			?: throw ParseException("$domain did not confirm chapter list completion", mangaUrl)
		val expectedChapterCount = chapterScan.attr("data-kotatsu-chapter-total").toIntOrNull()
		val capturedChapterCount = chapterScan.attr("data-kotatsu-chapter-count").toIntOrNull()
		if (capturedChapterCount == null) {
			throw ParseException("$domain returned an invalid chapter count", mangaUrl)
		}
		if (expectedChapterCount != null && capturedChapterCount < expectedChapterCount) {
			throw ParseException(
				"$domain loaded $capturedChapterCount of $expectedChapterCount chapters",
				mangaUrl,
			)
		}
		if (expectedChapterCount != null && capturedChapterCount > expectedChapterCount) {
			throw ParseException(
				"$domain returned $capturedChapterCount chapters but reported $expectedChapterCount",
				mangaUrl,
			)
		}
		if (chapterScan.attr("data-kotatsu-chapter-complete") == "false") {
			throw ParseException("$domain returned an incomplete chapter list", mangaUrl)
		}
		if (expectedChapterCount == null && chapterScan.attr("data-kotatsu-chapter-complete") != "true") {
			throw ParseException("$domain did not confirm chapter list completion", mangaUrl)
		}

		val bodyText = document.body()?.wholeText().orEmpty()
		val title = document.selectFirst("main h1, h1")?.text()?.trim()?.takeIf(String::isNotEmpty)
			?: manga.title
		val description = document.selectFirst("[data-kotatsu-description]")?.text()?.trim()
			?.takeIf(String::isNotEmpty)
			?: parseDescription(document)
		val altTitle = parseAltTitle(bodyText, title)
		val tags = document.select("a[href*='genero='], a[href*='tema='], a[href*='demografia='], a[href*='classificacao=']")
			.mapNotNullTo(linkedSetOf()) { link ->
				val label = link.text().trim()
				val path = link.attrAsRelativeUrl("href")
				if (label.isEmpty() || path.isEmpty()) null else MangaTag(
					key = path,
					title = label,
					source = source,
				)
			}
		val author = AUTHOR_REGEX.find(bodyText)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
		val chapters = parseChapters(document, manga)
		if (capturedChapterCount != chapters.size) {
			throw ParseException(
				"$domain captured $capturedChapterCount chapter links but parsed ${chapters.size}",
				mangaUrl,
			)
		}
		val coverUrl = document.select("img[alt]")
			.firstOrNull {
				it.attr("alt").equals(title, ignoreCase = true) ||
					(it.attr("alt").contains(title, ignoreCase = true) && it.attr("alt").contains("capa", true))
			}
			?.let(::imageUrl)
		val allText = document.body()?.text().orEmpty()

		return manga.copy(
			title = title,
			altTitles = altTitle?.let(::setOf) ?: manga.altTitles,
			coverUrl = coverUrl ?: manga.coverUrl,
			description = description ?: manga.description,
			tags = tags.ifEmpty { manga.tags },
			authors = author?.let(::setOf) ?: manga.authors,
			state = parseState(allText) ?: manga.state,
			rating = parseRating(allText.substringBefore("Sinopse")) ?: manga.rating,
			contentRating = parseContentRating(allText) ?: manga.contentRating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val document = loadRenderedDocument(chapterUrl, PAGES_SCRIPT)
		ensureAccessIsAvailable(document, chapterUrl)

		val preloadedScript = document.selectFirst("#chapter_preloaded_images")?.data()
		if (!preloadedScript.isNullOrBlank()) {
			val rawArray = preloadedScript.substringAfter("var chapter_preloaded_images = [", "")
				.substringBefore("]", "")
			if (rawArray.isNotBlank()) {
				val images = JSONArray("[$rawArray]")
				if (images.length() > 0) return (0 until images.length()).map { index ->
					val imageUrl = images.getString(index).toRelativeUrl(domain)
					MangaPage(
						id = generateUid(imageUrl),
						url = imageUrl,
						preview = null,
						source = source,
					)
				}
			}
		}

		val pages = document.select("img[data-kotatsu-page-url]")
			.mapNotNull { image ->
				val url = image.attrAsRelativeUrl("data-kotatsu-page-url")
				if (url.isBlank() || url.startsWith("data:")) null else MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
			.distinctBy { it.url }
		if (pages.isEmpty()) throw ParseException("No chapter pages found", chapterUrl)
		return pages
	}

	private fun parseMangaList(document: Document): List<Manga> = document.select("a[data-kotatsu-manga-url]")
		.mapNotNull { link ->
			val url = link.attrAsRelativeUrl("data-kotatsu-manga-url")
			val title = link.attr("data-kotatsu-manga-title").trim().ifEmpty { link.text().trim() }
			if (url.isEmpty() || title.isEmpty()) return@mapNotNull null
			val cardText = link.text().lowercase(Locale.ROOT)
			val cover = link.attrAsRelativeUrlOrNull("data-kotatsu-cover")
			Manga(
				id = generateUid(url),
				title = title,
				altTitles = emptySet(),
				url = url,
				publicUrl = url.toAbsoluteUrl(domain),
				contentRating = parseContentRating(cardText),
				coverUrl = cover?.toAbsoluteUrl(domain),
				tags = emptySet(),
				rating = parseRating(cardText) ?: RATING_UNKNOWN,
				state = parseState(cardText),
				authors = emptySet(),
				description = null,
				chapters = null,
				source = source,
			)
		}
		.distinctBy { it.url }

	private fun parseChapters(document: Document, manga: Manga): List<MangaChapter> =
		document.select("a[data-kotatsu-chapter-url]")
			.mapNotNull { link ->
				val url = link.attrAsRelativeUrl("data-kotatsu-chapter-url")
				val number = link.attr("data-kotatsu-chapter-number").toFloatOrNull()
					?: url.substringBefore('?').substringAfterLast('/').replace(',', '.').toFloatOrNull()
					?: return@mapNotNull null
				val text = link.attr("data-kotatsu-chapter-text").ifBlank { link.text() }.trim()
				val title = CHAPTER_PREFIX_REGEX.replace(text, "")
					.replace(DATE_SUFFIX_REGEX, "")
					.replace(Regex("\\s+"), " ")
					.trim()
					.takeIf(String::isNotEmpty)
				MangaChapter(
					id = generateUid(url),
					title = title,
					number = number,
					volume = 0,
					url = url,
					scanlator = null,
					uploadDate = parseUploadDate(link.attr("data-kotatsu-upload-date"), text),
					branch = null,
					source = source,
				)
			}
			.distinctBy { it.url }
			.sortedBy { it.number }

	private fun parseDescription(document: Document): String? {
		val heading = document.select("h2, h3, h4").firstOrNull { it.text().equals("Sinopse", ignoreCase = true) }
		return heading?.parent()?.selectFirst("p")?.text()?.trim()?.takeIf(String::isNotEmpty)
			?: heading?.nextElementSibling()?.text()?.trim()?.takeIf(String::isNotEmpty)
	}

	private fun parseAltTitle(text: String, title: String): String? {
		val titleIndex = text.indexOf(title, ignoreCase = true)
		if (titleIndex < 0) return null
		val metadataText = text.substring(titleIndex + title.length).trimStart()
		return ALT_TITLE_REGEX.find(metadataText)?.groupValues?.getOrNull(1)?.trim()
			?.takeIf { it.isNotEmpty() && !it.equals(title, ignoreCase = true) }
	}

	private fun parseUploadDate(attribute: String, text: String): Long {
		val value = attribute.takeIf(String::isNotBlank)
			?: DATE_SUFFIX_REGEX.find(text)?.value?.trim()
			?: return 0L
		runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()?.let { return it }
		val utcDateMillis = runCatching {
			java.time.LocalDate.parse(value.take(10))
				.atStartOfDay(java.time.ZoneOffset.UTC)
				.toInstant()
				.toEpochMilli()
		}.getOrNull()
		if (utcDateMillis != null) return utcDateMillis
		return runCatching {
			val normalized = value.replace(".", "")
			SimpleDateFormat(DATE_FORMAT_PATTERN, LOCALE_PT_BR).parse(normalized)?.time
				?: SimpleDateFormat(DATE_FORMAT_LONG_PATTERN, LOCALE_PT_BR).parse(normalized)?.time
		}.getOrNull() ?: 0L
	}

	private fun imageUrl(image: Element): String? = sequenceOf(
		image.attr("data-src"),
		image.attr("data-lazy-src"),
		image.attr("src"),
	).firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
		?.toAbsoluteUrl(domain)

	private fun parseState(text: String): MangaState? {
		val normalized = text.lowercase(Locale.ROOT)
		return when {
			"em lançamento" in normalized || "em andamento" in normalized || "ongoing" in normalized -> MangaState.ONGOING
			"completo" in normalized || "finalizado" in normalized || "finalizada" in normalized -> MangaState.FINISHED
			"em hiato" in normalized || "pausado" in normalized || "pausada" in normalized -> MangaState.PAUSED
			"cancelado" in normalized || "cancelada" in normalized || "abandonado" in normalized -> MangaState.ABANDONED
			else -> null
		}
	}

	private fun parseContentRating(text: String): ContentRating? {
		val normalized = text.lowercase(Locale.ROOT)
		return when {
			"18+" in normalized || "xxx" in normalized || "adulto" in normalized -> ContentRating.ADULT
			"sugestivo" in normalized || "ecchi" in normalized -> ContentRating.SUGGESTIVE
			else -> null
		}
	}

	private fun parseRating(text: String): Float? = RATING_REGEX.find(text)?.groupValues?.getOrNull(1)
		?.replace(',', '.')?.toFloatOrNull()?.div(5f)

	private fun ensureAccessIsAvailable(document: Document, url: String) {
		if (requiresManualBrowserAction(document)) context.requestBrowserAction(this, url)
	}

	private fun requiresManualBrowserAction(document: Document): Boolean {
		val text = document.body()?.text().orEmpty().lowercase(Locale.ROOT)
		val readerGate = "um passo para ler" in text && (
			document.select("a[href]").any { it.text().contains("acesse aqui para continuar", ignoreCase = true) } ||
			("bloqueador detectado" in text && document.select("button").any {
				it.text().contains("verificar novamente", ignoreCase = true)
			})
		)
		val verificationChallenge = document.selectFirst(MANUAL_ACTION_SELECTORS) != null || listOf(
			"checking your browser",
			"verify you are human",
			"human verification",
			"security verification",
			"verifique se você é humano",
			"verifique que você é humano",
		).any(text::contains)
		return readerGate || verificationChallenge
	}

	private suspend fun loadRenderedDocument(url: String, script: String): Document {
		val raw = context.evaluateJs(url, script, WEBVIEW_TIMEOUT)
			?: throw ParseException("Failed to load page", url)
		val html = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw
		return Jsoup.parse(html, url)
	}

	private fun listScript(page: Int): String = LIST_SCRIPT.replace("__KOTATSU_PAGE__", page.toString())

	private companion object {
		const val PAGE_SIZE = 20
		const val WEBVIEW_TIMEOUT = 45000L
		const val DATE_FORMAT_PATTERN = "d 'de' MMM 'de' yyyy"
		const val DATE_FORMAT_LONG_PATTERN = "d 'de' MMMM 'de' yyyy"
		val LOCALE_PT_BR = Locale("pt", "BR")
		val AUTHOR_REGEX = Regex("(?i)\\bPor\\s+(.+?)(?=\\s+(?:MANGA|MANHWA|MANHUA|NOVEL|ONE.?SHOT)\\b)")
		val ALT_TITLE_REGEX = Regex("(?i)^([\\p{L}\\d][\\p{L}\\d\\s'’.-]{1,39}?)\\s+(?=(?:Por|By)\\b)")
		val RATING_REGEX = Regex("(?<![\\d.])([0-5][.,]\\d{1,2})(?![\\d.])")
		val CHAPTER_PREFIX_REGEX = Regex("(?i)^Cap(?:ítulo|itulo|\\.)\\s*\\d+(?:[.,]\\d+)?\\s*(?:[-–—:]\\s*)?")
		val DATE_SUFFIX_REGEX = Regex("(?i)\\s+\\d{1,2}\\s+de\\s+[a-zç]{3,12}\\.?\\s+de\\s+\\d{4}\\s*$")
		const val MANUAL_ACTION_SELECTORS = "#challenge-form, #challenge-running, #cf-challenge-running, " +
			".cf-browser-verification, .cf-turnstile, [name=\"cf-turnstile-response\"], " +
			"iframe[src*=\"challenges.cloudflare.com\"], .g-recaptcha, iframe[src*=\"recaptcha\"], " +
			".h-captcha, iframe[src*=\"hcaptcha.com\"]"

		val LIST_SCRIPT = """
			(() => {
				const targetPage = __KOTATSU_PAGE__;
				const key = "__kazujiMangaCatalogListState";
				const state = window[key] || (window[key] = {
					started: Date.now(), page: 1, pending: false, previousLinks: "", pendingSince: 0,
				});
				const text = () => document.body?.innerText || "";
				const links = () => Array.from(document.querySelectorAll('a[href]'))
					.filter(link => {
						const path = new URL(link.href, location.href).pathname.replace(/\/+$/, "");
						const parts = path.split("/").filter(Boolean);
						return parts.length === 2 && ["manga", "manhwa", "manhua", "one-shot"].includes(parts[0]);
					})
					.map(link => link.getAttribute("href") || "").join("|");
				const finish = page => {
					for (const link of Array.from(document.querySelectorAll('a[href]'))) {
						const url = new URL(link.href, location.href);
						const path = url.pathname.replace(/\/+$/, "");
						const parts = path.split("/").filter(Boolean);
						if (parts.length !== 2 || !["manga", "manhwa", "manhua", "one-shot"].includes(parts[0])) continue;
						const titleNode = link.querySelector("h1, h2, h3, h4") || link;
						const image = link.querySelector("img");
						const title = link.getAttribute("title") || link.getAttribute("aria-label") ||
							titleNode.innerText?.trim() || image?.getAttribute("alt")?.replace(/^(?:capa|cover)\s+(?:de\s+)?/i, "") || "";
						const imageUrl = image && (image.currentSrc || image.getAttribute("data-src") ||
							image.getAttribute("data-lazy-src") || image.getAttribute("src")) || "";
						link.setAttribute("data-kotatsu-manga-url", path);
						link.setAttribute("data-kotatsu-manga-title", title);
						if (imageUrl) link.setAttribute("data-kotatsu-cover", imageUrl);
					}
					document.documentElement.setAttribute("data-kotatsu-list-page", String(page));
					return document.documentElement.outerHTML || "";
				};

				if (document.querySelector('#challenge-form, .cf-turnstile, iframe[src*="challenges.cloudflare.com"]')) {
					return finish(state.page);
				}
				const pageMatch = text().match(/Página\s*(\d+)\s*de\s*(\d+)/i);
				const currentLinks = links();
				if (state.pending) {
					if (pageMatch && Number(pageMatch[1]) > state.page) {
						state.page = Number(pageMatch[1]);
						state.pending = false;
					} else if (!pageMatch && currentLinks && currentLinks !== state.previousLinks) {
						state.page++;
						state.pending = false;
					} else if (Date.now() - state.pendingSince > 12000) {
						return finish(state.page);
					} else {
						return null;
					}
				} else if (pageMatch) {
					state.page = Number(pageMatch[1]);
				}
				if (currentLinks && state.page >= targetPage) return finish(state.page);
				if (Date.now() - state.started > 30000) return finish(state.page);
				if (currentLinks) {
					const next = Array.from(document.querySelectorAll("button"))
						.find(button => /próxima página/i.test(button.getAttribute("aria-label") || button.innerText || ""));
					if (!next || next.disabled) return finish(state.page);
					state.previousLinks = currentLinks;
					state.pending = true;
					state.pendingSince = Date.now();
					next.click();
					return null;
				}
				if (/0\s+(?:obras?|resultados?)|nenhum resultado|nenhuma obra/i.test(text())) return finish(state.page);
				return null;
			})()
		""".trimIndent()

		val DETAILS_SCRIPT = """
			(() => {
				const key = "__kazujiMangaCatalogDetailsState";
				const state = window[key] || (window[key] = {
					started: Date.now(),
					pending: false,
					previousCount: 0,
					pendingSince: 0,
					linksDirty: true,
					chapterLinks: [],
					chapterList: null,
					observerRoot: null,
					observer: null,
					result: null,
					total: null,
					totalDirty: true,
					noMoreSince: 0,
					growthSeenAt: 0,
					pumping: false,
					loadMoreButton: null,
				});
				const text = () => document.body?.innerText || "";
				const chapterPath = link => {
					const path = new URL(link.href, location.href).pathname.replace(/\/+$/, "");
					const work = location.pathname.replace(/\/+$/, "");
					const chapter = path.startsWith(work + "/") ? path.substring(work.length + 1) : "";
					return chapter && !chapter.includes("/") && /^\d+(?:[.,]\d+)?(?:-\d+)?$/.test(chapter)
						? path
						: null;
				};
				const scanChapterLinks = () => {
					const lists = Array.from(document.querySelectorAll("ul, ol"))
						.map(list => {
							const unique = new Map();
							for (const link of Array.from(list.querySelectorAll("li a[href]"))) {
								const path = chapterPath(link);
								if (path && !unique.has(path)) unique.set(path, link);
							}
							return { list, links: Array.from(unique.values()) };
						})
						.filter(item => item.links.length > 0)
						.sort((left, right) => right.links.length - left.links.length);
					state.chapterList = lists[0]?.list || null;
					return lists[0]?.links || [];
				};
				const chapterLinks = () => {
					if (!state.observer || state.linksDirty) {
						state.chapterLinks = scanChapterLinks();
						state.linksDirty = false;
						state.totalDirty = true;
					}
					return state.chapterLinks;
				};
				const observeChapterList = () => {
					const root = state.chapterList?.parentElement || state.chapterList;
					if (!root || state.observerRoot === root || typeof MutationObserver === "undefined") return;
					state.observer?.disconnect();
					state.observerRoot = root;
					state.observer = new MutationObserver(() => {
						state.linksDirty = true;
						state.totalDirty = true;
						state.loadMoreButton = null;
						pump();
					});
					state.observer.observe(root, {
						subtree: true,
						childList: true,
						characterData: true,
						attributes: true,
					});
				};
				const readTotal = () => {
					if (state.totalDirty) {
						const match = text().match(/Capítulos?\s*\(\s*(\d+)\s*\)|([0-9]+)\s+capítulos?/i);
						if (match) state.total = Number(match[1] || match[2]);
						state.totalDirty = false;
					}
					return state.total;
				};
				const findLoadMore = () => {
					if (state.loadMoreButton?.isConnected && /carregar mais/i.test(state.loadMoreButton.innerText || "")) {
						return state.loadMoreButton;
					}
					state.loadMoreButton = Array.from(document.querySelectorAll("button"))
						.find(button => /carregar mais/i.test(button.innerText || "")) || null;
					return state.loadMoreButton;
				};
				const finish = (total, complete) => {
					if (state.result) return state.result;
					const links = scanChapterLinks();
					state.chapterLinks = links;
					state.linksDirty = false;
					for (const link of links) {
						const path = new URL(link.href, location.href).pathname.replace(/\/+$/, "");
						const number = path.substring(path.lastIndexOf("/") + 1);
						const time = link.querySelector("time[datetime]");
						link.setAttribute("data-kotatsu-chapter-url", path);
						link.setAttribute("data-kotatsu-chapter-number", number.replace(",", "."));
						link.setAttribute("data-kotatsu-chapter-text", link.innerText?.trim() || "");
						if (time?.getAttribute("datetime")) {
							link.setAttribute("data-kotatsu-upload-date", time.getAttribute("datetime"));
						}
					}
					const root = document.documentElement;
					root.setAttribute("data-kotatsu-chapter-total", total == null ? "" : String(total));
					root.setAttribute("data-kotatsu-chapter-count", String(links.length));
					root.setAttribute("data-kotatsu-chapter-complete", String(complete));
					const heading = Array.from(document.querySelectorAll("h1, h2, h3, h4"))
						.find(item => /sinopse/i.test(item.innerText || ""));
					const description = heading?.parentElement?.querySelector("p") || heading?.nextElementSibling;
					if (description?.innerText?.trim()) description.setAttribute("data-kotatsu-description", "");
					state.observer?.disconnect();
					state.result = root.outerHTML || "";
					return state.result;
				};
				function pump() {
					if (state.result || state.pumping) return;
					state.pumping = true;
					try {
						if (/um passo para ler/i.test(text()) ||
							document.querySelector("#challenge-form, .cf-turnstile")) {
							finish(null, false);
							return;
						}
						if (!document.querySelector("h1")) {
							if (Date.now() - state.started > 25000) finish(null, false);
							return;
						}
						if (!state.observer) state.linksDirty = true;
						const links = chapterLinks();
						observeChapterList();
						const total = readTotal();
						if (total != null && total > 0 && links.length === total) {
							finish(total, true);
							return;
						}
						if (total != null && links.length > total) {
							finish(total, false);
							return;
						}
						if (Date.now() - state.started > 40000) {
							finish(total, total != null && links.length >= total);
							return;
						}
						if (state.pending) {
							if (links.length > state.previousCount) {
								state.pending = false;
								state.pendingSince = 0;
								state.growthSeenAt = Date.now();
								state.noMoreSince = 0;
								return;
							} else if (Date.now() - state.pendingSince > 12000) {
								finish(total, false);
								return;
							} else {
								return;
							}
						}
						if (state.growthSeenAt) {
							if (Date.now() - state.growthSeenAt < 500) return;
							state.growthSeenAt = 0;
						}
						const loadMore = findLoadMore();
						if (loadMore && !loadMore.disabled && links.length > 0) {
							state.noMoreSince = 0;
							state.previousCount = links.length;
							state.pending = true;
							state.pendingSince = Date.now();
							loadMore.click();
							return;
						}
						if (links.length > 0) {
							if (!state.noMoreSince) state.noMoreSince = Date.now();
							if (Date.now() - state.noMoreSince >= 2000) {
								finish(total, total == null || links.length >= total);
							}
							return;
						}
						if (Date.now() - state.started > 40000) finish(total, false);
					} finally {
						state.pumping = false;
					}
				}
				pump();
				return state.result;
			})()
		""".trimIndent()

		val PAGES_SCRIPT = """
			(() => {
				const key = "__kazujiMangaCatalogPagesState";
				const state = window[key] || (window[key] = { started: Date.now(), previousCount: 0, stablePasses: 0 });
				const text = () => document.body?.innerText || "";
				if (/um passo para ler/i.test(text()) || document.querySelector("#challenge-form, .cf-turnstile")) {
					return document.documentElement?.outerHTML || "";
				}
				const sourceUrl = image => [
					image.currentSrc,
					image.getAttribute("data-src"),
					image.getAttribute("data-lazy-src"),
					image.getAttribute("data-original"),
					image.getAttribute("src"),
				].find(value => value && !value.startsWith("data:")) || "";
				const reader = document.querySelector(
					'[data-reader], [class*="reader"], [id*="reader"], .reading-content, .chapter-content, main',
				);
				let images = Array.from(document.querySelectorAll('img[alt]'))
					.filter(image => /^(?:página|pagina|page)\s*\d+/i.test((image.getAttribute("alt") || "").trim()));
				if (images.length === 0 && reader) images = Array.from(reader.querySelectorAll("img"));
				images = images.filter(image => {
					const url = sourceUrl(image);
					return url && !/(?:^|[\W_])(?:advert|advertisement|ads?|banner|avatar|logo|cover|poster)(?:[\W_]|$)/i.test(
						url + " " + (image.getAttribute("alt") || ""),
					);
				});
				for (const image of images) {
					image.scrollIntoView({ block: "center" });
					image.setAttribute("data-kotatsu-page-url", sourceUrl(image));
				}
				if (images.length === 0) {
					return Date.now() - state.started > 30000 ? document.documentElement?.outerHTML || "" : null;
				}
				if (images.length === state.previousCount) state.stablePasses++;
				else { state.previousCount = images.length; state.stablePasses = 0; }
				if (state.stablePasses >= 2 || Date.now() - state.started >= 38000) {
					return document.documentElement?.outerHTML || "";
				}
				return null;
			})()
		""".trimIndent()
	}
}

@MangaSourceParser("LEITORDEMANGA", "Leitor de Mangás", "pt")
internal class LeitorDeManga(context: MangaLoaderContext) :
	MangaCatalogParser(
		context,
		MangaParserSource.LEITORDEMANGA,
		"leitordemangas.com",
		"/catalogo",
		"/catalogo",
		supportsAlphabeticalSort = true,
	)

@MangaSourceParser("MANGASBRASUKAS", "Mangas Brasukas", "pt")
internal class MangasBrasukas(context: MangaLoaderContext) :
	MangaCatalogParser(context, MangaParserSource.MANGASBRASUKAS, "mangasbrasuka.org", "/catalogo", "/buscar")
