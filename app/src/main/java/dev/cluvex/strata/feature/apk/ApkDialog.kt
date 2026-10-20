package dev.cluvex.strata.feature.apk

import dev.cluvex.strata.core.i18n.separator
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.cluvex.strata.R
import dev.cluvex.strata.core.apk.ApkCloner
import dev.cluvex.strata.core.apk.AppIntents
import dev.cluvex.strata.core.apk.VersionRelation
import dev.cluvex.strata.core.apk.install.InstallFailure
import dev.cluvex.strata.core.apk.install.InstallResult
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.sound.UiSound
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.ui.expressive.ArtTone
import dev.cluvex.strata.ui.expressive.ExpressiveDialog
import dev.cluvex.strata.ui.expressive.HeroShapes
import dev.cluvex.strata.ui.rememberTextClipboard
import java.io.File

data class ApkDialogActions(
    val onView: () -> Unit,
    val onSign: () -> Unit,
    val onDetails: () -> Unit,
    val onDexTools: () -> Unit,
    val onUnityTranslation: () -> Unit,
    val onGoTo: (folder: String, reveal: String?) -> Unit,
    val onMessage: (String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ApkDialog(
    path: String,
    policy: InstallPolicy,
    actions: ApkDialogActions,
    onDismiss: () -> Unit,
    vm: ApkDialogViewModel = viewModel(key = "apk-dialog"),
) {
    LaunchedEffect(path) { vm.load(path) }
    LifecycleResumeEffect(path) {
        vm.refreshInstalled()
        onPauseOrDispose { }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.takeIf { it.path == path } ?: ApkDialogState(path = path)
    val context = LocalContext.current
    var cloneDialog by remember { mutableStateOf(false) }

    LaunchedEffect(s.message) {
        s.message?.let { actions.onMessage(it); vm.consumeMessage() }
    }

    BasicAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("apk-dialog")) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Header(s, onExtractIcon = vm::extractIcon)
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                ) {
                    when {
                        s.loading -> Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                            LoadingIndicator()
                        }
                        s.notAnApk -> Text(
                            stringResource(R.string.apk_not_an_apk),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                        else -> Details(s, actions)
                    }
                }
                InstallProgress(s.install)
                ButtonRow(s, policy, vm, actions, onClone = { cloneDialog = true }, onDismiss)
            }
        }
    }

    if (cloneDialog) {
        s.manifest?.let { m ->
            CloneApkDialog(
                oldPackage = m.packageName,
                label = m.label,
                icon = s.icon,
                onConfirm = { newPackage, newName, iconUri ->
                    cloneDialog = false
                    vm.cloneAndInstall(policy, newPackage, newName, iconUri)
                },
                onDismiss = { cloneDialog = false },
            )
        }
    }

    s.build?.let { dev.cluvex.strata.feature.build.BuildDialog(it, onClose = vm::closeBuild) }

    when (val phase = s.install) {
        InstallPhase.NeedsSignature -> AlertDialog(
            onDismissRequest = vm::resetInstall,
            title = { Text(stringResource(R.string.apk_not_signed)) },
            text = { Text(stringResource(R.string.apk_needs_signature_body)) },
            confirmButton = { TextButton(onClick = { vm.resetInstall(); actions.onSign() }) { Text(stringResource(R.string.common_sign_apk)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.install(policy, force = true) }) { Text(stringResource(R.string.apk_install_anyway)) }
                    TextButton(onClick = vm::resetInstall) { Text(stringResource(R.string.common_close)) }
                }
            },
        )
        is InstallPhase.Done -> InstallResultDialog(
            result = phase.result,
            icon = s.icon,
            viaRoot = phase.viaRoot,
            label = s.manifest?.label ?: s.fileName,
            packageName = s.manifest?.packageName,
            installedNow = s.installed != null,
            onLaunch = { pkg -> if (!AppIntents.launch(context, pkg)) actions.onMessage(tr(R.string.apk_no_launcher)) },
            onDetails = { pkg -> AppIntents.details(context, pkg) },
            onUninstall = { pkg -> AppIntents.uninstall(context, pkg) },
            onDowngrade = { vm.install(policy, force = true, allowDowngrade = true) },
            onClose = vm::resetInstall,
        )
        else -> Unit
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Header(s: ApkDialogState, onExtractIcon: () -> Unit) {
    var iconMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            val icon = s.icon
            if (icon != null) {
                Image(
                    icon.asImageBitmap(),
                    contentDescription = stringResource(R.string.apk_app_icon),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(onClick = { iconMenu = true }),
                )
            } else {
                Icon(Icons.Rounded.Android, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = iconMenu, onDismissRequest = { iconMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.apk_extract_icon_png)) }, onClick = { iconMenu = false; onExtractIcon() })
            }
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(
                s.manifest?.label ?: s.fileName,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                s.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Details(s: ApkDialogState, actions: ApkDialogActions) {
    val m = s.manifest ?: return
    Field(stringResource(R.string.apk_package_name), m.packageName, copy = true)
    Field(stringResource(R.string.apk_version), "${m.versionName} (${m.versionCode})")
    Field(stringResource(R.string.apk_file_size), Formatters.size(s.size))
    Field("SDK", stringResource(R.string.apk_sdk_range, m.minSdk, m.targetSdk))
    SignatureField(s)
    val protection = s.protection
    Field(
        stringResource(R.string.apk_protection),
        when {
            protection == null -> stringResource(R.string.apk_detecting)
            protection.isEmpty() -> stringResource(R.string.apk_none_detected)
            else -> protection.joinToString(", ")
        },
    )
    val installed = s.installed ?: return
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
    val relation = s.relation
    val version = "${installed.versionName} (${installed.versionCode})"
    Field(
        stringResource(R.string.apk_installed),
        if (relation == VersionRelation.SAME) version else version + separator() + stringResource(relation.label),
        highlight = relation == VersionRelation.DOWNGRADE,
    )
    installed.dataDir?.let { dir -> PathField(stringResource(R.string.apk_data_dir), dir) { actions.onGoTo(dir, null) } }
    PathField(stringResource(R.string.apk_data_dir_n, 2), installed.externalDataDir) { actions.onGoTo(installed.externalDataDir, null) }
    PathField(stringResource(R.string.apk_apk_path), installed.sourceDir) {
        actions.onGoTo(File(installed.sourceDir).parent ?: "/", installed.sourceDir)
    }
    Field("UID", installed.uid.toString())
    Field(stringResource(R.string.apk_first_install), Formatters.date(installed.firstInstall))
    Field(stringResource(R.string.apk_last_update), Formatters.date(installed.lastUpdate))
}

@Composable
private fun SignatureField(s: ApkDialogState) {
    val sig = s.signature
    val (text, ok) = when {
        sig == null -> stringResource(R.string.apk_detecting) to null
        sig.verified -> stringResource(R.string.apk_verified_schemes, sig.schemes.joinToString(" + ")) to true
        sig.schemes.isEmpty() && sig.error == null -> stringResource(R.string.apk_not_signed) to false
        else -> stringResource(R.string.apk_invalid_signature) to false
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(stringResource(R.string.apk_signature))
        if (ok != null) {
            Icon(
                if (ok) Icons.Rounded.GppGood else Icons.Rounded.GppBad,
                contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp).padding(end = 2.dp),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(100.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Field(label: String, value: String, mono: Boolean = false, copy: Boolean = false, highlight: Boolean = false) {
    val clipboard = rememberTextClipboard()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (copy) Modifier.combinedClickable(onClick = {}, onLongClick = { clipboard.setText(value) }) else Modifier)
            .padding(vertical = 3.dp),
    ) {
        Label(label)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PathField(label: String, path: String, onOpen: () -> Unit) {
    val clipboard = rememberTextClipboard()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onOpen, onLongClick = { clipboard.setText(path) })
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(label)
        Text(
            path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = stringResource(R.string.common_open), tint = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InstallProgress(phase: InstallPhase) {
    val text = when (phase) {
        InstallPhase.Verifying -> stringResource(R.string.apk_checking_signature)
        InstallPhase.Cloning -> stringResource(R.string.apk_cloning_progress)
        is InstallPhase.Installing -> stringResource(if (phase.viaRoot) R.string.apk_installing_root else R.string.apk_waiting_installer)
        else -> return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ButtonRow(
    s: ApkDialogState,
    policy: InstallPolicy,
    vm: ApkDialogViewModel,
    actions: ApkDialogActions,
    onClone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val busy = s.install is InstallPhase.Installing || s.install is InstallPhase.Verifying
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            TextButton(onClick = { menu = true }, shapes = ButtonDefaults.shapes(), enabled = !s.loading) {
                Text(stringResource(R.string.apk_function))
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val pkg = s.manifest?.packageName
                if (s.manifest != null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.apk_clone_coexist)) }, onClick = { menu = false; onClone() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_sign_apk)) }, onClick = { menu = false; actions.onSign() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.apk_details)) }, onClick = { menu = false; actions.onDetails() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.apk_dex_tools)) }, onClick = { menu = false; actions.onDexTools() })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.unity_discovery_apk_action)) },
                        onClick = { menu = false; actions.onUnityTranslation() },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.apk_extract_icon)) }, onClick = { menu = false; vm.extractIcon() })
                }
                val installed = s.installed
                if (installed != null && pkg != null) {
                    HorizontalDivider()
                    if (installed.launchable) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.apk_launch_app)) }, onClick = { menu = false; AppIntents.launch(context, pkg) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.apk_app_info)) }, onClick = { menu = false; AppIntents.details(context, pkg) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_uninstall)) }, onClick = { menu = false; AppIntents.uninstall(context, pkg) })
                }
                if (s.manifest == null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_close)) }, onClick = { menu = false; onDismiss() })
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { onDismiss(); actions.onView() }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.common_view)) }
        if (s.manifest != null) {
            val label = when (s.relation) {
                VersionRelation.UPGRADE -> stringResource(R.string.apk_update)
                VersionRelation.SAME -> stringResource(R.string.apk_reinstall)
                else -> stringResource(R.string.common_install)
            }
            Button(onClick = { vm.install(policy) }, shapes = ButtonDefaults.shapes(), enabled = !busy) { Text(label) }
        }
    }
}

@Composable
internal fun CloneApkDialog(
    oldPackage: String,
    label: String,
    icon: Bitmap?,
    onConfirm: (newPackage: String, newName: String, iconUri: Uri?) -> Unit,
    onDismiss: () -> Unit,
) {
    var pkg by remember { mutableStateOf(ApkCloner.suggestPackage(oldPackage)) }
    var name by remember { mutableStateOf(label) }
    var picture by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) picture = uri }
    val samePackage = stringResource(R.string.apk_clone_choose_different)
    val problem = if (pkg.isBlank()) null else ApkCloner.validatePackage(pkg) ?: (if (pkg == oldPackage) samePackage else null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apk_clone_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.apk_clone_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = pkg,
                    onValueChange = { pkg = it.trim() },
                    label = { Text(stringResource(R.string.apk_clone_new_package)) },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = { Text(problem ?: stringResource(R.string.apk_clone_original_package, oldPackage)) },
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier.fillMaxWidth().testTag("clone_package"),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.apk_clone_app_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        when {
                            picture != null -> AsyncImage(picture, contentDescription = stringResource(R.string.apk_clone_new_icon), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            icon != null -> Image(icon.asImageBitmap(), contentDescription = stringResource(R.string.apk_clone_current_icon), modifier = Modifier.fillMaxSize())
                            else -> Icon(Icons.Rounded.Android, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.apk_app_icon), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(if (picture != null) R.string.apk_clone_your_picture else R.string.apk_clone_original),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (picture != null) TextButton(onClick = { picture = null }) { Text(stringResource(R.string.apk_clone_reset)) }
                    TextButton(onClick = { pick.launch(arrayOf("image/*")) }, modifier = Modifier.testTag("clone_icon")) { Text(stringResource(R.string.apk_clone_change)) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = pkg.isNotBlank() && problem == null, onClick = { onConfirm(pkg.trim(), name.trim(), picture) }) { Text(stringResource(R.string.apk_clone_install)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun InstallResultDialog(
    result: InstallResult,
    icon: Bitmap?,
    viaRoot: Boolean,
    label: String,
    packageName: String?,
    installedNow: Boolean,
    onLaunch: (String) -> Unit,
    onDetails: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onDowngrade: () -> Unit,
    onClose: () -> Unit,
) {
    when (result) {
        is InstallResult.Success -> {
            val pkg = result.packageName ?: packageName
            val picture = remember(icon) { icon?.asImageBitmap() }
            ExpressiveDialog(
                onDismissRequest = onClose,
                title = stringResource(R.string.apk_install_success_title),
                picture = picture,
                icon = if (picture == null) Icons.Rounded.Check else null,
                shapes = HeroShapes.happy,
                sound = UiSound.COMPLETE,
                text = { Text(stringResource(R.string.apk_installed_message, label)) },
                confirmButton = {
                    if (pkg != null) TextButton(onClick = { onClose(); onLaunch(pkg) }) { Text(stringResource(R.string.apk_launch)) }
                },
                dismissButton = {
                    Row {
                        if (pkg != null) TextButton(onClick = { onClose(); onDetails(pkg) }) { Text(stringResource(R.string.common_details)) }
                        TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) }
                    }
                },
            )
        }
        is InstallResult.Failure -> ExpressiveDialog(
            onDismissRequest = onClose,
            title = stringResource(if (result.reason == InstallFailure.CANCELLED) R.string.apk_install_canceled_title else R.string.apk_install_failed_title),
            icon = if (result.reason == InstallFailure.CANCELLED) Icons.Rounded.Block else Icons.Rounded.ErrorOutline,
            tone = if (result.reason == InstallFailure.CANCELLED) ArtTone.SECONDARY else ArtTone.ERROR,
            shapes = HeroShapes.alert,
            sound = if (result.reason == InstallFailure.CANCELLED) null else UiSound.ERROR,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(result.reason.message))
                    if (result.detail.isNotBlank()) {
                        Text(
                            result.detail,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (result.reason == InstallFailure.SIGNATURE_CONFLICT || result.reason == InstallFailure.DOWNGRADE) {
                        Text(
                            stringResource(R.string.apk_uninstall_first_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) } },
            dismissButton = {
                Row {
                    val conflict = result.reason == InstallFailure.SIGNATURE_CONFLICT || result.reason == InstallFailure.DOWNGRADE
                    if (conflict && installedNow && packageName != null) {
                        TextButton(onClick = { onClose(); onUninstall(packageName) }) { Text(stringResource(R.string.common_uninstall)) }
                    }
                    if (result.reason == InstallFailure.DOWNGRADE && viaRoot) {
                        TextButton(onClick = onDowngrade) { Text(stringResource(R.string.apk_downgrade)) }
                    }
                }
            },
        )
    }
}
