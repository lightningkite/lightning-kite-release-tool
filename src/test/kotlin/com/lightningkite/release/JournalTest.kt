package com.lightningkite.release

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalTest {
    private val directory: File = Files.createTempDirectory("journal").toFile()
    private val journalFile = directory.resolve("build/journal.json")
    private val properties = directory.resolve("gradle.properties").apply { writeText("version=1000.0.0\n") }
    private val settings = directory.resolve("settings.gradle.kts").apply { writeText("pin unreleased\n") }

    @Test
    fun theNextRunPutsBackWhatACrashedRunLeftEdited() {
        Journal(journalFile).run {
            edit(properties, "version=1000.0.1-local\n")
            edit(properties, "version=1000.0.2-local\n")
            edit(settings, "pin local\n")
        }
        // The process dies here.  A new one starts with the same journal file.
        val next = Journal(journalFile)
        assertEquals(setOf(properties, settings), next.edited.toSet())
        next.restore()
        assertEquals("version=1000.0.0\n", properties.readText(), "the text from before the first edit, not the second")
        assertEquals("pin unreleased\n", settings.readText())
        assertFalse(journalFile.exists())
    }

    @Test
    fun keptEditsStay() {
        val journal = Journal(journalFile)
        journal.edit(properties, "version=1000.0.1\n")
        journal.keep()
        journal.restore()
        assertEquals("version=1000.0.1\n", properties.readText())
    }

    @Test
    fun writingTheSameTextIsNotAnEdit() {
        val journal = Journal(journalFile)
        journal.edit(properties, "version=1000.0.0\n")
        assertTrue(journal.edited.isEmpty())
    }
}
