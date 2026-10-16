package com.shaneblackburn.progallery.core.util

/**
 * Validation and generation of file and folder names that are safe on Android
 * shared storage (FAT/exFAT/sdcardfs rules are the strictest common denominator).
 */
object FileNames {

    private val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')
    const val MAX_NAME_BYTES = 255

    sealed interface Validation {
        data object Ok : Validation
        data class Invalid(val reason: String) : Validation
    }

    fun validate(name: String): Validation {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> Validation.Invalid("Name can't be empty")
            trimmed == "." || trimmed == ".." -> Validation.Invalid("That name is reserved")
            trimmed.startsWith(".") -> Validation.Invalid("Names starting with a dot are hidden by Android")
            trimmed.any { it in ILLEGAL || it.code < 32 } ->
                Validation.Invalid("Name can't contain / \\ : * ? \" < > |")
            trimmed.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> Validation.Invalid("Name is too long")
            trimmed.endsWith(".") -> Validation.Invalid("Name can't end with a dot")
            else -> Validation.Ok
        }
    }

    fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) name.substring(dot + 1) else ""
    }

    /** Keeps the original extension when the user types only a new base name. */
    fun withExtension(newBase: String, originalName: String): String {
        val ext = extension(originalName)
        val trimmed = newBase.trim()
        if (ext.isEmpty()) return trimmed
        return if (trimmed.endsWith(".$ext", ignoreCase = true)) trimmed else "$trimmed.$ext"
    }

    /**
     * Returns [desired] or the first "name (2).ext", "name (3).ext"... not in [existing].
     * Comparison is case-insensitive, like most Android storage.
     */
    fun uniqueName(desired: String, existing: Set<String>): String {
        val lower = existing.mapTo(HashSet()) { it.lowercase() }
        if (desired.lowercase() !in lower) return desired
        val base = baseName(desired).replace(Regex(""" \(\d+\)$"""), "")
        val ext = extension(desired).let { if (it.isEmpty()) "" else ".$it" }
        var n = 2
        while (true) {
            val candidate = "$base ($n)$ext"
            if (candidate.lowercase() !in lower) return candidate
            n++
        }
    }

    /** Name for an edited copy: IMG_1234.jpg -> IMG_1234_edited.jpg (unique). */
    fun editedCopyName(original: String, existing: Set<String>, newExtension: String? = null): String {
        val ext = newExtension ?: extension(original)
        val base = baseName(original).replace(Regex("""_edited(\(\d+\))?$"""), "")
        val desired = if (ext.isEmpty()) "${base}_edited" else "${base}_edited.$ext"
        return uniqueName(desired, existing)
    }

    /** Normalises a MediaStore RELATIVE_PATH so it always ends with exactly one '/'. */
    fun normaliseRelativePath(path: String): String {
        val cleaned = path.replace('\\', '/').trim('/').replace(Regex("/+"), "/")
        return if (cleaned.isEmpty()) "" else "$cleaned/"
    }
}
