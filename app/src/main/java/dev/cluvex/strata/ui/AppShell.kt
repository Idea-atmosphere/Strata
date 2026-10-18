package dev.cluvex.strata.ui

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.core.archive.ArchiveParent
import dev.cluvex.strata.core.bookmarks.Bookmark
import dev.cluvex.strata.core.bookmarks.Bookmarks
import dev.cluvex.strata.core.data.SettingsRepository
import dev.cluvex.strata.core.data.StrataSettings
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.open.OpenResolver
import dev.cluvex.strata.core.open.OpenTarget
import dev.cluvex.strata.core.open.Opener
import dev.cluvex.strata.core.permission.rememberStorageAccessState
import dev.cluvex.strata.core.remote.RemoteConnection
import dev.cluvex.strata.core.remote.RemoteUri
import dev.cluvex.strata.core.root.RootManager
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.core.util.IntentUtils
import dev.cluvex.strata.feature.apk.ApkDialog
import dev.cluvex.strata.feature.apk.ApkDialogActions
import dev.cluvex.strata.feature.apk.ApkInfoScreen
import dev.cluvex.strata.feature.apk.InstallPolicy
import dev.cluvex.strata.feature.apk.SignApkDialog
import dev.cluvex.strata.feature.apk.SplitInstallDialog
import dev.cluvex.strata.feature.apps.AppsActions
import dev.cluvex.strata.feature.apps.AppsScreen
import dev.cluvex.strata.feature.archive.ArchiveActions
import dev.cluvex.strata.feature.archive.ArchiveScreen
import dev.cluvex.strata.feature.arsc.ArscEditorScreen
import dev.cluvex.strata.feature.color.ColorPickerScreen
import dev.cluvex.strata.feature.compare.CompareScreen
import dev.cluvex.strata.feature.dex.DexScreen
import dev.cluvex.strata.feature.editor.TextEditorScreen
import dev.cluvex.strata.feature.explorer.ExplorerScreen
import dev.cluvex.strata.feature.explorer.ExplorerViewModel
import dev.cluvex.strata.feature.hex.HexEditorScreen
import dev.cluvex.strata.feature.keys.ImportKeyDialog
import dev.cluvex.strata.feature.keys.SigningKeysScreen
import dev.cluvex.strata.feature.plugin.PluginsScreen
import dev.cluvex.strata.feature.markdown.MarkdownScreen
import dev.cluvex.strata.feature.media.FontViewerScreen
import dev.cluvex.strata.feature.pdf.PdfReaderScreen
import dev.cluvex.strata.feature.pdf.ReaderSource
import dev.cluvex.strata.feature.media.ImageViewerScreen
import dev.cluvex.strata.feature.media.MusicPlayerSheet
import dev.cluvex.strata.feature.media.VideoPlayerScreen
import dev.cluvex.strata.feature.ninepatch.NinePatchScreen
import dev.cluvex.strata.feature.open.OpenWithDialog
import dev.cluvex.strata.feature.remote.RemoteScreen
import dev.cluvex.strata.feature.search.SearchScreen
import dev.cluvex.strata.feature.settings.SettingsPage
import dev.cluvex.strata.feature.settings.SettingsScreen
import dev.cluvex.strata.feature.settings.ToolbarTab
import dev.cluvex.strata.feature.share.ShareScreen
import dev.cluvex.strata.feature.termux.LanguagesScreen
import dev.cluvex.strata.feature.trash.RecycleBinScreen
import dev.cluvex.strata.feature.unity.UnityAssetScreen
import dev.cluvex.strata.ui.drawer.DrawerActions
import dev.cluvex.strata.ui.drawer.StrataDrawerContent
import java.io.File
import dev.cluvex.strata.ui.expressive.Art
import dev.cluvex.strata.ui.expressive.ExpressiveHero
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface Route {
    data object Files : Route
    data object Settings : Route
    data class Customize(val tab: ToolbarTab) : Route
    data object RecycleBin : Route
    data class Editor(val path: String) : Route
    data class Markdown(val path: String) : Route
    data class NinePatch(val path: String) : Route
    data class Hex(val path: String) : Route
    data class Search(val root: String) : Route
    data class ApkInfo(val path: String) : Route
    data class Archive(val path: String, val parents: List<ArchiveParent> = emptyList()) : Route
    data class UnityAsset(val path: String, val parents: List<ArchiveParent> = emptyList()) : Route
    data class Image(val path: String) : Route
    data class Video(val path: String) : Route
    data class Font(val path: String) : Route
    data class Pdf(val path: String?, val uri: String?, val external: Boolean = false) : Route
    data object Apps : Route
    data object Remote : Route
    data object Languages : Route
    data object ColorPicker : Route
    data object SigningKeys : Route
    data object Plugins : Route
    data object Share : Route
    data class Dex(val path: String) : Route
    data class Compare(val left: String, val right: String) : Route
    data class Arsc(val path: String, val entry: String?) : Route
}

private sealed interface OpenDialog {
    data class Apk(val path: String, val parents: List<ArchiveParent> = emptyList()) : OpenDialog
    data class SplitInstall(val path: String, val parents: List<ArchiveParent> = emptyList()) : OpenDialog
    data class OpenWith(val item: FileItem) : OpenDialog
    data class SignApk(val item: FileItem) : OpenDialog
    data class Music(val path: String) : OpenDialog
    data class ImportKey(val path: String) : OpenDialog
}

private class ShellActions(
    val push: (Route) -> Unit,
    val pop: () -> Unit,
    val replace: (Route) -> Unit,
    val openFile: (FileItem) -> Unit,
    val openNestedFile: (File, List<ArchiveParent>) -> Unit,
    val openWith: (FileItem) -> Unit,
    val openWithOpener: (FileItem, Opener) -> Unit,
    val signApk: (FileItem) -> Unit,
    val locate: (folder: String, reveal: String?) -> Unit,
    val finish: () -> Unit,
    val openVault: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppShell(
    settings: StrataSettings,
    settingsRepo: SettingsRepository,
    startPath: String? = null,
    startPathOther: String? = null,
    incoming: ReaderSource? = null,
    incomingFile: Uri? = null,
    onIncomingDone: () -> Unit = {},
) {
    val access = rememberStorageAccessState()
    if (!access.granted) {
        if (incoming?.uri != null) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                PdfReaderScreen(source = incoming, rootEnabled = false, onBack = onIncomingDone, onOpenWith = null)
            }
            return
        }
        PermissionScreen(onGrant = access.request)
        return
    }

    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: ExplorerViewModel = viewModel(factory = ExplorerViewModel.factory(app))
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val stack = remember { RouteStack(app) }
    val route = stack.route
    var visitedOtherRoute by remember { mutableStateOf(false) }
    var openDialog by remember { mutableStateOf<OpenDialog?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val saveable = rememberSaveableStateHolder()
    val motion = MaterialTheme.motionScheme
    val side = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1

    val root = remember { Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0" }
    val sidebarBookmarks by remember(vm) {
        vm.state.map { s -> Bookmarks.inGroup(s.bookmarks, Bookmarks.DEFAULT_GROUP) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(emptyList())
    val storages by remember(vm) {
        vm.state.map { it.storages }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(emptyList())

    LaunchedEffect(incoming) {
        incoming?.let { stack.push(Route.Pdf(it.path, it.uri, external = true)) }
    }

    LaunchedEffect(startPath, startPathOther) {
        startPath?.let { vm.openPath(vm.state.value.active, it) }
        startPathOther?.let { vm.openPath(vm.state.value.inactive, it) }
    }

    LaunchedEffect(incomingFile) {
        incomingFile?.let { vm.locateIncoming(it) }
    }

    LaunchedEffect(route) {
        if (route == Route.Files) {
            if (visitedOtherRoute) {
                vm.refreshAll()
                vm.checkRemoteEdits()
            }
        } else {
            visitedOtherRoute = true
        }
    }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun openWith(item: FileItem, opener: Opener) {
        when (opener) {
            Opener.APK_VIEWER -> openDialog = OpenDialog.Apk(item.path)
            Opener.SPLIT_APK_INSTALLER -> openDialog = OpenDialog.SplitInstall(item.path)
            Opener.ARCHIVE_VIEWER -> stack.push(Route.Archive(item.path))
            Opener.TEXT_EDITOR -> stack.push(Route.Editor(item.path))
            Opener.MARKDOWN_VIEWER -> stack.push(Route.Markdown(item.path))
            Opener.NINE_PATCH_EDITOR -> stack.push(Route.NinePatch(item.path))
            Opener.SIGNING_KEY -> openDialog = OpenDialog.ImportKey(item.path)
            Opener.HEX_EDITOR -> stack.push(Route.Hex(item.path))
            Opener.IMAGE_VIEWER -> stack.push(Route.Image(item.path))
            Opener.VIDEO_PLAYER -> stack.push(Route.Video(item.path))
            Opener.MUSIC_PLAYER -> openDialog = OpenDialog.Music(item.path)
            Opener.FONT_VIEWER -> stack.push(Route.Font(item.path))
            Opener.PDF_READER -> stack.push(Route.Pdf(item.path, null))
            Opener.DEX_EDITOR -> stack.push(Route.Dex(item.path))
            Opener.ARSC_EDITOR -> stack.push(Route.Arsc(item.path, null))
            Opener.UNITY_ASSET -> stack.push(Route.UnityAsset(item.path))
        }
    }

    fun openFile(item: FileItem) {
        when (val target = OpenResolver.resolve(item, settings.openDefaults)) {
            null -> openDialog = OpenDialog.OpenWith(item)
            is OpenTarget.BuiltIn -> openWith(item, target.opener)
            is OpenTarget.App ->
                if (!IntentUtils.openIn(context, item, target.packageName, target.activity)) openDialog = OpenDialog.OpenWith(item)
        }
    }

    fun openNestedFile(file: File, parents: List<ArchiveParent>) {
        val item = FileItem.of(file)
        when (val target = OpenResolver.resolve(item, settings.openDefaults)) {
            null -> openDialog = OpenDialog.OpenWith(item)
            is OpenTarget.App ->
                if (!IntentUtils.openIn(context, item, target.packageName, target.activity)) openDialog = OpenDialog.OpenWith(item)
            is OpenTarget.BuiltIn -> when (target.opener) {
                Opener.UNITY_ASSET -> stack.push(Route.UnityAsset(file.path, parents))
                Opener.ARCHIVE_VIEWER -> stack.push(Route.Archive(file.path, parents))
                Opener.APK_VIEWER -> openDialog = OpenDialog.Apk(file.path, parents)
                Opener.SPLIT_APK_INSTALLER -> openDialog = OpenDialog.SplitInstall(file.path, parents)
                else -> openWith(item, target.opener)
            }
        }
    }

    fun locate(folder: String, reveal: String?) {
        stack.clear()
        openDialog = null
        vm.showInFolder(vm.state.value.active, folder, reveal)
    }

    val actions = ShellActions(
        push = { stack.push(it) },
        pop = { stack.pop() },
        replace = { stack.replace(it) },
        openFile = { openFile(it) },
        openNestedFile = { file, parents -> openNestedFile(file, parents) },
        openWith = { openDialog = OpenDialog.OpenWith(it) },
        openWithOpener = { item, opener -> openWith(item, opener) },
        signApk = { openDialog = OpenDialog.SignApk(it) },
        locate = { folder, reveal -> locate(folder, reveal) },
        finish = onIncomingDone,
        openVault = {
            stack.clear()
            vm.openVault()
        },
    )

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(
        enabled = route == Route.Settings || route is Route.Customize || route == Route.RecycleBin || route is Route.Search || route is Route.ApkInfo ||
            route == Route.ColorPicker,
    ) {
        stack.pop()
    }

    fun go(path: String) {
        vm.openPath(vm.state.value.active, path)
        scope.launch { drawerState.close() }
        stack.clear()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = route == Route.Files,
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = RoundedCornerShape(topEnd = 40.dp, bottomEnd = 40.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                fun push(r: Route) {
                    scope.launch { drawerState.close() }
                    stack.push(r)
                }
                StrataDrawerContent(
                    root = root,
                    rootMode = settings.rootAccess,
                    bookmarks = sidebarBookmarks,
                    storages = storages,
                    open = drawerState.targetValue == DrawerValue.Open,
                    closed = drawerState.currentValue == DrawerValue.Closed && drawerState.targetValue == DrawerValue.Closed,
                    actions = DrawerActions(
                        onShortcut = ::go,
                        onBookmark = { b ->
                            vm.openBookmark(vm.state.value.active, b)
                            scope.launch { drawerState.close() }
                            stack.clear()
                        },
                        onRecycleBin = { push(Route.RecycleBin) },
                        onApps = { push(Route.Apps) },
                        onNetwork = { push(Route.Remote) },
                        onStorage = { c ->
                            vm.openPath(vm.state.value.active, RemoteUri.of(c.id, c.initialPath))
                            scope.launch { drawerState.close() }
                            stack.clear()
                        },
                        onLanguages = { push(Route.Languages) },
                        onSettings = { push(Route.Settings) },
                        onShare = { push(Route.Share) },
                        onColorPicker = { push(Route.ColorPicker) },
                        onSigningKeys = { push(Route.SigningKeys) },
                        onPlugins = { push(Route.Plugins) },
                        onVault = {
                            vm.openVault()
                            scope.launch { drawerState.close() }
                            stack.clear()
                        },
                    ),
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(if (route != Route.Files) Modifier.clearAndSetSemantics { } else Modifier) {
                ExplorerScreen(
                    vm = vm,
                    onOpenFile = { openFile(it) },
                    onOpenWithOpener = { item, opener -> openWith(item, opener) },
                    onOpenWith = { openDialog = OpenDialog.OpenWith(it) },
                    onCompare = { a, b -> stack.push(Route.Compare(a.path, b.path)) },
                    onOpenSearch = { stack.push(Route.Search(it)) },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onCustomize = { tab -> stack.push(Route.Customize(tab)) },
                    drawerOpen = drawerState.isOpen || route != Route.Files,
                    appSnackbar = snackbar.takeIf { route != Route.Files },
                )
            }
            AnimatedContent(
                targetState = stack.top,
                contentKey = { it?.key },
                transitionSpec = {
                    val entering = (targetState?.key ?: -1L) > (initialState?.key ?: -1L)
                    if (entering) {
                        (slideInHorizontally(motion.defaultSpatialSpec()) { w -> side * w / 3 } + fadeIn(motion.defaultEffectsSpec()))
                            .togetherWith(fadeOut(motion.fastEffectsSpec()))
                    } else {
                        fadeIn(motion.fastEffectsSpec())
                            .togetherWith(slideOutHorizontally(motion.defaultSpatialSpec()) { w -> side * w / 3 } + fadeOut(motion.defaultEffectsSpec()))
                    }
                },
            ) { entry ->
                if (entry != null) {
                    DisposableEffect(entry) {
                        onDispose {
                            if (!stack.contains(entry)) {
                                entry.viewModelStore.clear()
                                saveable.removeState(entry.key)
                            }
                        }
                    }
                    saveable.SaveableStateProvider(entry.key) {
                        CompositionLocalProvider(LocalViewModelStoreOwner provides entry) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                Screen(entry.route, settings, settingsRepo, vm, actions, stack.top?.key ?: 0L)
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }

    val policy = InstallPolicy(
        rootMode = settings.rootAccess,
        installWithRoot = settings.installWithRoot,
        verifyFirst = settings.verifyBeforeInstall,
    )
    when (val d = openDialog) {
        null -> Unit
        is OpenDialog.Apk -> ApkDialog(
            path = d.path,
            policy = policy,
            actions = ApkDialogActions(
                onView = { stack.push(Route.Archive(d.path, d.parents)) },
                onSign = { openDialog = OpenDialog.SignApk(FileItem.of(File(d.path))) },
                onDetails = { openDialog = null; stack.push(Route.ApkInfo(d.path)) },
                onDexTools = { openDialog = null; stack.push(Route.Dex(d.path)) },
                onGoTo = { folder, reveal -> locate(folder, reveal) },
                onMessage = ::message,
            ),
            onDismiss = { openDialog = null },
        )
        is OpenDialog.SplitInstall -> SplitInstallDialog(
            path = d.path,
            policy = policy,
            onView = { stack.push(Route.Archive(d.path, d.parents)) },
            onDismiss = { openDialog = null },
        )
        is OpenDialog.OpenWith -> OpenWithDialog(
            item = d.item,
            onBuiltIn = { opener -> openDialog = null; openWith(d.item, opener) },
            onApp = { target, mime ->
                openDialog = null
                if (!IntentUtils.openIn(context, d.item, target.packageName, target.activity, mime)) message(tr(R.string.ui_open_in_app_failed))
            },
            onSetDefault = { target ->
                val ext = d.item.extension
                scope.launch { settingsRepo.setOpenDefault(ext, target) }
                message(if (ext.isEmpty()) tr(R.string.ui_default_no_extension) else tr(R.string.ui_default_for_ext, ext))
                openDialog = null
                when (target) {
                    is OpenTarget.BuiltIn -> openWith(d.item, target.opener)
                    is OpenTarget.App -> IntentUtils.openIn(context, d.item, target.packageName, target.activity)
                }
            },
            onDismiss = { openDialog = null },
        )
        is OpenDialog.Music -> MusicPlayerSheet(
            path = d.path,
            showHidden = settings.showHidden,
            onDismiss = { openDialog = null },
        )
        is OpenDialog.SignApk -> SignApkDialog(
            item = d.item,
            onConfirm = { request -> vm.signApk(d.item, request); openDialog = null },
            onDismiss = { openDialog = null },
        )
        is OpenDialog.ImportKey -> ImportKeyDialog(d.path) { result ->
            openDialog = null
            result?.let(::message)
        }
    }
}

@Composable
private fun Screen(
    r: Route,
    settings: StrataSettings,
    settingsRepo: SettingsRepository,
    vm: ExplorerViewModel,
    actions: ShellActions,
    refreshToken: Long,
) {
    when (r) {
        Route.Files -> Unit
        Route.Settings -> {
            val s = rememberCoroutineScope()
            val rootState by RootManager.state.collectAsStateWithLifecycle()
            val explorer by vm.state.collectAsStateWithLifecycle()
            SettingsScreen(
                settings = settings,
                repo = settingsRepo,
                rootState = rootState,
                onRootAccess = { v -> s.launch { settingsRepo.setRootAccess(v && RootManager.request()) } },
                onOpenRecycleBin = { actions.push(Route.RecycleBin) },
                activeFolder = explorer.activePane.path,
                onBack = actions.pop,
                onOpenVault = actions.openVault,
            )
        }
        is Route.Customize -> {
            val s = rememberCoroutineScope()
            val rootState by RootManager.state.collectAsStateWithLifecycle()
            val explorer by vm.state.collectAsStateWithLifecycle()
            SettingsScreen(
                settings = settings,
                repo = settingsRepo,
                rootState = rootState,
                onRootAccess = { v -> s.launch { settingsRepo.setRootAccess(v && RootManager.request()) } },
                onOpenRecycleBin = { actions.push(Route.RecycleBin) },
                activeFolder = explorer.activePane.path,
                onBack = actions.pop,
                startPage = SettingsPage.TOOLBAR,
                startTab = r.tab,
            )
        }
        Route.RecycleBin -> RecycleBinScreen(
            onBack = actions.pop,
            onChanged = { vm.refreshAll() },
        )
        is Route.Editor -> {
            val s = rememberCoroutineScope()
            TextEditorScreen(
                path = r.path,
                onBack = actions.pop,
                rootEnabled = settings.rootAccess,
                prefs = settings.editor,
                onPrefs = { p -> s.launch { settingsRepo.setEditorPrefs(p) } },
                onOpenHex = { actions.replace(Route.Hex(r.path)) },
                onPreview = if (File(r.path).extension.lowercase() in OpenResolver.markdown) ({ actions.push(Route.Markdown(r.path)) }) else null,
            )
        }
        is Route.NinePatch -> NinePatchScreen(path = r.path, rootEnabled = settings.rootAccess, onBack = actions.pop)
        is Route.Markdown -> MarkdownScreen(
            path = r.path,
            rootEnabled = settings.rootAccess,
            onBack = actions.pop,
            onEdit = { actions.push(Route.Editor(r.path)) },
            onOpenLocal = { p ->
                if (File(p).extension.lowercase() in OpenResolver.markdown) actions.push(Route.Markdown(p)) else actions.openFile(FileItem.of(File(p)))
            },
        )
        is Route.Hex -> HexEditorScreen(path = r.path, onBack = actions.pop, rootEnabled = settings.rootAccess)
        is Route.ApkInfo -> ApkInfoScreen(
            path = r.path,
            onBack = actions.pop,
            onSign = { actions.signApk(FileItem.of(File(r.path))) },
        )
        is Route.Archive -> ArchiveScreen(
            path = r.path,
            otherPaneDir = vm.state.value.let { it.pane(it.inactive).path },
            actions = ArchiveActions(
                onBack = actions.pop,
                onOpenFile = { file -> actions.openFile(FileItem.of(file)) },
                onSignApk = { p -> actions.signApk(FileItem.of(File(p))) },
                onLocate = actions.locate,
                onOpenArsc = { archive, entry -> actions.push(Route.Arsc(archive, entry)) },
                onOpenEntry = { archive, entry, materialized ->
                    actions.openNestedFile(materialized, r.parents + ArchiveParent(archive, entry.path))
                },
            ),
        )
        is Route.UnityAsset -> UnityAssetScreen(
            path = r.path,
            parents = r.parents,
            refreshToken = refreshToken,
            onBack = actions.pop,
            onOpenFile = actions.openFile,
            onSignApk = { file -> actions.signApk(FileItem.of(file)) },
            onLocate = actions.locate,
        )
        is Route.Image -> ImageViewerScreen(
            path = r.path,
            showHidden = settings.showHidden,
            onBack = actions.pop,
            onOpenWith = actions.openWith,
        )
        is Route.Video -> VideoPlayerScreen(path = r.path, showHidden = settings.showHidden, onBack = actions.pop)
        is Route.Font -> FontViewerScreen(path = r.path, onBack = actions.pop)
        is Route.Pdf -> PdfReaderScreen(
            source = ReaderSource(r.path, r.uri),
            rootEnabled = settings.rootAccess,
            onBack = if (r.external) actions.finish else actions.pop,
            onOpenWith = r.path?.let { p -> { actions.openWith(FileItem.of(File(p))) } },
        )
        is Route.Dex -> DexScreen(
            path = r.path,
            onBack = actions.pop,
            onLocate = actions.locate,
            onSignApk = { p -> actions.signApk(FileItem.of(File(p))) },
        )
        is Route.Arsc -> ArscEditorScreen(
            path = r.path,
            entry = r.entry,
            rootEnabled = settings.rootAccess,
            onBack = actions.pop,
            onSignApk = { p -> actions.signApk(FileItem.of(File(p))) },
        )
        is Route.Compare -> CompareScreen(
            leftPath = r.left,
            rightPath = r.right,
            rootEnabled = settings.rootAccess,
            onBack = actions.pop,
        )
        Route.Languages -> LanguagesScreen(onBack = actions.pop)
        Route.ColorPicker -> ColorPickerScreen(onBack = actions.pop)
        Route.Share -> ShareScreen(
            currentFolder = vm.state.value.let { it.pane(it.active).path }.takeUnless { RemoteUri.isRemote(it) }
                ?: android.os.Environment.getExternalStorageDirectory().path,
            onBack = actions.pop,
        )
        Route.SigningKeys -> SigningKeysScreen(
            exportDir = vm.state.value.let { it.pane(it.active).path }.takeUnless { RemoteUri.isRemote(it) }
                ?: android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path,
            onBack = actions.pop,
            onLocate = actions.locate,
        )
        Route.Plugins -> PluginsScreen(onBack = actions.pop)
        Route.Remote -> RemoteScreen(
            onBack = actions.pop,
            onOpenLocalFile = { actions.openFile(FileItem.of(File(it))) },
            onLocate = actions.locate,
            localDir = vm.state.value.let { it.pane(it.active).path },
        )
        Route.Apps -> {
            val s = rememberCoroutineScope()
            AppsScreen(
                extractDir = settings.apkExtractDir,
                namePattern = settings.apkNamePattern,
                actions = AppsActions(
                    onBack = actions.pop,
                    onOpenApk = { p -> actions.openWithOpener(FileItem.of(File(p)), Opener.APK_VIEWER) },
                    onLocate = actions.locate,
                    onExtractSettings = { d, p -> s.launch { settingsRepo.setApkExtraction(d, p) } },
                ),
            )
        }
        is Route.Search -> SearchScreen(
            root = r.root,
            onBack = actions.pop,
            onOpenItem = { item ->
                if (item.isDirectory) actions.locate(item.path, null) else actions.openFile(item)
            },
        )
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ExpressiveHero(art = Art.WELCOME, size = 200.dp)
            Text(
                stringResource(R.string.ui_storage_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.ui_storage_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onGrant) { Text(stringResource(R.string.ui_storage_grant)) }
        }
    }
}
