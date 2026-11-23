package io.github.artcraftandroid.filmcraft.engine

import java.io.File
import java.io.FileOutputStream
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
        val fontFiles: List<String>,
        val imageFiles: List<String>,
        val mediaFiles: List<String>,
        val metadataFiles: List<String>,
    )

    /**
     * Copies an imported template into the app's private library with a strict size limit.
     * The original is retained so a future renderer can reopen the complete package.
     */
    fun importToLibrary(input: InputStream, libraryDirectory: File): File {
        require(libraryDirectory.exists() || libraryDirectory.mkdirs()) {
            "Lou Studio couldn't create its MOGRT library."
        }
        val destination = File(libraryDirectory, "template-${System.currentTimeMillis()}.mogrt")
        var total = 0L
        try {
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_UNCOMPRESSED_BYTES) {
                        "This MOGRT file is too large to import safely."
                    }
                    output.write(buffer, 0, count)
                }
                output.flush()
            }
            require(total > 0L) { "The selected MOGRT file is empty." }
            return destination
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    data class ExtractedFont(val sourceEntry: String, val file: File)

    /**
     * Extracts only embedded TrueType/OpenType fonts into an app-private folder.
     * Web fonts are listed during inspection but are not installed as Android fonts.
     */
    fun extractEmbeddedFonts(packageFile: File, fontDirectory: File): List<ExtractedFont> {
        require(fontDirectory.exists() || fontDirectory.mkdirs()) {
            "Lou Studio couldn't create its private fonts folder."
        }
        val extracted = mutableListOf<ExtractedFont>()
        var total = 0L
        try {
            ZipInputStream(packageFile.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.replace('\\\\', '/')
                    require(!name.startsWith("/") && name.split('/').none { it == ".." }) {
                        "This MOGRT contains an unsafe file path."
                    }
                    val lower = name.lowercase()
                    if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) {
                        zip.closeEntry()
                        continue
                    }
                    require(extracted.size < 20) { "This MOGRT contains too many embedded fonts." }
                    val baseName = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val destination = File(fontDirectory, "mogrt-${packageFile.nameWithoutExtension}-${extracted.size}-$baseName")
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_UNCOMPRESSED_BYTES) {
                                "Embedded fonts exceed the safe extraction limit."
                            }
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                    require(destination.length() > 0L) { "An embedded font file is empty." }
                    extracted += ExtractedFont(name, destination)
                    zip.closeEntry()
                }
            }
            return extracted
        } catch (error: Throwable) {
            extracted.forEach { it.file.delete() }
            throw error
        }
    }

    fun inspect(input: InputStream): Inspection {
        val names = mutableListOf<String>()
        var total = 0L
        var hasManifest = false
        var hasPreview = false
        var hasAep = false
        val fonts = mutableListOf<String>()
        val images = mutableListOf<String>()
        val media = mutableListOf<String>()
        val metadata = mutableListOf<String>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                require(!name.startsWith("/") && name.split('/').none { it == ".." }) {
                    "This MOGRT contains an unsafe file path."
                }
                require(names.size < MAX_ENTRIES) { "This MOGRT contains too many files to inspect." }
                names += name
                val lower = name.lowercase()
                if (lower.endsWith("manifest.xml")) hasManifest = true
                if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) hasPreview = true
                if (lower.endsWith(".aep")) hasAep = true
                when {
                    lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".woff") || lower.endsWith(".woff2") -> fonts.add(name)
                    lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".svg") -> images.add(name)
                    lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".m4v") || lower.endsWith(".wav") || lower.endsWith(".mp3") -> media.add(name)
                    lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".jsx") || lower.endsWith(".js") -> metadata.add(name)
                }
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
            fontFiles = fonts.take(20),
            imageFiles = images.take(20),
            mediaFiles = media.take(20),
            metadataFiles = metadata.take(20),
        )
    }
}
