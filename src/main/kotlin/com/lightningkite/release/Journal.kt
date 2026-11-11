package com.lightningkite.release

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Remembers the original text of every file a run edits (version lines, catalog pins), so a failed build, or the
 * next run after a crash, can put them back.
 */
class Journal(private val file: File) {
    private fun originals(): Map<String, String> = if (file.exists()) Json.decodeFromString(file.readText()) else mapOf()

    fun edit(target: File, text: String) {
        if (target.readText() == text) return
        val originals = originals()
        if (target.path !in originals) {
            file.parentFile.mkdirs()
            file.writeText(Json.encodeToString(originals + (target.path to target.readText())))
        }
        target.writeText(text)
    }

    val edited: List<File> get() = originals().keys.map { File(it) }

    fun restore() {
        for ((path, text) in originals()) File(path).writeText(text)
        file.delete()
    }

    /** The edits are now meant to stay (they were committed). */
    fun keep() {
        file.delete()
    }
}
