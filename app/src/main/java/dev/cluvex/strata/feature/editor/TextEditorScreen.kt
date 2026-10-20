package dev.cluvex.strata.feature.editor

import dev.cluvex.strata.core.i18n.separator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.color.ColorCodec
import dev.cluvex.strata.core.data.EditorPrefs
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.sound.UiSound
import dev.cluvex.strata.core.termux.TermuxLanguages
import dev.cluvex.strata.core.text.EditorLanguage
import dev.cluvex.strata.core.text.LineEnding
import dev.cluvex.strata.core.text.TextEncoding
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.feature.color.ColorPickerDialog
import dev.cluvex.strata.feature.termux.RunSheet
import dev.cluvex.strata.feature.termux.TermuxViewModel
import dev.cluvex.strata.ui.expressive.ArtTone
import dev.cluvex.strata.ui.expressive.ExpressiveDialog
import dev.cluvex.strata.ui.expressive.HeroShapes
import io.github.rosemoe.sora.lang.EmptyLanguage
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import kotlinx.coroutines.launch
import dev.cluvex.strata.core.plugin.Plugins
import dev.cluvex.strata.core.plugin.TextApp

private sealed interface EditorDialog {
    data object None : EditorDialog
    data object GoToLine : EditorDialog
    data object GoToPage : EditorDialog
    data object Syntax : EditorDialog
    data object Theme : EditorDialog
    data object Encoding : EditorDialog
    data object LineEndings : EditorDialog
    data object TextSize : EditorDialog
    data object Leave : EditorDialog
    data class PickColor(val at: CursorColor?) : EditorDialog
    data class ProcessText(val selection: EditorSelection) : EditorDialog
}

private val SYMBOLS = listOf(
    "\t", "{", "}", "(", ")", ";", ",", ".", "=", "\"", "'", "|", "&", "!", "[", "]", "<", ">",
    "+", "-", "/", "*", "?", ":", "_", "#", "$", "@", "%", "^", "~", "\\", "`",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TextEditorScreen(
    path: String,
    onBack: () -> Unit,
    rootEnabled: Boolean = false,
    prefs: EditorPrefs = EditorPrefs(),
    onPrefs: (EditorPrefs) -> Unit = {},
    onOpenHex: () -> Unit = {},
    onPreview: (() -> Unit)? = null,
    textApps: (() -> List<TextApp>)? = null,
    vm: TextEditorViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val controller = remember { EditorController() }
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<EditorDialog>(EditorDialog.None) }
    var menu by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    val runnable = remember(state.name) { TermuxLanguages.forFile(state.name) != null }
    var showRun by remember { mutableStateOf(false) }
    val termux: TermuxViewModel = viewModel()
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(path) { vm.load(path, rootEnabled) }
    LifecycleResumeEffect(path) {
        vm.checkExternalChange()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    val scope = rememberCoroutineScope()
    val processText = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val sel = vm.pendingSelection ?: return@rememberLauncherForActivityResult
        vm.pendingSelection = null
        val out = runCatching { r.data?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() }.getOrNull()
        if (r.resultCode != Activity.RESULT_OK || out == null || out == sel.text || state.readOnly) return@rememberLauncherForActivityResult
        if (!controller.replaceSelection(sel, out)) scope.launch { snackbar.showSnackbar(tr(R.string.editor_process_text_changed)) }
    }
    controller.onEdited = { vm.onTextChanged(controller.length(), controller::text) }
    fun save(then: () -> Unit = {}) = vm.save(controller.text()) {
        if (controller.editor != null && !vm.state.value.paged) vm.onTextChanged(controller.length(), controller::text)
        then()
    }

    LaunchedEffect(state.loadSeq, controller.editor) {
        if (controller.editor == null) return@LaunchedEffect
        val text = vm.takeText() ?: return@LaunchedEffect
        onMain {
            controller.setText(text)
            controller.setLineEnding(state.lineEnding)
            state.jumpLine?.let { controller.gotoLine(it); vm.consumeJump() }
        }
    }
    DisposableEffect(Unit) {
        onDispose { vm.stash(controller.text()) }
    }
    val dark = isDarkBackground(colors.surface)
    val themeId = if (prefs.theme == "auto") autoThemeId(dark) else prefs.theme
    LaunchedEffect(themeId, prefs.theme == "auto", colors, state.syntaxRevision, controller.editor) {
        if (controller.editor == null) return@LaunchedEffect
        val availability = withContext(Dispatchers.Default) { TextMateEngine.init(context) }
        if (availability !is OptionalFeatureState.Ready) {
            vm.reportSyntaxUnavailable()
            return@LaunchedEffect
        }
        val chrome = if (prefs.theme == "auto") EditorChrome.from(colors) else null
        val model = withContext(Dispatchers.Default) { TextMateEngine.activateTheme(themeId) }
        if (model == null) {
            if (!TextMateEngine.isReady) vm.reportSyntaxUnavailable()
            return@LaunchedEffect
        }
        val scheme = withContext(Dispatchers.Default) { TextMateEngine.colorScheme(model, chrome) }
        if (scheme == null) {
            vm.reportSyntaxUnavailable()
            return@LaunchedEffect
        }
        onMain { controller.setColorScheme(scheme) }
    }
    LaunchedEffect(state.language?.scopeName, state.syntaxRevision, controller.editor) {
        if (controller.editor == null) return@LaunchedEffect
        val scope = state.language?.scopeName
        val language = if (scope == null || !TextMateEngine.isReady) {
            EmptyLanguage()
        } else {
            withContext(Dispatchers.Default) { TextMateEngine.language(scope) } ?: EmptyLanguage()
        }
        if (scope != null && !TextMateEngine.isReady) vm.reportSyntaxUnavailable()
        onMain { controller.setLanguage(language) }
    }
    LaunchedEffect(prefs.wordWrap, controller.editor) { onMain { controller.setWordWrap(prefs.wordWrap) } }
    LaunchedEffect(prefs.lineNumbers, controller.editor) { onMain { controller.setLineNumbers(prefs.lineNumbers) } }
    LaunchedEffect(prefs.showWhitespace, controller.editor) { onMain { controller.setShowWhitespace(prefs.showWhitespace) } }
    LaunchedEffect(prefs.textSize, controller.editor) { onMain { controller.setTextSize(prefs.textSize) } }
    LaunchedEffect(prefs, controller.editor) { onMain { controller.applyOptions(prefs) } }
    LaunchedEffect(state.readOnly, controller.editor) { onMain { controller.setEditable(!state.readOnly) } }

    fun leave() {
        when {
            finding -> { finding = false; controller.stopSearch() }
            state.dirty && !state.readOnly -> dialog = EditorDialog.Leave
            else -> onBack()
        }
    }
    BackHandler { leave() }

    Scaffold(
        modifier = Modifier.testTag("text-editor"),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
                title = {
                    Column {
                        Text(
                            state.name + if (state.dirty) " ●" else "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            statusLine(state, controller),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = controller::undo, enabled = controller.canUndo && !state.readOnly) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.editor_undo))
                    }
                    IconButton(onClick = controller::redo, enabled = controller.canRedo && !state.readOnly) {
                        Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = stringResource(R.string.editor_redo))
                    }
                    if (!state.readOnly) {
                        IconButton(onClick = { save() }, enabled = state.dirty && !state.saving) {
                            Icon(Icons.Rounded.Save, contentDescription = stringResource(R.string.common_save))
                        }
                    }
                    if (runnable && !state.loading && !state.tooLarge) {
                        IconButton(onClick = { showRun = true }, modifier = Modifier.testTag("editor_run")) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.editor_run))
                        }
                    }
                    IconButton(onClick = { finding = !finding; if (!finding) controller.stopSearch() }, enabled = !state.loading && !state.tooLarge) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.editor_find))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.common_more)) }
                        EditorMenu(
                            expanded = menu,
                            state = state,
                            prefs = prefs,
                            onDismiss = { menu = false },
                            onPrefs = onPrefs,
                            onDialog = { menu = false; dialog = it },
                            onReadOnly = { menu = false; vm.setReadOnly(it) },
                            onReload = { menu = false; vm.load(path, rootEnabled, force = true) },
                            onOpenHex = { menu = false; onOpenHex() },
                            onColor = { menu = false; dialog = EditorDialog.PickColor(controller.colorUnderCursor()) },
                            onProcessText = {
                                menu = false
                                val sel = controller.selection()
                                when {
                                    sel == null -> scope.launch { snackbar.showSnackbar(tr(R.string.editor_select_text_first)) }
                                    sel.text.length > MAX_PROCESS_TEXT -> scope.launch { snackbar.showSnackbar(tr(R.string.editor_process_text_too_much)) }
                                    else -> dialog = EditorDialog.ProcessText(sel)
                                }
                            },
                            onPreview = onPreview?.let { preview ->
                                {
                                    menu = false
                                    if (state.dirty && !state.readOnly) save { preview() } else preview()
                                }
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            AnimatedVisibility(finding, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                FindBar(controller, readOnly = state.readOnly, onClose = { finding = false; controller.stopSearch() })
            }
            state.decodedResource?.let { Banner(it) }
            if (state.binary && !state.loading && state.decodedResource == null) {
                Banner(stringResource(R.string.editor_binary_banner))
            }
            if (state.paged && state.pageLossy) {
                Banner(stringResource(R.string.editor_page_lossy, state.page + 1, state.charset.displayName()))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.tooLarge -> TooLarge(state.size, onOpenHex)
                    else -> CodeEditorView(controller, Modifier.fillMaxSize())
                }
                if (state.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
            }
            if (state.paged) {
                PageBar(
                    page = state.page,
                    count = state.pageCount,
                    onPrev = { vm.goToPage(state.page - 1, controller.text()) },
                    onNext = { vm.goToPage(state.page + 1, controller.text()) },
                    onPick = { dialog = EditorDialog.GoToPage },
                )
            }
            if (prefs.symbolBar && !state.readOnly && !state.tooLarge) {
                SymbolBar(onSymbol = controller::insertSymbol)
            }
        }
    }

    if (showRun) {
        RunSheet(
            path = path,
            onRun = { _, run -> if (state.dirty && !state.readOnly) save { run() } else run() },
            onDismiss = { showRun = false },
            session = termux.session,
        )
    }

    when (val d = dialog) {
        EditorDialog.None -> Unit
        is EditorDialog.ProcessText -> ProcessTextDialog(
            apps = textApps,
            onPick = { app ->
                dialog = EditorDialog.None
                vm.pendingSelection = d.selection
                val intent = Intent(Intent.ACTION_PROCESS_TEXT)
                    .setClassName(app.packageName, app.activityName)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, d.selection.text)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, state.readOnly)
                try {
                    processText.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    vm.pendingSelection = null
                    scope.launch { snackbar.showSnackbar(tr(R.string.editor_app_cant_open, app.label)) }
                }
            },
            onDismiss = { dialog = EditorDialog.None },
        )
        is EditorDialog.PickColor -> ColorPickerDialog(
            initial = d.at?.literal?.argb ?: 0xFF6750A4.toInt(),
            title = stringResource(if (d.at != null) R.string.editor_edit_color else R.string.editor_insert_color),
            onPick = { c ->
                if (d.at != null) controller.replaceColor(d.at, c) else controller.insertText(ColorCodec.androidHex(c))
                dialog = EditorDialog.None
            },
            onDismiss = { dialog = EditorDialog.None },
        )
        EditorDialog.GoToLine -> GoToLineDialog(
            lineCount = if (state.paged) state.totalLines.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else controller.lineCount,
            onGo = { n ->
                dialog = EditorDialog.None
                if (state.paged) vm.goToLine(n, controller.text())?.let(controller::gotoLine) else controller.gotoLine(n)
            },
            onDismiss = { dialog = EditorDialog.None },
        )
        EditorDialog.GoToPage -> GoToLineDialog(
            lineCount = state.pageCount,
            title = stringResource(R.string.editor_go_to_page),
            fieldLabel = stringResource(R.string.editor_page_range, 1, state.pageCount),
            onGo = { n -> dialog = EditorDialog.None; vm.goToPage(n - 1, controller.text()) },
            onDismiss = { dialog = EditorDialog.None },
        )
        EditorDialog.Syntax -> {
            val plainText = stringResource(R.string.editor_plain_text)
            ChoiceDialog(
                title = stringResource(R.string.editor_syntax),
                options = listOf<EditorLanguage?>(null) + TextMateEngine.languages,
                selected = state.language,
                label = { it?.label ?: plainText },
                onSelect = { vm.setLanguage(it); dialog = EditorDialog.None },
                onDismiss = { dialog = EditorDialog.None },
            )
        }
        EditorDialog.Theme -> {
            val auto = stringResource(R.string.editor_theme_auto)
            ChoiceDialog(
                title = stringResource(R.string.editor_theme),
                options = listOf("auto") + TextMateEngine.themes.map { it.id },
                selected = prefs.theme,
                label = { id -> if (id == "auto") auto else TextMateEngine.theme(id)?.name ?: id },
                onSelect = { onPrefs(prefs.copy(theme = it)); dialog = EditorDialog.None },
                onDismiss = { dialog = EditorDialog.None },
            )
        }
        EditorDialog.Encoding -> EncodingDialog(
            current = state.charset,
            dirty = state.dirty,
            onReopen = { cs -> vm.load(path, rootEnabled, charset = cs); dialog = EditorDialog.None },
            onSaveAs = { cs -> vm.setSaveCharset(cs); dialog = EditorDialog.None },
            onDismiss = { dialog = EditorDialog.None },
        )
        EditorDialog.LineEndings -> {
            val lf = stringResource(R.string.editor_line_ending_lf, LineEnding.LF.label)
            val crlf = stringResource(R.string.editor_line_ending_crlf, LineEnding.CRLF.label)
            val cr = stringResource(R.string.editor_line_ending_cr, LineEnding.CR.label)
            ChoiceDialog(
                title = stringResource(R.string.editor_line_endings),
                options = LineEnding.entries,
                selected = state.lineEnding,
                label = {
                    when (it) {
                        LineEnding.LF -> lf
                        LineEnding.CRLF -> crlf
                        LineEnding.CR -> cr
                    }
                },
                onSelect = { vm.setLineEnding(it); controller.setLineEnding(it); dialog = EditorDialog.None },
                onDismiss = { dialog = EditorDialog.None },
            )
        }
        EditorDialog.TextSize -> TextSizeDialog(
            size = prefs.textSize,
            onSize = { onPrefs(prefs.copy(textSize = it)) },
            onDismiss = { dialog = EditorDialog.None },
        )
        EditorDialog.Leave -> ExpressiveDialog(
            onDismissRequest = { dialog = EditorDialog.None },
            title = stringResource(R.string.common_unsaved_changes),
            icon = Icons.Rounded.EditNote,
            tone = ArtTone.TERTIARY,
            shapes = HeroShapes.alert,
            sound = UiSound.ATTENTION,
            text = { Text(stringResource(R.string.editor_save_before_closing, state.name)) },
            confirmButton = {
                TextButton(onClick = { dialog = EditorDialog.None; save { onBack() } }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { dialog = EditorDialog.None; onBack() }) {
                        Text(stringResource(R.string.common_discard), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { dialog = EditorDialog.None }) { Text(stringResource(R.string.common_cancel)) }
                }
            },
        )
    }

    if (state.externallyModified) {
        AlertDialog(
            onDismissRequest = vm::ignoreExternalChange,
            title = { Text(stringResource(R.string.editor_file_changed)) },
            text = {
                Text(
                    stringResource(
                        if (state.dirty) R.string.editor_file_changed_text_unsaved else R.string.editor_file_changed_text,
                        state.name,
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { vm.load(path, rootEnabled, force = true) }) { Text(stringResource(R.string.editor_reload)) } },
            dismissButton = { TextButton(onClick = vm::ignoreExternalChange) { Text(stringResource(R.string.editor_keep_mine)) } },
        )
    }
    if (state.encodingProblem) {
        AlertDialog(
            onDismissRequest = vm::dismissEncodingProblem,
            title = { Text(stringResource(R.string.editor_cant_save_as, state.charset.displayName())) },
            text = { Text(stringResource(R.string.editor_cant_encode, state.charset.displayName())) },
            confirmButton = {
                TextButton(onClick = { vm.setSaveCharset(Charsets.UTF_8); save() }) { Text(stringResource(R.string.editor_save_as_utf8)) }
            },
            dismissButton = { TextButton(onClick = vm::dismissEncodingProblem) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main.immediate) { block() }

@Composable
private fun statusLine(state: EditorUiState, controller: EditorController): String = buildList {
    if (!state.loading && !state.tooLarge) add(stringResource(R.string.editor_status_position, controller.line + state.pageFirstLine, controller.column))
    if (state.paged) add(stringResource(R.string.editor_status_page, state.page + 1, state.pageCount))
    add(state.charset.displayName() + if (state.bom) " BOM" else "")
    add(state.lineEnding.label)
    add(state.language?.label ?: stringResource(R.string.editor_plain_text))
    if (state.readOnly) add(stringResource(R.string.editor_read_only))
}.joinToString(separator())

@Composable
private fun EditorMenu(
    expanded: Boolean,
    state: EditorUiState,
    prefs: EditorPrefs,
    onDismiss: () -> Unit,
    onPrefs: (EditorPrefs) -> Unit,
    onDialog: (EditorDialog) -> Unit,
    onReadOnly: (Boolean) -> Unit,
    onReload: () -> Unit,
    onOpenHex: () -> Unit,
    onColor: () -> Unit,
    onProcessText: () -> Unit,
    onPreview: (() -> Unit)?,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (onPreview != null) DropdownMenuItem(text = { Text(stringResource(R.string.editor_preview)) }, onClick = onPreview)
        DropdownMenuItem(text = { Text(stringResource(R.string.editor_menu_go_to_line)) }, onClick = { onDialog(EditorDialog.GoToLine) }, enabled = !state.tooLarge)
        DropdownMenuItem(text = { Text(stringResource(R.string.editor_menu_color)) }, onClick = onColor, enabled = !state.tooLarge && !state.readOnly)
        DropdownMenuItem(text = { Text(stringResource(R.string.editor_menu_process_text)) }, onClick = onProcessText, enabled = !state.tooLarge && !state.loading)
        HorizontalDivider()
        CheckItem(stringResource(R.string.editor_word_wrap), prefs.wordWrap) { onPrefs(prefs.copy(wordWrap = it)) }
        CheckItem(stringResource(R.string.editor_line_numbers), prefs.lineNumbers) { onPrefs(prefs.copy(lineNumbers = it)) }
        CheckItem(stringResource(R.string.editor_show_whitespace), prefs.showWhitespace) { onPrefs(prefs.copy(showWhitespace = it)) }
        CheckItem(stringResource(R.string.editor_symbol_bar), prefs.symbolBar) { onPrefs(prefs.copy(symbolBar = it)) }
        if (state.writable) CheckItem(stringResource(R.string.editor_read_only), state.readOnly) { onReadOnly(it) }
        HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(R.string.editor_menu_syntax)) }, onClick = { onDialog(EditorDialog.Syntax) })
        DropdownMenuItem(text = { Text(stringResource(R.string.editor_menu_theme)) }, onClick = { onDialog(EditorDialog.Theme) })
        DropdownMenuItem(text = { Text(stringResource(R.string.editor_menu_text_size)) }, onClick = { onDialog(EditorDialog.TextSize) })
        DropdownMenuItem(text = { Text(stringResource(R.string.editor_menu_encoding)) }, onClick = { onDialog(EditorDialog.Encoding) }, enabled = !state.tooLarge)
        DropdownMenuItem(text = { Text(stringResource(R.string.editor_menu_line_endings)) }, onClick = { onDialog(EditorDialog.LineEndings) }, enabled = !state.readOnly)
        HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(R.string.editor_reload)) }, onClick = onReload)
        DropdownMenuItem(text = { Text(stringResource(R.string.editor_open_in_hex)) }, onClick = onOpenHex)
    }
}

@Composable
private fun CheckItem(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
        onClick = { onChange(!checked) },
    )
}

@Composable
fun FindBar(controller: EditorController, readOnly: Boolean, onClose: () -> Unit, initial: SearchQuery = SearchQuery("")) {
    var query by remember { mutableStateOf(initial) }
    var replacement by remember { mutableStateOf("") }
    var replacing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) { error = controller.search(query) }

    val fieldStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        textDirection = if (query.regex) TextDirection.Ltr else TextDirection.Unspecified,
    )

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.testTag("find-bar")) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query.text,
                    onValueChange = { query = query.copy(text = it) },
                    placeholder = { Text(stringResource(R.string.editor_find)) },
                    singleLine = true,
                    isError = error != null,
                    suffix = {
                        if (query.text.isNotEmpty()) {
                            val count = controller.matchCount
                            val index = controller.matchIndex
                            Text(if (index >= 0) "${index + 1}/$count" else "$count", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { controller.next() }),
                    textStyle = fieldStyle,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                IconButton(onClick = { controller.previous() }) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.editor_previous_match))
                }
                IconButton(onClick = { controller.next() }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.editor_next_match))
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.editor_close_find)) }
            }
            if (replacing && !readOnly) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        placeholder = { Text(stringResource(R.string.editor_replace_with)) },
                        singleLine = true,
                        textStyle = fieldStyle,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { controller.replace(replacement) }, enabled = controller.matchCount > 0) {
                        Text(stringResource(R.string.common_replace))
                    }
                    TextButton(onClick = { controller.replaceAll(replacement) }, enabled = controller.matchCount > 0) {
                        Text(stringResource(R.string.editor_replace_all))
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                FilterChip(selected = query.regex, onClick = { query = query.copy(regex = !query.regex) }, label = { Text(stringResource(R.string.editor_regex)) })
                FilterChip(
                    selected = query.matchCase,
                    onClick = { query = query.copy(matchCase = !query.matchCase) },
                    label = { Text(stringResource(R.string.editor_match_case_short)) },
                )
                FilterChip(
                    selected = query.wholeWord,
                    onClick = { query = query.copy(wholeWord = !query.wholeWord) },
                    label = { Text(stringResource(R.string.editor_whole_word_short)) },
                )
                if (!readOnly) FilterChip(selected = replacing, onClick = { replacing = !replacing }, label = { Text(stringResource(R.string.common_replace)) })
            }
        }
    }
}

@Composable
private fun SymbolBar(onSymbol: (String) -> Unit) {
    val tab = stringResource(R.string.editor_symbol_tab)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.testTag("symbol-bar")) {
            LazyRow(contentPadding = PaddingValues(horizontal = 4.dp)) {
                items(SYMBOLS) { symbol ->
                    Box(
                        Modifier
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClickLabel = if (symbol == "\t") tab else symbol) { onSymbol(symbol) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (symbol == "\t") "⇥" else symbol,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageBar(page: Int, count: Int, onPrev: () -> Unit, onNext: () -> Unit, onPick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("page-bar"), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev, enabled = page > 0) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = stringResource(R.string.editor_previous_page))
            }
            TextButton(onClick = onPick, modifier = Modifier.weight(1f).testTag("page-label")) {
                Text(stringResource(R.string.editor_page_of, page + 1, count))
            }
            IconButton(onClick = onNext, enabled = page < count - 1) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = stringResource(R.string.editor_next_page))
            }
        }
    }
}

@Composable
private fun Banner(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun TooLarge(size: Long, onOpenHex: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.editor_too_large, Formatters.size(size), Formatters.size(TextEditorViewModel.MAX_BYTES)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpenHex, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.editor_open_in_hex)) }
    }
}

@Composable
private fun GoToLineDialog(
    lineCount: Int,
    onGo: (Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.editor_go_to_line),
    fieldLabel: String = stringResource(R.string.editor_line_range, 1, lineCount),
) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val target = text.toIntOrNull()
    val valid = target != null && target in 1..lineCount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit) },
                label = { Text(fieldLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (valid) onGo(target!!) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onGo(target!!) }, enabled = valid) { Text(stringResource(R.string.editor_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options) { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label(option), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
private fun EncodingDialog(
    current: Charset,
    dirty: Boolean,
    onReopen: (Charset) -> Unit,
    onSaveAs: (Charset) -> Unit,
    onDismiss: () -> Unit,
) {
    var saveMode by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_encoding)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !saveMode, onClick = { saveMode = false }, label = { Text(stringResource(R.string.editor_reopen_with)) })
                    FilterChip(selected = saveMode, onClick = { saveMode = true }, label = { Text(stringResource(R.string.editor_save_with)) })
                }
                if (!saveMode && dirty) {
                    Text(
                        stringResource(R.string.editor_reopen_discards),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
                    TextEncoding.choices.forEach { cs ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(selected = cs == current, role = Role.RadioButton, onClick = { if (saveMode) onSaveAs(cs) else onReopen(cs) })
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = cs == current, onClick = null)
                            Text(cs.displayName(), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
private fun TextSizeDialog(size: Float, onSize: (Float) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf(size) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_text_size)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.editor_text_size_value, value.toInt()),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = { onSize(value) },
                    valueRange = 8f..32f,
                    steps = 23,
                )
                Text(
                    stringResource(R.string.editor_pinch_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSize(value); onDismiss() }) { Text(stringResource(R.string.common_ok)) } },
    )
}

private const val MAX_PROCESS_TEXT = 100_000

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProcessTextDialog(apps: (() -> List<TextApp>)?, onPick: (TextApp) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val found by produceState<List<TextApp>?>(null) { value = withContext(Dispatchers.IO) { apps?.invoke() ?: Plugins.textApps(context) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_process_text_title)) },
        text = {
            val list = found
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("process-text-apps")) {
                when {
                    list == null -> LoadingIndicator()
                    list.isEmpty() -> Text(stringResource(R.string.editor_process_text_none))
                    else -> list.forEach { a ->
                        Text(
                            a.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 12.dp).testTag("text-app-${a.packageName}"),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

