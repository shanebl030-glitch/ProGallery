package com.shaneblackburn.progallery.core.video

import com.shaneblackburn.progallery.core.json.Json
import com.shaneblackburn.progallery.core.json.JsonObj

/** A saved video project: the edit plus the information shown in the Projects list. */
data class ProjectDoc(
    val id: String,
    val name: String,
    val createdMs: Long,
    val modifiedMs: Long,
    val project: VideoProject,
    /** Folder new exports go to (relative path, e.g. "Movies/ProGallery/"). */
    val exportFolder: String = "Movies/ProGallery/",
) {
    val clipCount: Int get() = project.clips.size
    val durationMs: Long get() = project.durationMs
    /** First clip, used as the cover in the Projects list. */
    val cover: Clip? get() = project.clips.firstOrNull()
}

/**
 * Converts projects to and from JSON.
 *
 * Rules that keep old saves loading as the format grows:
 * - every field has a default, so missing fields (older files) decode to defaults;
 * - unknown fields (newer files) are ignored;
 * - enums are stored by name and unknown names fall back to a safe default;
 * - [FORMAT_VERSION] is written for diagnostics and future migrations.
 */
object ProjectCodec {
    const val FORMAT_VERSION = 1

    class UnsupportedVersionException(v: Int) : Exception("Project format $v is newer than this app supports")

    fun encode(doc: ProjectDoc): String = Json.write(
        linkedMapOf(
            "format" to "progallery.video-project",
            "version" to FORMAT_VERSION,
            "id" to doc.id,
            "name" to doc.name,
            "created" to doc.createdMs,
            "modified" to doc.modifiedMs,
            "exportFolder" to doc.exportFolder,
            "project" to project(doc.project),
        ),
    )

    fun decode(text: String): ProjectDoc {
        val o = JsonObj.parse(text)
        val v = o.int("version", 1)
        // Fields are additive; only a deliberate major bump (>= 100) is refused.
        if (v >= 100) throw UnsupportedVersionException(v)
        return ProjectDoc(
            id = o.str("id"),
            name = o.str("name", "Untitled video"),
            createdMs = o.long("created"),
            modifiedMs = o.long("modified"),
            exportFolder = o.str("exportFolder", "Movies/ProGallery/"),
            project = o.obj("project")?.let(::project) ?: VideoProject(),
        )
    }

    // ------------------------------------------------------------------ encode

    private fun project(p: VideoProject): Map<String, Any?> = linkedMapOf(
        "clips" to p.clips.map(::clip),
        "transitions" to p.transitions.map {
            mapOf("type" to it.type.name, "ms" to it.durationMs, "dir" to it.direction.name, "intensity" to it.intensity)
        },
        "texts" to p.texts.map(::text),
        "music" to p.music?.let(::music),
        "fadeIn" to p.fadeIn?.let { mapOf("ms" to it.durationMs, "white" to it.toWhite) },
        "fadeOut" to p.fadeOut?.let { mapOf("ms" to it.durationMs, "white" to it.toWhite) },
        "aspect" to p.aspect.name,
    )

    private fun clip(c: Clip): Map<String, Any?> = linkedMapOf(
        "id" to c.id, "uri" to c.uri, "name" to c.name, "duration" to c.sourceDurationMs,
        "width" to c.width, "height" to c.height, "rotation" to c.sourceRotation, "hasAudio" to c.hasAudio,
        "trimStart" to c.trimStartMs, "trimEnd" to c.trimEndMs, "speed" to c.speed, "volume" to c.volume,
        "muted" to c.muted, "quarterTurns" to c.quarterTurns, "flipH" to c.flipH, "flipV" to c.flipV,
        "color" to color(c.color), "filter" to c.filter.name, "filterIntensity" to c.filterIntensity,
        "fps" to c.frameRate, "image" to c.isImage, "mime" to c.mimeType,
    )

    private fun color(c: VideoColor): Map<String, Any?> = linkedMapOf(
        "brightness" to c.brightness, "contrast" to c.contrast, "saturation" to c.saturation,
        "temperature" to c.temperature, "exposure" to c.exposure, "highlights" to c.highlights,
        "shadows" to c.shadows, "vibrance" to c.vibrance, "tint" to c.tint, "sharpness" to c.sharpness,
    )

    private fun text(t: TimedText): Map<String, Any?> = linkedMapOf(
        "id" to t.id, "text" to t.text, "start" to t.startMs, "end" to t.endMs, "x" to t.x, "y" to t.y,
        "size" to t.size, "color" to t.color, "background" to t.background, "animation" to t.animation.name,
    )

    private fun music(m: MusicTrack): Map<String, Any?> = linkedMapOf(
        "uri" to m.uri, "name" to m.name, "duration" to m.durationMs, "offset" to m.startOffsetMs,
        "volume" to m.volume, "fadeIn" to m.fadeInMs, "fadeOut" to m.fadeOutMs, "loop" to m.loop,
    )

    // ------------------------------------------------------------------ decode

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private fun project(o: JsonObj): VideoProject {
        val clips = o.objs("clips").mapNotNull(::clipOrNull)
        return VideoProject(
            clips = clips,
            transitions = o.objs("transitions").map {
                Transition(
                    type = enumOr(it.strOrNull("type"), TransitionType.NONE),
                    durationMs = it.long("ms", 0L).coerceAtLeast(0L),
                    direction = enumOr(it.strOrNull("dir"), TransitionDirection.AUTO),
                    intensity = it.float("intensity", 0.6f).coerceIn(0f, 1f),
                )
            },
            texts = o.objs("texts").map(::text),
            music = o.obj("music")?.let(::music)?.takeIf { it.uri.isNotEmpty() && it.durationMs > 0 },
            fadeIn = o.obj("fadeIn")?.let { Fade(it.long("ms", 0L).coerceAtLeast(0L), it.bool("white")) },
            fadeOut = o.obj("fadeOut")?.let { Fade(it.long("ms", 0L).coerceAtLeast(0L), it.bool("white")) },
            aspect = enumOr(o.strOrNull("aspect"), AspectMode.ORIGINAL),
        )
    }

    /** Returns null for a clip that can't be used at all (no source or no duration). */
    private fun clipOrNull(o: JsonObj): Clip? {
        val uri = o.str("uri")
        val dur = o.long("duration")
        if (uri.isEmpty() || dur <= 0) return null
        val base = Clip(
            id = o.long("id", System.nanoTime()),
            uri = uri,
            name = o.str("name", "Clip"),
            sourceDurationMs = dur,
            width = o.int("width", 1920).coerceAtLeast(2),
            height = o.int("height", 1080).coerceAtLeast(2),
            sourceRotation = o.int("rotation", 0),
            hasAudio = o.bool("hasAudio", true),
            speed = o.float("speed", 1f).coerceIn(0.25f, 4f),
            volume = o.float("volume", 1f).coerceIn(0f, 2f),
            muted = o.bool("muted"),
            quarterTurns = o.int("quarterTurns").mod(4),
            flipH = o.bool("flipH"),
            flipV = o.bool("flipV"),
            color = o.obj("color")?.let(::color) ?: VideoColor(),
            filter = enumOr(o.strOrNull("filter"), VideoFilter.NONE),
            filterIntensity = o.float("filterIntensity", 1f).coerceIn(0f, 1f),
            frameRate = o.int("fps", 30).coerceIn(1, 480),
            isImage = o.bool("image"),
            mimeType = o.strOrNull("mime"),
        )
        // Re-validate trims against the source so a hand-edited or damaged file can't break export.
        return if (base.isImage) base.withImageDuration(dur)
        else base.withTrim(o.long("trimStart", 0L), o.long("trimEnd", dur))
    }

    private fun color(o: JsonObj): VideoColor {
        fun f(k: String, lo: Float = -1f) = o.float(k).coerceIn(lo, 1f)
        return VideoColor(
            brightness = f("brightness"), contrast = f("contrast"), saturation = f("saturation"),
            temperature = f("temperature"), exposure = f("exposure"), highlights = f("highlights"),
            shadows = f("shadows"), vibrance = f("vibrance"), tint = f("tint"), sharpness = f("sharpness", 0f),
        )
    }

    private fun text(o: JsonObj) = TimedText(
        id = o.long("id", System.nanoTime()),
        text = o.str("text"),
        startMs = o.long("start").coerceAtLeast(0L),
        endMs = o.long("end").coerceAtLeast(o.long("start").coerceAtLeast(0L)),
        x = o.float("x", 0.5f).coerceIn(0f, 1f),
        y = o.float("y", 0.85f).coerceIn(0f, 1f),
        size = o.float("size", 0.06f).coerceIn(0.01f, 0.5f),
        color = o.long("color", 0xFFFFFFFFL).toInt(),
        background = o.bool("background", true),
        animation = enumOr(o.strOrNull("animation"), TextAnimation.FADE),
    )

    private fun music(o: JsonObj) = MusicTrack(
        uri = o.str("uri"),
        name = o.str("name", "Music"),
        durationMs = o.long("duration"),
        startOffsetMs = o.long("offset").coerceAtLeast(0L),
        volume = o.float("volume", 0.8f).coerceIn(0f, 2f),
        fadeInMs = o.long("fadeIn", 1000L).coerceAtLeast(0L),
        fadeOutMs = o.long("fadeOut", 1500L).coerceAtLeast(0L),
        loop = o.bool("loop", true),
    )
}
