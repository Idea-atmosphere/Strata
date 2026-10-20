package dev.cluvex.strata.feature.dex

import dev.cluvex.strata.core.i18n.separator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.EditOff
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.dex.ClassMember
import dev.cluvex.strata.core.dex.DexClass
import dev.cluvex.strata.core.dex.DexHit
import dev.cluvex.strata.core.dex.DexNode
import dev.cluvex.strata.core.dex.DexSearchMode
import dev.cluvex.strata.core.dex.DexSearchQuery
import dev.cluvex.strata.core.dex.MemberKind
import dev.cluvex.strata.core.dex.SmaliRefs
import dev.cluvex.strata.core.dex.TextMatcher
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.sound.UiSound
import dev.cluvex.strata.feature.editor.CodeEditorView
import dev.cluvex.strata.feature.editor.EditorChrome
import dev.cluvex.strata.feature.editor.EditorController
import dev.cluvex.strata.feature.editor.FindBar
import dev.cluvex.strata.feature.editor.OptionalFeatureState
import dev.cluvex.strata.feature.editor.SearchQuery
import dev.cluvex.strata.feature.editor.TextMateEngine
import dev.cluvex.strata.feature.editor.autoThemeId
import dev.cluvex.strata.feature.editor.isDarkBackground
import dev.cluvex.strata.feature.build.BuildDialog
import dev.cluvex.strata.ui.expressive.ArtTone
import dev.cluvex.strata.ui.expressive.ExpressiveDialog
import dev.cluvex.strata.ui.expressive.HeroShapes
import dev.cluvex.strata.ui.rememberSheetState
import io.github.rosemoe.sora.event.ClickEvent
import io.github.rosemoe.sora.event.EditorMotionEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DexScreen(
    path: String,
    onBack: () -> Unit,
    onLocate: (folder: String, reveal: String?) -> Unit,
    onSignApk: (String) -> Unit = {},
    vm: DexViewModel = viewModel(),
) {
    LaunchedEffect(path) { vm.open(path) }
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    BackHandler(enabled = s.open == null && !s.search.open) {
        when {
            s.query.isNotEmpty() -> vm.setQuery("")
            s.pkg.isNotEmpty() -> vm.goUp()
            else -> onBack()
        }
    }
    BackHandler(enabled = s.open == null && s.search.open) { vm.closeSearch() }
    BackHandler(enabled = s.open != null) { vm.back() }

    Box(Modifier.fillMaxSize().testTag("dex-screen")) {
        when {
            s.open != null -> ClassPage(s, vm, snackbar)
            s.search.open -> SearchPage(s, vm, snackbar)
            else -> TreePage(s, vm, snackbar, onBack, onLocate)
        }
    }

    s.build?.let { BuildDialog(it, onClose = vm::closeBuild) }

    s.leave?.let { leave ->
        ExpressiveDialog(
            onDismissRequest = vm::cancelLeave,
            title = stringResource(R.string.common_unsaved_changes),
            icon = Icons.Rounded.EditNote,
            tone = ArtTone.TERTIARY,
            shapes = HeroShapes.alert,
            sound = UiSound.ATTENTION,
            text = {
                val name = s.open?.simpleName
                Text(if (name != null) stringResource(R.string.dex_save_before_leaving, name) else stringResource(R.string.dex_save_before_leaving_class))
            },
            confirmButton = { TextButton(onClick = { vm.resolveLeave(save = true) }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = vm::cancelLeave) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(onClick = { vm.resolveLeave(save = false) }) { Text(stringResource(R.string.common_discard)) }
                }
            },
            modifier = Modifier.testTag("dex-leave-${leave.javaClass.simpleName}"),
        )
    }

    if (s.apkNeedsSigning && s.build == null) {
        AlertDialog(
            onDismissRequest = vm::acknowledgeApkSigning,
            title = { Text(stringResource(R.string.dex_apk_rebuilt)) },
            text = { Text(stringResource(R.string.dex_apk_rebuilt_text)) },
            confirmButton = { TextButton(onClick = { vm.acknowledgeApkSigning(); onSignApk(s.path) }) { Text(stringResource(R.string.common_sign_apk)) } },
            dismissButton = { TextButton(onClick = vm::acknowledgeApkSigning) { Text(stringResource(R.string.common_later)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TreePage(s: DexUiState, vm: DexViewModel, snackbar: SnackbarHostState, onBack: () -> Unit, onLocate: (String, String?) -> Unit) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
                title = {
                    Column {
                        Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val classesText = pluralStringResource(R.plurals.dex_class_count, s.classes.size, s.classes.size)
                        val readOnly = stringResource(R.string.dex_read_only)
                        Text(
                            classesText + if (s.editable) "" else " · $readOnly",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::openSearch, enabled = !s.loading && s.error == null, modifier = Modifier.testTag("dex-search-open")) {
                        Icon(Icons.AutoMirrored.Rounded.ManageSearch, contentDescription = stringResource(R.string.dex_search_in_dex))
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.dex_tools)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.dex_menu_to_jar)) }, onClick = { menu = false; vm.toJar { onLocate(it.parent ?: "/", it.path) } })
                            DropdownMenuItem(text = { Text(stringResource(R.string.dex_menu_to_smali)) }, onClick = { menu = false; vm.toSmali { onLocate(it.path, null) } })
                            DropdownMenuItem(text = { Text(stringResource(R.string.dex_decompile_java)) }, onClick = { menu = false; vm.toJava { onLocate(it.path, null) } })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                s.error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(s.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                else -> ClassTree(s, vm)
            }
        }
    }
}

@Composable
private fun ClassTree(s: DexUiState, vm: DexViewModel) {
    val nodes = remember(s.tree, s.pkg) { s.tree?.children(s.pkg).orEmpty() }
    val term = s.query.trim()
    val matches = remember(s.classes, term) { if (term.isEmpty()) emptyList() else s.classes.filter { it.fqcn.contains(term, ignoreCase = true) } }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = s.query,
            onValueChange = vm::setQuery,
            placeholder = { Text(stringResource(R.string.dex_find_class)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = if (s.query.isNotEmpty()) {
                { IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_clear)) } }
            } else {
                null
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("dex-filter"),
        )
        if (term.isEmpty()) Crumbs(s, vm)
        val noMatch = stringResource(R.string.dex_no_class_matches, term)
        LazyColumn(Modifier.fillMaxSize().testTag("dex-class-list"), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            if (term.isNotEmpty()) {
                items(matches, key = { "c:" + it.type }) { cls -> ClassRow(cls, showPackage = true) { vm.openClass(cls) } }
                if (matches.isEmpty()) item { EmptyNote(noMatch) }
            } else {
                items(nodes, key = { n -> if (n is DexNode.Package) "p:" + n.path else "c:" + (n as DexNode.Class).cls.type }) { n ->
                    when (n) {
                        is DexNode.Package -> PackageRow(n) { vm.enterPackage(n.path) }
                        is DexNode.Class -> ClassRow(n.cls, showPackage = false) { vm.openClass(n.cls) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Crumbs(s: DexUiState, vm: DexViewModel) {
    val crumbs = remember(s.tree, s.pkg) { s.tree?.crumbs(s.pkg).orEmpty() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).testTag("dex-crumbs"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { i, c ->
            val last = i == crumbs.lastIndex
            if (i > 0) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(
                if (i == 0) s.name else c.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !last) { vm.enterPackage(c.path) }.padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PackageRow(n: DexNode.Package, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(n.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.dex_class_count, n.classCount, n.classCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ClassRow(cls: DexClass, showPackage: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Code, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(cls.simpleName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showPackage && cls.packageName.isNotEmpty()) {
                Text(cls.packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(24.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchPage(s: DexUiState, vm: DexViewModel, snackbar: SnackbarHostState) {
    val q = s.search.query
    val search = s.search
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = vm::closeSearch) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
                title = {
                    Column {
                        Text(stringResource(R.string.dex_search_in_dex), maxLines = 1)
                        Text(s.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = q.text,
                onValueChange = { vm.setSearchQuery(q.copy(text = it)) },
                placeholder = { Text(stringResource(R.string.dex_text_to_find)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = if (q.text.isNotEmpty()) {
                    { IconButton(onClick = { vm.setSearchQuery(q.copy(text = "")) }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_clear)) } }
                } else {
                    null
                },
                singleLine = true,
                isError = search.error != null,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.runSearch() }),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("dex-search-field"),
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DexSearchMode.entries.forEach { m ->
                    FilterChip(selected = q.mode == m, onClick = { vm.setSearchQuery(q.copy(mode = m)) }, label = { Text(stringResource(m.label)) }, modifier = Modifier.testTag("dex-mode-${m.name}"))
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = q.matchCase, onClick = { vm.setSearchQuery(q.copy(matchCase = !q.matchCase)) }, label = { Text(stringResource(R.string.dex_match_case_short)) })
                FilterChip(selected = q.wholeWord, onClick = { vm.setSearchQuery(q.copy(wholeWord = !q.wholeWord)) }, label = { Text(stringResource(R.string.dex_whole_word)) })
                FilterChip(selected = q.regex, onClick = { vm.setSearchQuery(q.copy(regex = !q.regex)) }, label = { Text(stringResource(R.string.dex_regex)) })
                if (s.pkg.isNotEmpty()) {
                    val pkgLabel = s.tree?.crumbs(s.pkg)?.lastOrNull()?.label ?: s.pkg
                    val only = stringResource(R.string.dex_only_package, pkgLabel)
                    FilterChip(selected = q.inPackage == s.pkg, onClick = { vm.setSearchQuery(q.copy(inPackage = if (q.inPackage == s.pkg) null else s.pkg)) }, label = { Text(only) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (search.running) {
                    OutlinedButton(onClick = vm::stopSearch, modifier = Modifier.testTag("dex-search-stop")) { Text(stringResource(R.string.common_stop)) }
                } else {
                    Button(onClick = vm::runSearch, enabled = q.text.isNotEmpty(), modifier = Modifier.testTag("dex-search-run")) { Text(stringResource(R.string.common_search)) }
                }
                Spacer(Modifier.width(12.dp))
                val results = pluralStringResource(R.plurals.dex_results, search.hits.size, search.hits.size)
                val status = when {
                    search.error != null -> search.error
                    search.running -> stringResource(R.string.dex_search_progress, search.done, search.total, search.hits.size)
                    search.finished -> if (search.truncated) results + separator() + stringResource(R.string.dex_showing_first, search.hits.size) else results
                    else -> ""
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (search.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).testTag("dex-search-status"),
                )
            }
            if (search.running) {
                val fraction = if (search.total > 0) search.done.toFloat() / search.total else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            }
            HorizontalDivider(Modifier.padding(top = 6.dp))
            LazyColumn(Modifier.fillMaxSize().testTag("dex-hits"), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                items(search.hits) { hit -> HitRow(hit) { vm.openHit(hit) } }
                if (search.finished && search.hits.isEmpty() && search.error == null) item { EmptyNote(stringResource(R.string.dex_nothing_found)) }
            }
        }
    }
}

@Composable
private fun HitRow(hit: DexHit, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val text = remember(hit) {
        buildAnnotatedString {
            append(hit.text)
            if (hit.end > hit.start) addStyle(SpanStyle(background = colors.primaryContainer, color = colors.onPrimaryContainer, fontWeight = FontWeight.Bold), hit.start, hit.end)
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp).testTag("dex-hit")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(hit.cls.simpleName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.dex_line, hit.line), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        if (hit.cls.packageName.isNotEmpty()) {
            Text(hit.cls.packageName, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(text, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ClassPage(s: DexUiState, vm: DexViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val controller = remember { EditorController() }
    val colors = MaterialTheme.colorScheme
    val dark = isDarkBackground(colors.surface)
    val cls = s.open ?: return
    val smali = s.view == DexView.SMALI
    val editing = s.editable && s.editing && smali
    var finding by remember { mutableStateOf(false) }
    var findSeed by remember { mutableStateOf(SearchQuery("")) }
    var findKey by remember { mutableStateOf(0L) }
    var members by remember { mutableStateOf(false) }
    var syntaxFailureShown by remember(cls) { mutableStateOf(false) }
    val state by rememberUpdatedState(s)

    controller.onEdited = { vm.onCodeEdited(controller.text()) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
                title = {
                    Column {
                        Text(cls.simpleName + if (s.dirty) " ●" else "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("dex-class-title"))
                        if (cls.packageName.isNotEmpty()) {
                            Text(cls.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { finding = !finding; if (!finding) controller.stopSearch() }) { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.dex_find_in_class)) }
                    IconButton(onClick = { members = true }) { Icon(Icons.AutoMirrored.Rounded.List, contentDescription = stringResource(R.string.dex_members)) }
                    if (s.editable && smali) {
                        IconButton(onClick = { vm.setEditing(!s.editing) }) {
                            Icon(if (editing) Icons.Rounded.EditOff else Icons.Rounded.Edit, contentDescription = stringResource(if (editing) R.string.dex_stop_editing else R.string.dex_edit_smali))
                        }
                    }
                    if (editing) {
                        IconButton(onClick = {
                            val e = controller.editor ?: return@IconButton
                            val c = e.cursor
                            val ref = SmaliRefs.at(e.text.getLineString(c.leftLine), c.leftColumn)
                            if (ref == null) vm.say(tr(R.string.dex_put_cursor)) else vm.goTo(ref, c.leftLine + 1)
                        }) { Icon(Icons.Rounded.NorthEast, contentDescription = stringResource(R.string.dex_go_to_definition)) }
                        IconButton(onClick = { vm.save() }, enabled = s.dirty) { Icon(Icons.Rounded.Save, contentDescription = stringResource(R.string.common_save)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                SegmentedButton(selected = smali, onClick = { vm.setView(DexView.SMALI) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Smali") }
                SegmentedButton(selected = !smali, onClick = { vm.setView(DexView.JAVA) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Java") }
            }
            if (smali && !editing) {
                Text(
                    stringResource(R.string.dex_tap_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            if (finding) key(findKey) { FindBar(controller, readOnly = !editing, onClose = { finding = false; controller.stopSearch() }, initial = findSeed) }
            Box(Modifier.weight(1f).fillMaxWidth().testTag("dex-code")) {
                CodeEditorView(controller, Modifier.fillMaxSize())
                if (s.codeLoading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                s.codeError?.let { Text(it, color = colors.error, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }
            }
        }
    }

    BackHandler(enabled = finding) {
        finding = false
        controller.stopSearch()
    }

    LaunchedEffect(colors, controller.editor) {
        if (controller.editor == null) return@LaunchedEffect
        val availability = withContext(Dispatchers.Default) { TextMateEngine.init(context) }
        if (availability !is OptionalFeatureState.Ready) {
            controller.setLanguage(EmptyLanguage())
            if (!syntaxFailureShown) {
                syntaxFailureShown = true
                vm.say(tr(R.string.unity_editor_plain_text_fallback))
            }
            return@LaunchedEffect
        }
        val model = withContext(Dispatchers.Default) { TextMateEngine.activateTheme(autoThemeId(dark)) }
        if (model == null) {
            controller.setLanguage(EmptyLanguage())
            if (!TextMateEngine.isReady && !syntaxFailureShown) {
                syntaxFailureShown = true
                vm.say(tr(R.string.unity_editor_plain_text_fallback))
            }
            return@LaunchedEffect
        }
        val scheme = withContext(Dispatchers.Default) {
            TextMateEngine.colorScheme(model, EditorChrome.from(colors))
        }
        if (scheme == null) {
            controller.setLanguage(EmptyLanguage())
            if (!syntaxFailureShown) {
                syntaxFailureShown = true
                vm.say(tr(R.string.unity_editor_plain_text_fallback))
            }
            return@LaunchedEffect
        }
        withContext(Dispatchers.Main.immediate) { controller.setColorScheme(scheme) }
    }
    LaunchedEffect(s.view, controller.editor) {
        if (controller.editor == null) return@LaunchedEffect
        val scope = if (s.view == DexView.JAVA) "source.java" else "source.smali"
        val language = withContext(Dispatchers.Default) { TextMateEngine.language(scope) }
        if (language == null && !TextMateEngine.isReady && !syntaxFailureShown) {
            syntaxFailureShown = true
            vm.say(tr(R.string.unity_editor_plain_text_fallback))
        }
        withContext(Dispatchers.Main.immediate) { controller.setLanguage(language ?: EmptyLanguage()) }
    }
    LaunchedEffect(editing, controller.editor) { controller.setEditable(editing) }
    LaunchedEffect(cls) {
        if (s.jump?.find == null && finding) {
            finding = false
            controller.stopSearch()
        }
    }
    LaunchedEffect(cls, s.view, s.code, s.codeLoading, s.jump, controller.editor) {
        if (controller.editor == null || s.codeLoading) return@LaunchedEffect
        if (controller.text() != s.code) controller.setText(s.code)
        controller.setEditable(editing)
        val j = s.jump ?: return@LaunchedEffect
        val find = j.find
        if (find != null) {
            findSeed = find
            findKey = j.seq
            finding = true
            val line = controller.lineText(j.line)
            val range = line?.let { text -> runCatching { TextMatcher(DexSearchQuery(find.text, matchCase = find.matchCase, regex = find.regex, wholeWord = find.wholeWord)).find(text) }.getOrNull() }
            if (range != null) controller.select(j.line, range.first, range.last + 1) else controller.select(j.line, 0, 0)
        } else {
            controller.select(j.line, j.start, j.end)
        }
        vm.consumeJump()
    }
    DisposableEffect(controller.editor, editing, smali) {
        val e = controller.editor
        if (e == null || editing || !smali) return@DisposableEffect onDispose {}
        val receipt = e.subscribeEvent(ClickEvent::class.java) { event, _ ->
            if (event.motionRegion != EditorMotionEvent.REGION_TEXT) return@subscribeEvent
            if (state.codeLoading || state.view != DexView.SMALI) return@subscribeEvent
            val line = runCatching { e.text.getLineString(event.line) }.getOrNull() ?: return@subscribeEvent
            val rowBottom = e.getCharOffsetY(event.line, event.column)
            if (event.y > rowBottom || event.y < rowBottom - e.rowHeight) return@subscribeEvent
            if (event.x > e.getCharOffsetX(event.line, line.length) + e.rowHeight / 2f) return@subscribeEvent
            val ref = SmaliRefs.at(line, event.column) ?: return@subscribeEvent
            event.intercept()
            vm.goTo(ref, event.line + 1)
        }
        onDispose { receipt.unsubscribe() }
    }

    if (members) {
        val list = remember(s.view, s.javaMembers) { if (smali) vm.smaliMembers(controller.text()) else s.javaMembers }
        MembersSheet(list, onDismiss = { members = false }) { m ->
            members = false
            if (m.nameColumn > 0) controller.select(m.line, m.nameColumn, m.nameColumn + m.name.length) else controller.select(m.line, 0, 0)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MembersSheet(members: List<ClassMember>, onDismiss: () -> Unit, onPick: (ClassMember) -> Unit) {
    var filter by remember { mutableStateOf("") }
    val shown = remember(members, filter) { if (filter.isBlank()) members else members.filter { it.label.contains(filter.trim(), ignoreCase = true) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberSheetState()) {
        Column(Modifier.fillMaxWidth().testTag("dex-members")) {
            Text(
                pluralStringResource(R.plurals.dex_member_count, members.size, members.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            if (members.size > 8) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text(stringResource(R.string.dex_filter)) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            val staticLabel = stringResource(R.string.dex_static)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    items(shown) { m ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(m) }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                when (m.kind) {
                                    MemberKind.FIELD -> Icons.Rounded.Tag
                                    MemberKind.CONSTRUCTOR -> Icons.Rounded.Code
                                    MemberKind.METHOD -> Icons.Rounded.Functions
                                },
                                contentDescription = null,
                                tint = if (m.kind == MemberKind.FIELD) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                AnnotatedString(m.label),
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 12.dp),
                            )
                            if (m.isStatic) Text(staticLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("  ${m.line}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
