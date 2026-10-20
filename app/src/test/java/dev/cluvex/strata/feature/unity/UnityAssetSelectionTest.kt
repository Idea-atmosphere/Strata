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
        val first = UnityEditorRequest(1L, "/cache/first.tsv")
        val second = UnityEditorRequest(2L, "/cache/second.tsv")

        assertThat(consumeUnityEditorRequest(first, 1L)).isNull()
        assertThat(consumeUnityEditorRequest(second, 1L)).isSameInstanceAs(second)
    }
}
