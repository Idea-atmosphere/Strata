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
}
