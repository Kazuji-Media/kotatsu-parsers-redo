package org.koitharu.kotatsu.parsers.site.pt

import com.koushikdutta.quack.QuackContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock

internal class NexusMangasSearchScriptTest {

	@Test
	fun waitsForResultsAfterTheTransientEmptyState() {
		val script = NexusMangas(MangaLoaderContextMock).searchScript("One Piece")
		QuackContext.create().use { javascript ->
			javascript.evaluate(
				"""
					var window = {};
				var input = { value: "One Piece" };
				var cards = [];
				var document = {
					body: { innerText: "0 resultados NENHUM RESULTADO" },
					documentElement: { outerHTML: "<html><body>0 resultados</body></html>" },
					querySelector: function() { return input; },
					querySelectorAll: function() { return cards; }
				};
				""".trimIndent(),
			)

			assertNull(
				javascript.evaluate(script),
				"The initial zero-results placeholder must not finish search before the SPA response arrives",
			)

			javascript.evaluate(
				"""
					document.body.innerText = "2 resultados";
					document.documentElement.outerHTML = "<html><body>2 resultados</body></html>";
				""".trimIndent(),
			)
			assertNull(
				javascript.evaluate(script),
				"A positive result count must wait until its cards have hydrated",
			)

			javascript.evaluate("cards = [{}, {}];")

			assertEquals(
				"<html><body>2 resultados</body></html>",
				javascript.evaluate(script),
			)
		}
	}

	@Test
	fun returnsAnEmptySearchAfterTheEmptyStateSettles() {
		val script = NexusMangas(MangaLoaderContextMock).searchScript("No matching title")
		QuackContext.create().use { javascript ->
			javascript.evaluate(
				"""
					var window = {};
					var input = { value: "No matching title" };
					var cards = [];
					var fakeNow = 1000;
					Date.now = function() { return fakeNow; };
					var document = {
						body: { innerText: "0 resultados NENHUM RESULTADO" },
						documentElement: { outerHTML: "<html><body>0 resultados</body></html>" },
						querySelector: function() { return input; },
						querySelectorAll: function() { return cards; }
					};
				""".trimIndent(),
			)
			assertNull(javascript.evaluate(script))

			javascript.evaluate("fakeNow += 2501;")

			assertEquals(
				"<html><body>0 resultados</body></html>",
				javascript.evaluate(script),
			)
		}
	}
}
