package com.shaneblackburn.progallery.video

import android.content.Context
import android.util.Log
import com.shaneblackburn.progallery.core.video.ProjectCodec
import com.shaneblackburn.progallery.core.video.ProjectDoc
import com.shaneblackburn.progallery.core.video.VideoProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Saved video projects, one JSON file per project in app-private storage
 * (`files/projects/<id>.json`). Projects only reference media by content URI; no media is
 * copied and originals are never touched.
 *
 * Writes are atomic (temp file + rename), so a crash mid-save leaves the previous version.
 */
class ProjectStore(context: Context) {
    private val dir = File(context.filesDir, "projects").apply { mkdirs() }
    private val mutex = Mutex()

    private val _projects = MutableStateFlow<List<ProjectDoc>>(emptyList())
    /** All saved projects, most recently modified first. */
    val projects: StateFlow<List<ProjectDoc>> = _projects.asStateFlow()

    /** Number of project files that couldn't be read on the last refresh (shown to the user). */
    private val _unreadable = MutableStateFlow(0)
    val unreadable: StateFlow<Int> = _unreadable.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            var bad = 0
            val docs = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
                runCatching { ProjectCodec.decode(f.readText()) }
                    .onFailure { Log.w(TAG, "unreadable project ${f.name}", it); bad++ }
                    .getOrNull()
            }
            _unreadable.value = bad
            _projects.value = docs.sortedByDescending { it.modifiedMs }
        }
    }

    suspend fun load(id: String): ProjectDoc? = withContext(Dispatchers.IO) {
        mutex.withLock { fileFor(id).takeIf { it.exists() }?.let { runCatching { ProjectCodec.decode(it.readText()) }.getOrNull() } }
    }

    /** Creates a new project (not yet written until [save]). */
    fun newDoc(project: VideoProject, name: String, exportFolder: String): ProjectDoc {
        val now = System.currentTimeMillis()
        return ProjectDoc(UUID.randomUUID().toString(), name, now, now, project, exportFolder)
    }

    suspend fun save(doc: ProjectDoc): ProjectDoc = withContext(Dispatchers.IO) {
        val stamped = doc.copy(modifiedMs = System.currentTimeMillis())
        mutex.withLock {
            val target = fileFor(stamped.id)
            val tmp = File(dir, "${stamped.id}.json.tmp")
            tmp.writeText(ProjectCodec.encode(stamped))
            if (!tmp.renameTo(target)) {
                // renameTo can fail over an existing file on some filesystems; fall back safely.
                target.delete()
                if (!tmp.renameTo(target)) throw java.io.IOException("Couldn't save project")
            }
            _projects.value = (_projects.value.filterNot { it.id == stamped.id } + stamped).sortedByDescending { it.modifiedMs }
        }
        stamped
    }

    suspend fun rename(id: String, name: String) {
        load(id)?.let { save(it.copy(name = name.trim().ifEmpty { it.name })) }
    }

    suspend fun duplicate(id: String): ProjectDoc? {
        val src = load(id) ?: return null
        return save(newDoc(src.project, "${src.name} (copy)", src.exportFolder))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            fileFor(id).delete()
            _projects.value = _projects.value.filterNot { it.id == id }
        }
    }

    private fun fileFor(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "bad project id" }
        return File(dir, "$id.json")
    }

    private companion object { const val TAG = "ProjectStore" }
}
