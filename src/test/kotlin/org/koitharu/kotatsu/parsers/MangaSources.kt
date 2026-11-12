package org.koitharu.kotatsu.parsers

import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.util.stream.Stream

/** Select network-tested parsers with -PmangaSources=SOURCE_A,SOURCE_B. */
@ArgumentsSource(MangaSourcesProvider::class)
internal annotation class MangaSources

internal class MangaSourcesProvider : ArgumentsProvider {

	override fun provideArguments(context: ExtensionContext): Stream<out Arguments> {
		val names = System.getProperty("mangaSources")
			.orEmpty()
			.split(',')
			.map(String::trim)
			.filter(String::isNotEmpty)
			.distinctBy(String::lowercase)
		return names.asSequence()
			.map { name ->
				val source = requireNotNull(
					MangaParserSource.entries.firstOrNull { it.name.equals(name, ignoreCase = true) },
				) {
						"Unknown manga source '$name'. Use a MangaParserSource enum name."
					}
				Arguments.of(source)
			}
			.toList()
			.stream()
	}
}
