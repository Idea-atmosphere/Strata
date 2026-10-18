package dev.cluvex.strata.feature.open

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DataArray
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.cluvex.strata.R
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.open.OpenAs
import dev.cluvex.strata.core.open.OpenResolver
import dev.cluvex.strata.core.open.OpenTarget
import dev.cluvex.strata.core.open.Opener
import dev.cluvex.strata.core.util.FileKinds
import dev.cluvex.strata.core.util.IntentUtils
import dev.cluvex.strata.ui.LocalSettings
import dev.cluvex.strata.ui.isolated
import dev.cluvex.strata.ui.rememberSheetState
import dev.cluvex.strata.ui.theme.toShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val Opener.icon: ImageVector
    get() = when (this) {
        Opener.APK_VIEWER -> Icons.Rounded.Android
        Opener.SPLIT_APK_INSTALLER -> Icons.Rounded.InstallMobile
        Opener.ARCHIVE_VIEWER -> Icons.Rounded.FolderZip
        Opener.TEXT_EDITOR -> Icons.Rounded.EditNote
        Opener.MARKDOWN_VIEWER -> Icons.AutoMirrored.Rounded.Article
        Opener.HEX_EDITOR -> Icons.Rounded.DataArray
        Opener.IMAGE_VIEWER -> Icons.Rounded.Image
        Opener.NINE_PATCH_EDITOR -> Icons.Rounded.GridOn
        Opener.SIGNING_KEY -> Icons.Rounded.Key
        Opener.MUSIC_PLAYER -> Icons.Rounded.MusicNote
        Opener.VIDEO_PLAYER -> Icons.Rounded.Movie
        Opener.FONT_VIEWER -> Icons.Rounded.FontDownload
        Opener.PDF_READER -> Icons.Rounded.PictureAsPdf
        Opener.DEX_EDITOR -> Icons.Rounded.Code
        Opener.ARSC_EDITOR -> Icons.Rounded.DataObject
        Opener.UNITY_ASSET -> Icons.Rounded.FolderZip
    }

private data class AppChoice(val target: OpenTarget.App, val label: String, val icon: ImageBitmap?)

private val TileGap = 8.dp
private val TileMinWidth = 100.dp
private val TileIconSize = 44.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenWithDialog(
    item: FileItem,
    onBuiltIn: (Opener) -> Unit,
    onApp: (OpenTarget.App, mime: String) -> Unit,
    onSetDefault: (OpenTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var openAs by remember(item.path) { mutableStateOf(OpenAs.AUTO) }
    val mime = remember(item.path, openAs) { openAs.mimeFor(IntentUtils.mimeType(item)) }
    val apps by produceState<List<AppChoice>?>(null, item.path, mime) {
        value = null
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val uri = IntentUtils.contentUri(context, item) ?: android.net.Uri.parse("content://${context.packageName}.fileprovider/query")
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .filter { it.activityInfo.packageName != context.packageName }
                .distinctBy { it.activityInfo.packageName to it.activityInfo.name }
                .map { ri ->
                    AppChoice(
                        OpenTarget.App(ri.activityInfo.packageName, ri.activityInfo.name),
                        ri.loadLabel(pm).toString(),
                        runCatching { ri.loadIcon(pm).toBitmap(128, 128).asImageBitmap() }.getOrNull(),
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
    }
    val ext = item.extension
    val iconShape = LocalSettings.current.appearance.iconShape.toShape()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberSheetState(),
        modifier = Modifier.testTag("open-with"),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(FileKinds.of(item).icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(stringResource(R.string.open_with), style = MaterialTheme.typography.titleMedium)
                    Text(
                        isolated(item.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            SectionHeader("Strata", Modifier.padding(bottom = 10.dp))
            TileGrid(OpenResolver.candidates(item)) { opener, modifier ->
                OpenTile(
                    label = stringResource(opener.label),
                    onClick = { onBuiltIn(opener) },
                    onLongClick = { onSetDefault(OpenTarget.BuiltIn(opener)) },
                    modifier = modifier.testTag("open-builtin-${opener.name}"),
                ) {
                    Box(
                        Modifier.size(TileIconSize).clip(iconShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(opener.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            SectionHeader(stringResource(R.string.open_other_apps), Modifier.padding(bottom = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
                items(OpenAs.entries) { kind ->
                    FilterChip(selected = openAs == kind, onClick = { openAs = kind }, label = { Text(stringResource(kind.label), maxLines = 1) })
                }
            }
            Box(Modifier.padding(top = 10.dp)) {
                val list = apps
                when {
                    list == null -> Hint(stringResource(R.string.open_looking))
                    list.isEmpty() -> Hint(stringResource(R.string.open_no_app))
                    else -> TileGrid(list) { app, modifier ->
                        OpenTile(
                            label = isolated(app.label),
                            onClick = { onApp(app.target, mime) },
                            onLongClick = { onSetDefault(app.target) },
                            modifier = modifier,
                        ) {
                            val bmp = app.icon
                            if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(TileIconSize))
                            else Icon(Icons.Rounded.Android, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 18.dp)) {
                Icon(Icons.Rounded.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text(
                    if (ext.isEmpty()) stringResource(R.string.open_default_hint) else stringResource(R.string.open_default_hint_ext, ext),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun <T> TileGrid(items: List<T>, tile: @Composable (T, Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + TileGap) / (TileMinWidth + TileGap)).toInt().coerceIn(3, 6)
        Column(verticalArrangement = Arrangement.spacedBy(TileGap)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                    row.forEach { tile(it, Modifier.weight(1f).fillMaxHeight()) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OpenTile(
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.clip(shape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(TileIconSize), contentAlignment = Alignment.Center) { icon() }
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
