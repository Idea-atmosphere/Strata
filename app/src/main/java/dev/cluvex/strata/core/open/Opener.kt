package dev.cluvex.strata.core.open

import androidx.annotation.StringRes
import dev.cluvex.strata.R

import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.util.FileKind
import dev.cluvex.strata.core.util.FileKinds
import dev.cluvex.strata.core.unity.UnityBundleReader
import java.io.File

enum class Opener(@StringRes val label: Int) {
    APK_VIEWER(R.string.model_opener_apk),
    SPLIT_APK_INSTALLER(R.string.model_opener_split_apk),
    ARCHIVE_VIEWER(R.string.model_opener_archive),
    TEXT_EDITOR(R.string.model_opener_text),
    MARKDOWN_VIEWER(R.string.model_opener_markdown),
    PDF_READER(R.string.model_opener_pdf),
    HEX_EDITOR(R.string.model_sheet_hex),
    IMAGE_VIEWER(R.string.model_opener_image),
    NINE_PATCH_EDITOR(R.string.model_opener_nine_patch),
    SIGNING_KEY(R.string.model_opener_signing_key),
    MUSIC_PLAYER(R.string.model_opener_music),
    VIDEO_PLAYER(R.string.model_opener_video),
    FONT_VIEWER(R.string.model_opener_font),
    DEX_EDITOR(R.string.model_opener_dex),
    ARSC_EDITOR(R.string.model_opener_arsc),
    UNITY_ASSET(R.string.model_opener_unity),
}

sealed interface OpenTarget {
    data class BuiltIn(val opener: Opener) : OpenTarget
    data class App(val packageName: String, val activity: String) : OpenTarget

    fun encode(): String = when (this) {
        is BuiltIn -> "builtin:${opener.name}"
        is App -> "app:$packageName/$activity"
    }

    companion object {
        fun decode(text: String): OpenTarget? = when {
            text.startsWith("builtin:") ->
                runCatching { BuiltIn(Opener.valueOf(text.removePrefix("builtin:"))) }.getOrNull()
            text.startsWith("app:") -> {
                val body = text.removePrefix("app:")
                val slash = body.indexOf('/')
                if (slash <= 0 || slash == body.lastIndex) null else App(body.substring(0, slash), body.substring(slash + 1))
            }
            else -> null
        }
    }
}

object OpenResolver {
    val splitBundles = setOf("apks", "xapk", "apkm")

    private val browsable = setOf("zip", "jar", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "zstd", "war", "aar")

    val markdown = setOf("md", "markdown", "mdown", "mkd", "mkdn")

    private val keyFiles = setOf("jks", "keystore", "bks", "p12", "pfx", "pk8")

    private val fonts = setOf("ttf", "otf", "ttc")

    fun builtIn(item: FileItem): Opener? {
        if (item.isDirectory) return null
        val ext = item.extension
        return when {
            ext == "apk" -> Opener.APK_VIEWER
            ext in splitBundles -> Opener.SPLIT_APK_INSTALLER
            ext == "dex" || ext == "odex" -> Opener.DEX_EDITOR
            ext == "arsc" -> Opener.ARSC_EDITOR
            ext == "obb" -> Opener.ARCHIVE_VIEWER
            UnityBundleReader.isUnityRelated(item.name) || (File(item.path).isFile && UnityBundleReader.probe(File(item.path)).kind != dev.cluvex.strata.core.unity.UnityFileKind.UNKNOWN) -> Opener.UNITY_ASSET
            ext in browsable -> Opener.ARCHIVE_VIEWER
            ext in fonts -> Opener.FONT_VIEWER
            ext in markdown -> Opener.MARKDOWN_VIEWER
            ext == "pdf" -> Opener.PDF_READER
            item.name.endsWith(".9.png", ignoreCase = true) -> Opener.NINE_PATCH_EDITOR
            ext in keyFiles || item.name.endsWith(".x509.pem", ignoreCase = true) -> Opener.SIGNING_KEY
            else -> when (FileKinds.of(item)) {
                FileKind.TEXT, FileKind.CODE -> Opener.TEXT_EDITOR
                FileKind.IMAGE -> Opener.IMAGE_VIEWER
                FileKind.AUDIO -> Opener.MUSIC_PLAYER
                FileKind.VIDEO -> Opener.VIDEO_PLAYER
                else -> null
            }
        }
    }

    fun resolve(item: FileItem, defaults: Map<String, OpenTarget>): OpenTarget? {
        if (item.isDirectory) return null
        defaults[item.extension.ifEmpty { NO_EXTENSION }]?.let { return it }
        return builtIn(item)?.let { OpenTarget.BuiltIn(it) }
    }

    private val anyFile = listOf(
        Opener.TEXT_EDITOR, Opener.MARKDOWN_VIEWER, Opener.PDF_READER, Opener.HEX_EDITOR, Opener.ARCHIVE_VIEWER, Opener.IMAGE_VIEWER, Opener.NINE_PATCH_EDITOR,
        Opener.MUSIC_PLAYER, Opener.VIDEO_PLAYER, Opener.FONT_VIEWER, Opener.APK_VIEWER,
        Opener.SPLIT_APK_INSTALLER, Opener.DEX_EDITOR, Opener.ARSC_EDITOR, Opener.SIGNING_KEY, Opener.UNITY_ASSET,
    )

    fun candidates(item: FileItem): List<Opener> = (listOfNotNull(builtIn(item)) + anyFile).distinct()

    const val NO_EXTENSION = "*"
}

enum class OpenAs(@StringRes val label: Int) {
    AUTO(R.string.model_open_as_auto),
    TEXT(R.string.model_open_as_text),
    IMAGE(R.string.model_open_as_image),
    AUDIO(R.string.model_open_as_audio),
    VIDEO(R.string.model_open_as_video),
    ANY(R.string.model_open_as_any),
    ;

    fun mimeFor(natural: String): String = when (this) {
        AUTO -> natural
        TEXT -> "text/plain"
        IMAGE -> "image/*"
        AUDIO -> "audio/*"
        VIDEO -> "video/*"
        ANY -> "*/*"
    }
}
