package dev.cluvex.strata.feature.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException

class UnityLocalizationDraftTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun importedTsvIsBoundedStagedAndStrictlyDecoded() {
        val expected = "Key\tEnglish\tPersian\nhello\tHello\tسلام\n"
        val directory = temp.newFolder("drafts")

        val staged = stageImportedLocalizationTsv(directory, open = {
            ByteArrayInputStream(expected.toByteArray(Charsets.UTF_8))
        })

        assertThat(staged.isFile).isTrue()
        assertThat(readLocalizationTsvUtf8(staged)).isEqualTo(expected)
        assertThat(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") }).isTrue()
    }

    @Test
    fun oversizedImportDeletesEveryPartialFile() {
        val directory = temp.newFolder("bounded")

        assertThrows(IOException::class.java) {
            stageImportedLocalizationTsv(
                directory = directory,
                open = { ByteArrayInputStream(ByteArray(9) { it.toByte() }) },
                maxBytes = 8,
            )
        }

        assertThat(directory.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun malformedUtf8IsRejectedInsteadOfReplaced() {
        val file = temp.newFile("malformed.tsv")
        file.writeBytes(byteArrayOf(0x4b, 0x09, 0xc3.toByte(), 0x28))

        assertThrows(IOException::class.java) {
            readLocalizationTsvUtf8(file)
        }
    }
}
