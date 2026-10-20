package dev.cluvex.strata.feature.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.ServiceConfigurationError

class TextMateInitializationTest {
    @Test
    fun serviceLoaderFailureIsNonFatalAndNeverRetried() {
        val gate = OptionalInitializationGate()
        val failure = ServiceConfigurationError("broken provider")
        var attempts = 0

        val first = gate.initialize {
            attempts += 1
            throw failure
        }
        val second = gate.initialize {
            attempts += 1
        }

        assertThat(first).isInstanceOf(OptionalFeatureState.Unavailable::class.java)
        assertThat((first as OptionalFeatureState.Unavailable).error).isSameInstanceAs(failure)
        assertThat(second).isSameInstanceAs(first)
        assertThat(gate.isReady).isFalse()
        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun successfulInitializationRunsOnlyOnce() {
        val gate = OptionalInitializationGate()
        var attempts = 0

        assertThat(gate.initialize { attempts += 1 }).isSameInstanceAs(OptionalFeatureState.Ready)
        assertThat(gate.initialize { attempts += 1 }).isSameInstanceAs(OptionalFeatureState.Ready)
        assertThat(gate.isReady).isTrue()
        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun runtimeFailureDisablesAPreviouslyReadyOptionalFeature() {
        val gate = OptionalInitializationGate()
        gate.initialize { }
        val failure = LinkageError("native grammar backend unavailable")

        gate.disable(failure)

        val state = gate.state as OptionalFeatureState.Unavailable
        assertThat(state.error).isSameInstanceAs(failure)
        assertThat(gate.isReady).isFalse()
    }
}
