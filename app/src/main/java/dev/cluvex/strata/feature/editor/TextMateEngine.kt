package dev.cluvex.strata.feature.editor

import android.content.Context
import android.content.res.AssetManager
import dev.cluvex.strata.core.text.EditorLanguage
import dev.cluvex.strata.core.text.EditorTheme
import dev.cluvex.strata.core.text.GrammarCatalog
import dev.cluvex.strata.core.text.GrammarEntry
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.GrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource

internal sealed class OptionalFeatureState {
    data object Uninitialized : OptionalFeatureState()
    data object Ready : OptionalFeatureState()
    data class Unavailable(val error: Throwable) : OptionalFeatureState()
}

/** Runs an optional subsystem initializer at most once, including when initialization fails. */
internal class OptionalInitializationGate {
    private val lock = Any()

    @Volatile
    var state: OptionalFeatureState = OptionalFeatureState.Uninitialized
        private set

    val isReady: Boolean
        get() = state === OptionalFeatureState.Ready

    fun initialize(block: () -> Unit): OptionalFeatureState {
        val observed = state
        if (observed !== OptionalFeatureState.Uninitialized) return observed
        synchronized(lock) {
            val current = state
            if (current !== OptionalFeatureState.Uninitialized) return current
            state = try {
                block()
                OptionalFeatureState.Ready
            } catch (error: Throwable) {
                rethrowFatalOptionalFeatureError(error)
                OptionalFeatureState.Unavailable(error)
            }
            return state
        }
    }

    fun disable(error: Throwable) {
        rethrowFatalOptionalFeatureError(error)
        synchronized(lock) {
            if (state !is OptionalFeatureState.Unavailable) {
                state = OptionalFeatureState.Unavailable(error)
            }
        }
    }
}

private fun rethrowFatalOptionalFeatureError(error: Throwable) {
    // Out-of-memory during grammar loading can be recovered by dropping the optional engine.
    if (error is VirtualMachineError && error !is OutOfMemoryError) throw error
    if (error is ThreadDeath) throw error
}

/**
 * Optional TextMate enhancement. File decoding and editing must remain usable when this engine or
 * one of its service providers cannot initialize on a device.
 */
object TextMateEngine {

    private val operationLock = Any()
    private val initialization = OptionalInitializationGate()

    private lateinit var assets: AssetManager
    private var loadedCatalog: GrammarCatalog? = null
    private var loadedThemes: List<EditorTheme> = emptyList()
    private val loadedScopes = HashSet<String>()

    val isReady: Boolean
        get() = initialization.isReady

    val unavailableCause: Throwable?
        get() = (initialization.state as? OptionalFeatureState.Unavailable)?.error

    val languages: List<EditorLanguage>
        get() = if (isReady) loadedCatalog?.languages.orEmpty() else emptyList()

    val themes: List<EditorTheme>
        get() = if (isReady) loadedThemes else emptyList()

    internal fun init(context: Context): OptionalFeatureState = initialization.initialize {
        assets = context.applicationContext.assets
        GrammarRegistry.getInstance()
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))

        val catalog = GrammarCatalog.parse(read("textmate/languages.json"), read("textmate/filetypes.json"))
        val themes = GrammarCatalog.parseThemes(read("textmate/themes.json"))
        require(themes.isNotEmpty()) { "TextMate theme catalog is empty" }

        val registry = ThemeRegistry.getInstance()
        for (theme in themes) {
            val stream = FileProviderRegistry.getInstance().tryGetInputStream(theme.path)
            registry.loadTheme(
                ThemeModel(IThemeSource.fromInputStream(stream, theme.path, null), theme.id).apply {
                    isDark = theme.dark
                },
                false,
            )
        }
        registry.setTheme(themes.first().id)
        loadedCatalog = catalog
        loadedThemes = themes
    }

    fun languageFor(fileName: String): EditorLanguage? =
        if (isReady) loadedCatalog?.languageFor(fileName) else null

    fun languageNamed(name: String): EditorLanguage? =
        if (isReady) loadedCatalog?.language(name) else null

    fun theme(id: String): EditorTheme? = themes.firstOrNull { it.id == id }

    fun activateTheme(id: String): ThemeModel? = guarded {
        val registry = ThemeRegistry.getInstance()
        if (registry.currentThemeModel?.name != id) registry.setTheme(id)
        registry.currentThemeModel
    }

    fun colorScheme(model: ThemeModel, chrome: EditorChrome?): StrataColorScheme? = guarded {
        StrataColorScheme(ThemeRegistry.getInstance(), model, chrome)
    }

    fun language(scope: String, autoComplete: Boolean = true): TextMateLanguage? = guarded {
        ThemeRegistry.getInstance().currentThemeModel?.let { GrammarRegistry.getInstance().setTheme(it) }
        val catalog = checkNotNull(loadedCatalog) { "TextMate catalog is unavailable" }
        val needed = catalog.closure(scope) { includesOf(it) }.filter { it.scopeName !in loadedScopes }
        if (needed.isNotEmpty()) {
            GrammarRegistry.getInstance().loadGrammars(needed.map(::definition))
            loadedScopes += needed.map { it.scopeName }
        }
        TextMateLanguage.create(scope, autoComplete)
    }

    private fun <T : Any> guarded(block: () -> T?): T? = synchronized(operationLock) {
        if (!isReady) return@synchronized null
        try {
            block()
        } catch (error: Throwable) {
            initialization.disable(error)
            loadedScopes.clear()
            null
        }
    }

    private fun definition(entry: GrammarEntry): GrammarDefinition {
        val source = IGrammarSource.fromInputStream(
            FileProviderRegistry.getInstance().tryGetInputStream(entry.grammar),
            entry.grammar,
            null,
        )
        val definition = if (entry.languageConfiguration != null) {
            DefaultGrammarDefinition.withLanguageConfiguration(
                source,
                entry.languageConfiguration,
                entry.name,
                entry.scopeName,
            )
        } else {
            DefaultGrammarDefinition.withGrammarSource(source, entry.name, entry.scopeName)
        }
        return if (entry.embeddedLanguages.isEmpty()) {
            definition
        } else {
            definition.withEmbeddedLanguages(entry.embeddedLanguages)
        }
    }

    private fun includesOf(entry: GrammarEntry): Set<String> =
        GrammarCatalog.includedScopes(read(entry.grammar))

    private fun read(path: String): String = assets.open(path).bufferedReader().use { it.readText() }
}
