package com.srineere.airbridge.band

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam, asserted.
 *
 * `:app` used to reach into `:air` from six files; it now reaches in from
 * one, and that is the whole of what the `BandLink` extraction bought. The
 * saving is invisible to every other test in this module — nothing behaves
 * differently — so without this it would come back one convenient import at
 * a time, and nobody would notice until a second band had to be threaded
 * through six call sites again.
 *
 * This reads the source rather than the bytecode on purpose. The question is
 * not whether `:app` links against `:air` — it does, through the adapter, and
 * always will — but whether anything *else* names it.
 */
class BandSeamTest {

    private val sources = File("src/main/kotlin")

    /** The one file allowed to know which band is on the other end. */
    private val adapter = "AirBandLink.kt"

    @Test
    fun `only the adapter imports the air module`() {
        val importers = kotlinFiles()
            .filter { file ->
                file.readLines().any {
                    it.startsWith("import dev.airpulse.") || it.startsWith("import com.fitbit.goldengate.")
                }
            }
            .map { it.name }
            .sorted()

        assertEquals(
            "Only $adapter may import :air. Anything else wanting the band goes " +
                "through BandLink, or the next transport has to be threaded through " +
                "every one of these files.",
            listOf(adapter),
            importers,
        )
    }

    /**
     * A guard on the guard: if the tree ever moves and the glob finds nothing,
     * the assertion above would pass by being vacuous.
     */
    @Test
    fun `the source tree is where this test thinks it is`() {
        assertTrue("expected to find sources at ${sources.absolutePath}", sources.isDirectory)
        assertTrue("found only ${kotlinFiles().size} Kotlin files", kotlinFiles().size > 100)
        assertTrue(
            "$adapter is gone — if the adapter was renamed, rename it here too",
            kotlinFiles().any { it.name == adapter },
        )
    }

    private fun kotlinFiles(): List<File> =
        sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
}
