package com.republicate.kroom.webapp.authoring

/**
 * Uploaded files — the pictures a block shows, the PDF it links. Names are handed out by the store, unique and
 * creation-ordered, so a file is never replaced under a page that shows it: an URL to media is immutable.
 */
interface Media {
    /** Store [bytes] under a new name built from [name] (its extension included), answered. */
    fun put(name: String, type: String, bytes: ByteArray): MediaFile

    fun get(name: String): MediaFile?

    fun read(name: String): ByteArray?

    /** False when there was nothing under [name]. */
    fun delete(name: String): Boolean

    /** Newest first. */
    fun list(limit: Int = 100): List<MediaFile>
}

data class MediaFile(val name: String, val type: String, val size: Long, val time: Long)

/** A store with no room left for [needed] more bytes. */
class MediaFullException(needed: Long) : IllegalStateException("no room left for $needed more bytes")

/**
 * What may be uploaded, told by the bytes themselves, never by the name or the type a client claims. No SVG:
 * served from the site's own origin, a picture that carries script is a stored XSS.
 */
object MediaTypes {
    private val extensions = mapOf(
        "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp",
        "image/avif" to "avif", "application/pdf" to "pdf"
    )
    private val byExtension = extensions.entries.associate { (type, ext) -> ext to type } + ("jpeg" to "image/jpeg")

    fun extension(type: String): String? = extensions[type]

    fun ofName(name: String): String? = byExtension[name.substringAfterLast('.', "").lowercase()]

    fun sniff(bytes: ByteArray): String? {
        fun at(offset: Int, vararg expected: Int) =
            bytes.size >= offset + expected.size && expected.indices.all { bytes[offset + it].toInt() and 0xff == expected[it] }
        fun text(offset: Int, value: String) = at(offset, *value.map { it.code }.toIntArray())
        return when {
            at(0, 0x89, 0x50, 0x4e, 0x47) -> "image/png"
            at(0, 0xff, 0xd8, 0xff) -> "image/jpeg"
            text(0, "GIF8") -> "image/gif"
            text(0, "RIFF") && text(8, "WEBP") -> "image/webp"
            text(4, "ftypavif") -> "image/avif"
            text(0, "%PDF-") -> "application/pdf"
            else -> null
        }
    }
}

private val SLUG = Regex("[^a-z0-9]+")

/** `Photo du Club!.JPG` → `<id>-photo-du-club.jpg`: unique, sortable by time, readable, safe as a path. */
internal fun mediaName(name: String, type: String): String {
    val base = SLUG.replace(name.substringBeforeLast('.').lowercase(), "-").trim('-').take(60).ifEmpty { "file" }
    return "${newRecordId()}-$base.${MediaTypes.extension(type) ?: "bin"}"
}

private val MEDIA_NAME = Regex("[a-z0-9]+-[a-z0-9-]+\\.[a-z0-9]+")

/** A name this store could have handed out — never a path. */
internal fun mediaNameOrNull(name: String): String? = name.takeIf { MEDIA_NAME.matches(it) }
