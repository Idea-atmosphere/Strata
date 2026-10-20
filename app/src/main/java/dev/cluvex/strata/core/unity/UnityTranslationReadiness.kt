package dev.cluvex.strata.core.unity

/** Direction comes from runtime locale data; the inference algorithm never contains language lists. */
enum class UnityLanguageDirection {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
    UNKNOWN,
}

fun interface UnityLanguageDirectionResolver {
    fun resolve(languageCode: String): UnityLanguageDirection
}

enum class UnityRtlInferenceStatus {
    DETERMINED,
    NEEDS_RTL_AND_LTR_SAMPLES,
    AMBIGUOUS_BITS,
    TARGET_DIRECTION_UNKNOWN,
}

data class UnityRtlFlagInference(
    val status: UnityRtlInferenceStatus,
    val bitMask: Int? = null,
    val rtlBitSet: Boolean? = null,
    val targetDirection: UnityLanguageDirection = UnityLanguageDirection.UNKNOWN,
    val targetFlags: Int? = null,
) {
    init {
        if (status == UnityRtlInferenceStatus.DETERMINED) {
            require(bitMask != null && Integer.bitCount(bitMask) == 1) {
                "A determined RTL flag must contain exactly one bit"
            }
            require(rtlBitSet != null && targetDirection != UnityLanguageDirection.UNKNOWN && targetFlags != null) {
                "A determined RTL flag must include direction and target flags"
            }
        }
    }
}

/** Infers one bit only when it is uniquely constant and opposite across known RTL/LTR rows. */
object UnityRtlFlagInferer {
    fun infer(
        languages: List<LocalizationLanguage>,
        targetLanguageCode: String,
        templateFlags: Int,
        directionResolver: UnityLanguageDirectionResolver,
    ): UnityRtlFlagInference {
        val targetDirection = directionResolver.resolve(targetLanguageCode)
        if (targetDirection == UnityLanguageDirection.UNKNOWN) {
            return UnityRtlFlagInference(
                status = UnityRtlInferenceStatus.TARGET_DIRECTION_UNKNOWN,
                targetDirection = targetDirection,
            )
        }
        val rtl = languages.filter { directionResolver.resolve(it.code) == UnityLanguageDirection.RIGHT_TO_LEFT }
        val ltr = languages.filter { directionResolver.resolve(it.code) == UnityLanguageDirection.LEFT_TO_RIGHT }
        if (rtl.isEmpty() || ltr.isEmpty()) {
            return UnityRtlFlagInference(
                status = UnityRtlInferenceStatus.NEEDS_RTL_AND_LTR_SAMPLES,
                targetDirection = targetDirection,
            )
        }

        val candidates = ArrayList<Pair<Int, Boolean>>()
        for (bitIndex in 0 until Int.SIZE_BITS) {
            val mask = 1 shl bitIndex
            val rtlValues = rtl.map { it.flags and mask != 0 }.toSet()
            val ltrValues = ltr.map { it.flags and mask != 0 }.toSet()
            if (rtlValues.size == 1 && ltrValues.size == 1 && rtlValues.single() != ltrValues.single()) {
                candidates += mask to rtlValues.single()
            }
        }
        if (candidates.size != 1) {
            return UnityRtlFlagInference(
                status = UnityRtlInferenceStatus.AMBIGUOUS_BITS,
                targetDirection = targetDirection,
            )
        }

        val (mask, rtlBitSet) = candidates.single()
        val targetNeedsSetBit = when (targetDirection) {
            UnityLanguageDirection.RIGHT_TO_LEFT -> rtlBitSet
            UnityLanguageDirection.LEFT_TO_RIGHT -> !rtlBitSet
            UnityLanguageDirection.UNKNOWN -> error("Unknown direction was handled above")
        }
        val flags = if (targetNeedsSetBit) templateFlags or mask else templateFlags and mask.inv()
        return UnityRtlFlagInference(
            status = UnityRtlInferenceStatus.DETERMINED,
            bitMask = mask,
            rtlBitSet = rtlBitSet,
            targetDirection = targetDirection,
            targetFlags = flags,
        )
    }
}

data class UnityTranslationFontCoverage(
    val targetLanguageCode: String,
    val targetCodepointCount: Int,
    val sourceCodepointCount: Int,
    val missingTargetCodepoints: List<Int>,
    val missingSourceCodepoints: List<Int>,
    val inspectedTmpFontCount: Int,
    val inspectedRawFontCount: Int,
    val unavailableReason: UnityTextUnavailableReason? = null,
) {
    val missingCodepoints: List<Int> = (missingTargetCodepoints + missingSourceCodepoints).distinct().sorted()
    val missingCount: Int get() = missingCodepoints.size
    val complete: Boolean get() = unavailableReason == null && missingCodepoints.isEmpty()
}

/** Computes coverage from actual pending values, never from a script or language-name guess. */
object UnityTranslationFontCoverageEvaluator {
    fun evaluate(
        report: UnityFontReport,
        source: LocalizationSource,
        targetLanguageCode: String,
        sourceLanguageCode: String?,
    ): UnityTranslationFontCoverage {
        val targetIndex = source.languageIndex(targetLanguageCode)
        require(targetIndex >= 0) { "Unknown target language '$targetLanguageCode'" }
        val sourceIndex = sourceLanguageCode?.let(source::languageIndex)?.takeIf { it >= 0 && it != targetIndex }
        val targetText = source.terms.mapNotNull { it.values[targetIndex] }.joinToString("\n")
        val sourceText = sourceIndex?.let { index ->
            source.terms.mapNotNull { it.values[index] }.joinToString("\n")
        }.orEmpty()
        return UnityTranslationFontCoverage(
            targetLanguageCode = targetLanguageCode,
            targetCodepointCount = codepoints(targetText).size,
            sourceCodepointCount = codepoints(sourceText).size,
            missingTargetCodepoints = report.missingCodepointsFor(targetText).sorted(),
            missingSourceCodepoints = report.missingCodepointsFor(sourceText).sorted(),
            inspectedTmpFontCount = report.tmpFontAssets.size,
            inspectedRawFontCount = report.rawFonts.size,
            unavailableReason = report.unavailableReason,
        )
    }

    private fun codepoints(text: String): Set<Int> {
        val result = LinkedHashSet<Int>()
        var offset = 0
        while (offset < text.length) {
            val codepoint = Character.codePointAt(text, offset)
            offset += Character.charCount(codepoint)
            if (!Character.isWhitespace(codepoint)) result += codepoint
        }
        return result
    }
}

enum class UnityTranslationReadinessKind {
    LOCALIZATION_TABLES,
    TARGET_LANGUAGE,
    FONT_COVERAGE,
    COMPRESSION,
    ROUND_TRIP,
}

enum class UnityTranslationReadinessStatus {
    READY,
    ACTIONABLE,
    BLOCKED,
}

enum class UnityTranslationNextAction {
    DISCOVER_TABLES,
    SELECT_TARGET_LANGUAGE,
    INSPECT_OR_REPLACE_FONT,
    USE_SUPPORTED_COMPRESSION,
    PREPARE_TRANSLATION,
    VALIDATE_AND_STAGE,
    REVIEW_ROUND_TRIP_FAILURE,
    EXPORT_OUTPUT,
}

enum class UnityLocalizationRoundTripStatus {
    NOT_RUN,
    RUNNING,
    PASSED,
    FAILED,
}

enum class UnityApkSigningHandoffState {
    NOT_APK,
    VERIFIED_CERTIFICATE,
    SIGNING_REQUIRED,
}

object UnityApkSigningHandoffEvaluator {
    fun evaluate(isApk: Boolean, signatureVerified: Boolean, certificateCount: Int): UnityApkSigningHandoffState {
        require(certificateCount >= 0) { "APK certificate count cannot be negative" }
        return when {
            !isApk -> UnityApkSigningHandoffState.NOT_APK
            signatureVerified && certificateCount > 0 -> UnityApkSigningHandoffState.VERIFIED_CERTIFICATE
            else -> UnityApkSigningHandoffState.SIGNING_REQUIRED
        }
    }
}

data class UnityTranslationReadinessCheck(
    val kind: UnityTranslationReadinessKind,
    val status: UnityTranslationReadinessStatus,
    val nextAction: UnityTranslationNextAction?,
    val detail: String? = null,
)

data class UnityTranslationReadiness(val checks: List<UnityTranslationReadinessCheck>) {
    init {
        require(checks.map { it.kind }.toSet().size == UnityTranslationReadinessKind.entries.size) {
            "Translation readiness must contain every check exactly once"
        }
    }

    val canExport: Boolean get() = checks.all { it.status == UnityTranslationReadinessStatus.READY }
    val nextAction: UnityTranslationNextAction
        get() = checks.firstOrNull { it.status != UnityTranslationReadinessStatus.READY }?.nextAction
            ?: UnityTranslationNextAction.EXPORT_OUTPUT
}

data class UnityTranslationReadinessInput(
    val tableSelected: Boolean,
    val tableInspectionFailed: Boolean,
    val targetLanguageSelected: Boolean,
    val fontCoverage: UnityTranslationFontCoverage?,
    val compressionSupported: Boolean,
    val compressionDetail: String? = null,
    val translationPending: Boolean = false,
    val roundTripStatus: UnityLocalizationRoundTripStatus,
    val roundTripDetail: String? = null,
)

object UnityTranslationReadinessEvaluator {
    fun evaluate(input: UnityTranslationReadinessInput): UnityTranslationReadiness {
        val tables = when {
            input.tableSelected -> ready(UnityTranslationReadinessKind.LOCALIZATION_TABLES)
            input.tableInspectionFailed -> blocked(
                UnityTranslationReadinessKind.LOCALIZATION_TABLES,
                UnityTranslationNextAction.DISCOVER_TABLES,
                null,
            )
            else -> actionable(UnityTranslationReadinessKind.LOCALIZATION_TABLES, UnityTranslationNextAction.DISCOVER_TABLES)
        }
        val target = when {
            !input.tableSelected -> blocked(
                UnityTranslationReadinessKind.TARGET_LANGUAGE,
                UnityTranslationNextAction.DISCOVER_TABLES,
                null,
            )
            input.targetLanguageSelected -> ready(UnityTranslationReadinessKind.TARGET_LANGUAGE)
            else -> actionable(UnityTranslationReadinessKind.TARGET_LANGUAGE, UnityTranslationNextAction.SELECT_TARGET_LANGUAGE)
        }
        val font = when {
            !input.targetLanguageSelected -> blocked(
                UnityTranslationReadinessKind.FONT_COVERAGE,
                UnityTranslationNextAction.SELECT_TARGET_LANGUAGE,
                null,
            )
            input.fontCoverage == null -> actionable(
                UnityTranslationReadinessKind.FONT_COVERAGE,
                UnityTranslationNextAction.INSPECT_OR_REPLACE_FONT,
            )
            input.fontCoverage.complete -> ready(UnityTranslationReadinessKind.FONT_COVERAGE)
            else -> blocked(
                UnityTranslationReadinessKind.FONT_COVERAGE,
                UnityTranslationNextAction.INSPECT_OR_REPLACE_FONT,
                fontDetail(input.fontCoverage),
            )
        }
        val compression = if (input.compressionSupported) {
            ready(UnityTranslationReadinessKind.COMPRESSION, input.compressionDetail)
        } else {
            blocked(
                UnityTranslationReadinessKind.COMPRESSION,
                UnityTranslationNextAction.USE_SUPPORTED_COMPRESSION,
                input.compressionDetail,
            )
        }
        val prerequisitesReady = listOf(tables, target, font, compression).all {
            it.status == UnityTranslationReadinessStatus.READY
        }
        val roundTrip = when (input.roundTripStatus) {
            UnityLocalizationRoundTripStatus.PASSED -> ready(
                UnityTranslationReadinessKind.ROUND_TRIP,
                input.roundTripDetail,
            )
            UnityLocalizationRoundTripStatus.FAILED -> blocked(
                UnityTranslationReadinessKind.ROUND_TRIP,
                UnityTranslationNextAction.REVIEW_ROUND_TRIP_FAILURE,
                input.roundTripDetail,
            )
            UnityLocalizationRoundTripStatus.RUNNING -> blocked(
                UnityTranslationReadinessKind.ROUND_TRIP,
                UnityTranslationNextAction.VALIDATE_AND_STAGE,
                input.roundTripDetail,
            )
            UnityLocalizationRoundTripStatus.NOT_RUN -> if (prerequisitesReady) {
                actionable(
                    UnityTranslationReadinessKind.ROUND_TRIP,
                    if (input.translationPending) {
                        UnityTranslationNextAction.VALIDATE_AND_STAGE
                    } else {
                        UnityTranslationNextAction.PREPARE_TRANSLATION
                    },
                )
            } else {
                blocked(
                    UnityTranslationReadinessKind.ROUND_TRIP,
                    listOf(tables, target, font, compression)
                        .first { it.status != UnityTranslationReadinessStatus.READY }
                        .nextAction,
                    null,
                )
            }
        }
        return UnityTranslationReadiness(listOf(tables, target, font, compression, roundTrip))
    }

    private fun fontDetail(coverage: UnityTranslationFontCoverage): String? {
        if (coverage.unavailableReason != null) return null
        val values = coverage.missingCodepoints.take(32).joinToString(",") {
            "U+${it.toString(16).uppercase().padStart(4, '0')}"
        }
        return if (coverage.missingCodepoints.size > 32) "$values,…" else values
    }

    private fun ready(kind: UnityTranslationReadinessKind, detail: String? = null) =
        UnityTranslationReadinessCheck(kind, UnityTranslationReadinessStatus.READY, null, detail)

    private fun actionable(kind: UnityTranslationReadinessKind, action: UnityTranslationNextAction) =
        UnityTranslationReadinessCheck(kind, UnityTranslationReadinessStatus.ACTIONABLE, action)

    private fun blocked(kind: UnityTranslationReadinessKind, action: UnityTranslationNextAction?, detail: String?) =
        UnityTranslationReadinessCheck(kind, UnityTranslationReadinessStatus.BLOCKED, action, detail)
}
