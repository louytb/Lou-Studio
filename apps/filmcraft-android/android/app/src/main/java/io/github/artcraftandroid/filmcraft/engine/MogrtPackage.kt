package io.github.artcraftandroid.filmcraft.engine

import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Safely inspects the ZIP container of an Adobe Motion Graphics Template.
 *
 * This is deliberately a package inspector, not a renderer: After Effects expressions,
 * Premiere graphics and embedded plug-ins need a separate compatible rendering engine.
 */
object MogrtPackage {
    private const val MAX_ENTRIES = 256
    private const val MAX_UNCOMPRESSED_BYTES = 128L * 1024L * 1024L

    enum class Origin { AFTER_EFFECTS, PREMIERE_OR_UNKNOWN }

    data class Inspection(
        val origin: Origin,
        val entries: List<String>,
        val hasManifest: Boolean,
        val hasPreview: Boolean,
        val hasProject: Boolean,
    )

    fun inspect(input: InputStream): Inspection {
        val names = mutableListOf<String>()
        var total = 0L
        var hasManifest = false
        var hasPreview = false
        var hasAep = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\\\', '/')
                require(!name.startsWith("/") && name.split('/').none { it == ".." }) {
                    "This MOGRT contains an unsafe file path."
                }
                require(names.size < MAX_ENTRIES) { "This MOGRT contains too many files to inspect." }
                names += name
                val lower = name.lowercase()
                if (lower.endsWith("manifest.xml")) hasManifest = true
                if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) hasPreview = true
                if (lower.endsWith(".aep")) hasAep = true
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_UNCOMPRESSED_BYTES) {
                        "This MOGRT is too large to inspect safely on this device."
                    }
                }
                zip.closeEntry()
            }
        }
        require(names.isNotEmpty()) { "This file is not a valid MOGRT package." }
        return Inspection(
            origin = if (hasAep) Origin.AFTER_EFFECTS else Origin.PREMIERE_OR_UNKNOWN,
            entries = names,
            hasManifest = hasManifest,
            hasPreview = hasPreview,
            hasProject = hasAep,
        )
    }
}
