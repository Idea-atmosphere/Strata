package dev.cluvex.strata.feature.unity

import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.open.IncomingPaths
import dev.cluvex.strata.core.unity.UnityLocalizationBudgetStopReason
import dev.cluvex.strata.core.unity.UnityLocalizationCandidateReason
import dev.cluvex.strata.core.unity.UnityLocalizationOpenRequest
import dev.cluvex.strata.core.unity.UnityLocalizationScanBudget
import dev.cluvex.strata.core.unity.UnityLocalizationScanProgress
import dev.cluvex.strata.core.unity.UnityTranslationNextAction
import dev.cluvex.strata.core.unity.UnityTranslationReadiness
import dev.cluvex.strata.core.unity.UnityTranslationReadinessKind
import dev.cluvex.strata.core.unity.UnityTranslationReadinessStatus
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.ui.isolated
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/** Standalone APK entry point; candidates open in the ordinary Unity asset workflow. */
@Composable
fun UnityLocalizationDiscoveryScreen(
    initialApkPath: String?,
    onBack: () -> Unit,
    onOpen: (UnityLocalizationOpenRequest) -> Unit,
    vm: UnityAssetViewModel = viewModel(key = "unity-localization-discovery:${initialApkPath.orEmpty()}"),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsStateWithLifecycle()
    var stagingApk by remember { mutableStateOf(false) }
    var stagingIdentity by remember { mutableStateOf<String?>(null) }
    var stagingJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(initialApkPath) {
        if (vm.state.value.discoveryReport == null && !vm.state.value.discoveryRunning) {
            initialApkPath?.let { path ->
                val apk = File(path)
                vm.discoverLocalizationApk(apk, apk.path)
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            val path = id?.let {
                IncomingPaths.fromDocumentId(
                    uri.authority,
                    it,
                    Environment.getExternalStorageDirectory().path,
                )
            }
            if (path == null) {
                vm.notifyMessage(context.getString(R.string.unity_discovery_folder_unavailable))
            } else {
                vm.discoverLocalizationDirectory(File(path), path)
            }
        }
    }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            stagingApk = true
            stagingIdentity = uri.toString()
            stagingJob = scope.launch {
                try {
                    val apk = runInterruptible(Dispatchers.IO) {
                        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                        val local = id?.let {
                            IncomingPaths.fromDocumentId(
                                uri.authority,
                                it,
                                Environment.getExternalStorageDirectory().path,
                            )
                        }?.let(::File)?.takeIf(File::isFile)
                        local ?: stagePickedApk(
                            context.cacheDir,
                            uri.toString(),
                            open = {
                                context.contentResolver.openInputStream(uri)
                                    ?: throw IOException("APK source unavailable")
                            },
                        )
                    }
                    vm.discoverLocalizationApk(apk, apk.path)
                } catch (_: CancellationException) {
                    // The temporary file is deleted by stagePickedApk.
                } catch (error: Exception) {
                    val detail = error.message?.takeIf { it.isNotBlank() }
                    vm.notifyMessage(
                        if (detail == null) context.getString(R.string.unity_operation_failed)
                        else context.getString(R.string.unity_operation_failed_detail, detail),
                    )
                } finally {
                    stagingApk = false
                    stagingIdentity = null
                    stagingJob = null
                }
            }
        }
    }

    fun leave() {
        stagingJob?.cancel()
        vm.clearDiscovery()
        onBack()
    }
    val dialogState = if (stagingApk) {
        state.copy(
            discoveryRunning = true,
            discoveryProgress = UnityLocalizationScanProgress(0, 1, 0L, stagingIdentity),
            discoveryReport = null,
            discoveryScopeLabel = stagingIdentity,
        )
    } else {
        state
    }
    BackHandler(enabled = !dialogState.discoveryRunning) { leave() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            state.message ?: stringResource(R.string.unity_discovery_title),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    UnityLocalizationDiscoveryDialog(
        state = dialogState,
        onPickFolder = { folderPicker.launch(null) },
        onPickApk = {
            apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/zip"))
        },
        onOpen = onOpen,
        onCancel = {
            if (stagingApk) stagingJob?.cancel() else vm.cancelDiscovery()
        },
        onDismiss = ::leave,
    )
}

@Composable
internal fun UnityLocalizationDiscoveryDialog(
    state: UnityAssetUiState,
    onPickFolder: () -> Unit,
    onPickApk: () -> Unit,
    onOpen: (UnityLocalizationOpenRequest) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val report = state.discoveryReport
    AlertDialog(
        onDismissRequest = { if (!state.discoveryRunning) onDismiss() },
        title = { Text(stringResource(R.string.unity_discovery_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = onPickFolder,
                        enabled = !state.discoveryRunning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.unity_discovery_pick_folder), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onPickApk,
                        enabled = !state.discoveryRunning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.unity_discovery_scan_apk), maxLines = 1)
                    }
                }
                state.message?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.discoveryRunning) {
                    val progress = state.discoveryProgress
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(
                            R.string.unity_discovery_progress,
                            progress?.scannedFiles ?: 0,
                            progress?.totalFiles ?: 0,
                            progress?.currentPath ?: state.discoveryScopeLabel.orEmpty(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                report?.budgetStopReason?.let { reason ->
                    Text(
                        stringResource(
                            when (reason) {
                                UnityLocalizationBudgetStopReason.FILE_LIMIT -> R.string.unity_discovery_budget_file
                                UnityLocalizationBudgetStopReason.SOURCE_BYTES -> R.string.unity_discovery_budget_bytes
                                UnityLocalizationBudgetStopReason.ELAPSED_TIME -> R.string.unity_discovery_budget_time
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                when {
                    report == null && !state.discoveryRunning -> Text(
                        stringResource(R.string.unity_discovery_no_results),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    report?.candidates?.isEmpty() == true -> Text(
                        stringResource(R.string.unity_discovery_no_results),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    report != null -> LazyColumn(
                        Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(
                            report.candidates,
                            key = { index, candidate -> "$index:${candidate.path}" },
                        ) { _, candidate ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (candidate.hasLocalizationTables) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        isolated(candidate.path),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val languageLabel = candidate.languages.joinToString(", ").ifBlank { "—" }
                                    val outcome = if (candidate.hasLocalizationTables) {
                                        languageLabel
                                    } else {
                                        candidate.reason?.let { unityDiscoveryReasonLabel(it) }.orEmpty()
                                    }
                                    Text(
                                        stringResource(
                                            R.string.unity_discovery_result_detail,
                                            File(candidate.path.substringAfterLast("!/", candidate.path)).name,
                                            Formatters.size(candidate.fileSize),
                                            candidate.tableCount,
                                            candidate.termCount,
                                            outcome,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    candidate.diagnostic?.takeIf { it.isNotBlank() }?.let { diagnostic ->
                                        Text(
                                            diagnostic,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    candidate.openRequest?.let { request ->
                                        TextButton(
                                            onClick = { onOpen(request) },
                                            enabled = !state.discoveryRunning,
                                        ) {
                                            Text(stringResource(R.string.unity_discovery_open))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = if (state.discoveryRunning) onCancel else onDismiss) {
                Text(
                    stringResource(
                        if (state.discoveryRunning) R.string.common_cancel
                        else R.string.unity_discovery_close,
                    ),
                )
            }
        },
    )
}

@Composable
internal fun UnityTranslationReadinessPanel(
    readiness: UnityTranslationReadiness,
    busy: Boolean,
    onNextAction: (UnityTranslationNextAction) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            readiness.checks.forEach { check ->
                val status = when (check.status) {
                    UnityTranslationReadinessStatus.READY -> stringResource(R.string.unity_readiness_ready)
                    UnityTranslationReadinessStatus.ACTIONABLE -> stringResource(R.string.unity_readiness_actionable)
                    UnityTranslationReadinessStatus.BLOCKED -> stringResource(R.string.unity_readiness_blocked)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(unityReadinessKindLabel(check.kind), style = MaterialTheme.typography.bodySmall)
                    Text(
                        status,
                        style = MaterialTheme.typography.labelMedium,
                        color = when (check.status) {
                            UnityTranslationReadinessStatus.READY -> MaterialTheme.colorScheme.primary
                            UnityTranslationReadinessStatus.ACTIONABLE -> MaterialTheme.colorScheme.tertiary
                            UnityTranslationReadinessStatus.BLOCKED -> MaterialTheme.colorScheme.error
                        },
                    )
                }
                check.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val next = readiness.nextAction
            Button(
                onClick = { onNextAction(next) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Text(
                    stringResource(R.string.unity_readiness_next_action, unityReadinessActionLabel(next)),
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun unityReadinessKindLabel(kind: UnityTranslationReadinessKind): String = stringResource(
    when (kind) {
        UnityTranslationReadinessKind.LOCALIZATION_TABLES -> R.string.unity_readiness_tables
        UnityTranslationReadinessKind.TARGET_LANGUAGE -> R.string.unity_readiness_target
        UnityTranslationReadinessKind.FONT_COVERAGE -> R.string.unity_readiness_font
        UnityTranslationReadinessKind.COMPRESSION -> R.string.unity_readiness_compression
        UnityTranslationReadinessKind.ROUND_TRIP -> R.string.unity_readiness_round_trip
    },
)

@Composable
internal fun unityReadinessActionLabel(action: UnityTranslationNextAction): String = stringResource(
    when (action) {
        UnityTranslationNextAction.DISCOVER_TABLES -> R.string.unity_readiness_action_discover
        UnityTranslationNextAction.SELECT_TARGET_LANGUAGE -> R.string.unity_readiness_action_target
        UnityTranslationNextAction.INSPECT_OR_REPLACE_FONT -> R.string.unity_readiness_action_font
        UnityTranslationNextAction.USE_SUPPORTED_COMPRESSION -> R.string.unity_readiness_action_compression
        UnityTranslationNextAction.PREPARE_TRANSLATION -> R.string.unity_readiness_action_prepare
        UnityTranslationNextAction.VALIDATE_AND_STAGE -> R.string.unity_readiness_action_validate
        UnityTranslationNextAction.REVIEW_ROUND_TRIP_FAILURE -> R.string.unity_readiness_action_review
        UnityTranslationNextAction.EXPORT_OUTPUT -> R.string.unity_readiness_action_export
    },
)

@Composable
private fun unityDiscoveryReasonLabel(reason: UnityLocalizationCandidateReason): String = stringResource(
    when (reason) {
        UnityLocalizationCandidateReason.LOCALIZATION_TABLE_NOT_FOUND -> R.string.unity_discovery_reason_no_table
        UnityLocalizationCandidateReason.NOT_A_UNITY_CONTAINER -> R.string.unity_discovery_reason_not_unity
        UnityLocalizationCandidateReason.SOURCE_MISSING -> R.string.unity_discovery_reason_missing
        UnityLocalizationCandidateReason.SOURCE_NOT_A_FILE -> R.string.unity_discovery_reason_not_file
        UnityLocalizationCandidateReason.SOURCE_UNREADABLE -> R.string.unity_discovery_reason_unreadable
        UnityLocalizationCandidateReason.SOURCE_METADATA_MISMATCH -> R.string.unity_discovery_reason_changed
        UnityLocalizationCandidateReason.PROBE_FAILED -> R.string.unity_discovery_reason_probe
        UnityLocalizationCandidateReason.OPEN_FAILED -> R.string.unity_discovery_reason_open
        UnityLocalizationCandidateReason.INSPECTION_FAILED -> R.string.unity_discovery_reason_inspect
        UnityLocalizationCandidateReason.ARCHIVE_ENTRY_EXTRACTION_FAILED -> R.string.unity_discovery_reason_extract
        UnityLocalizationCandidateReason.ARCHIVE_ENTRY_DUPLICATE -> R.string.unity_discovery_reason_duplicate
        UnityLocalizationCandidateReason.ARCHIVE_ENTRY_UNSAFE_PATH -> R.string.unity_discovery_reason_unsafe_path
    },
)

internal fun stagePickedApk(
    cacheDir: File,
    identity: String,
    open: () -> InputStream,
    maxBytes: Long = UnityLocalizationScanBudget().maxSourceBytes,
): File {
    require(maxBytes > 0L) { "Picked APK staging limit must be positive" }
    val root = File(cacheDir, "unity-picked-apk")
    if (!root.isDirectory && !root.mkdirs()) throw IOException("Could not create picked APK staging directory")
    val output = File(root, "picked-${identity.hashCode().toUInt().toString(16)}-${System.nanoTime()}.apk")
    val temporary = File(root, ".${output.name}.tmp")
    var written = 0L
    try {
        open().buffered(64 * 1024).use { input ->
            FileOutputStream(temporary).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) {
                        throw InterruptedIOException("APK staging was cancelled")
                    }
                    val read = input.read(buffer)
                    if (read < 0) break
                    written += read
                    if (written > maxBytes) {
                        throw IOException("Picked APK exceeds the $maxBytes byte staging limit")
                    }
                    stream.write(buffer, 0, read)
                }
                stream.fd.sync()
            }
        }
        if (written <= 0L || temporary.length() != written) {
            throw IOException("Picked APK failed staged size verification")
        }
        if (!temporary.renameTo(output)) throw IOException("Could not atomically stage picked APK")
        return output
    } catch (error: Throwable) {
        temporary.delete()
        throw error
    }
}
