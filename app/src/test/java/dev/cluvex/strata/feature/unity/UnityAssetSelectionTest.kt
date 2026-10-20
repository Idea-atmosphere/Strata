package dev.cluvex.strata.feature.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnityAssetSelectionTest {
    @Test
    fun tappingSelectedEntryCollapsesItsInlineActions() {
        assertThat(toggleUnityEntrySelection("entry-2", "entry-2")).isNull()
    }

    @Test
    fun tappingAnotherEntryMovesTheInlineActionsToThatEntry() {
        assertThat(toggleUnityEntrySelection("entry-2", "entry-7")).isEqualTo("entry-7")
        assertThat(toggleUnityEntrySelection(null, "entry-7")).isEqualTo("entry-7")
    }

    @Test
    fun editorHandoffIsConsumedOnceWithoutClearingANewerRequest() {
        val first = UnityEditorRequest(1L, "/cache/first.json")
        val second = UnityEditorRequest(2L, "/cache/second.json")

        assertThat(consumeUnityEditorRequest(first, 1L)).isNull()
        assertThat(consumeUnityEditorRequest(second, 1L)).isSameInstanceAs(second)
    }

    @Test
    fun tsvDocumentHandoffIsConsumedOnceWithoutClearingANewerRequest() {
        val first = UnityTsvExportRequest(3L, "first.tsv")
        val second = UnityTsvExportRequest(4L, "second.tsv")

        assertThat(consumeUnityTsvExportRequest(first, 3L)).isNull()
        assertThat(consumeUnityTsvExportRequest(second, 3L)).isSameInstanceAs(second)
    }
}
