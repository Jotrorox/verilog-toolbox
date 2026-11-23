package com.jotrorox.verilogtoolbox

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/** Told when the Quartus project of an IDE project, its settings or its reports have changed. */
fun interface QuartusProjectListener {
    fun quartusProjectChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<QuartusProjectListener> =
            Topic.create("Quartus project changed", QuartusProjectListener::class.java)
    }
}

/**
 * The Quartus project an IDE project works on: the `.qpf` found in it, or the one chosen among several,
 * and the revision that is compiled. Everything Quartus in the IDE refers to this one project, so that
 * compiling, programming and the pin assignments need no setting up.
 */
@Service(Service.Level.PROJECT)
@State(name = "VerilogToolboxQuartus", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class QuartusProjectService(private val project: Project) :
    SerializablePersistentStateComponent<QuartusProjectService.State>(State()), Disposable {

    /** Fields are `var` so that the platform saves them, see [ToolchainSettings.State]. */
    data class State(
        /** The chosen `.qpf` or `.qsf`; empty to use the first one found. */
        @JvmField var projectFile: String = "",
        @JvmField var revision: String = "",
        /** Set when the user took the Quartus project back as the design, so that it is not made that again. */
        @JvmField var designDeclined: Boolean = false,
    )

    @Volatile
    private var found: List<QuartusProject> = emptyList()

    @Volatile
    private var scanned = false

    @Volatile
    private var cachedRevision: CachedRevision? = null

    private class CachedRevision(val revision: QuartusRevision, val stamps: Map<Path, Long>, val documentStamp: Long)

    private val changes = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val relevant = events.mapNotNull { event -> event.path.substringAfterLast('.', "").lowercase().takeIf { it in WATCHED } }
                if (relevant.isEmpty()) return
                // A project file that appeared or went away changes what there is to choose from.
                if (relevant.any { it == "qpf" || it == "qsf" }) scanned = false
                changed()
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (file.extension?.lowercase() in SETTINGS) changed()
            }
        }, this)
    }

    /** The Quartus projects of the IDE project, the nearest to its root first. Scans the first time; not for the UI thread then. */
    val available: List<QuartusProject>
        get() {
            if (!scanned) {
                found = scan(project.basePath?.let { runCatching { Path.of(it) }.getOrNull() })
                scanned = true
            }
            return found
        }

    /** What [available] last found, without looking: safe on the UI thread, empty until the first scan is done. */
    val known: List<QuartusProject> get() = found

    /** The project everything refers to: the chosen one while it exists, else the first one found. */
    val active: QuartusProject?
        get() = chosen() ?: known.firstOrNull()

    private fun chosen(): QuartusProject? =
        state.projectFile.takeIf { it.isNotEmpty() }?.let { runCatching { Path.of(it) }.getOrNull() }
            ?.let { file -> known.firstOrNull { it.file == file } ?: QuartusProject.of(file) }

    /** The name of the revision that is compiled: the chosen one while the project has it, else its first. */
    val activeRevisionName: String?
        get() = active?.let { project -> state.revision.takeIf { it in project.revisions } ?: project.defaultRevision }

    /**
     * The settings of the active revision, with the changes not yet saved in an editor. Read again only
     * when the settings file or a file it names has changed. Null without a project.
     */
    val revision: QuartusRevision?
        get() {
            val active = active ?: return null
            val name = activeRevisionName ?: return null
            val file = active.settingsFile(name)
            val document = LocalFileSystem.getInstance().findFileByNioFile(file)
                ?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
            val documentStamp = document?.modificationStamp ?: -1
            cachedRevision?.let { cached ->
                if (cached.revision.project == active && cached.revision.name == name &&
                    cached.documentStamp == documentStamp && cached.stamps.all { (path, stamp) -> stamp(path) == stamp }
                ) {
                    return cached.revision
                }
            }
            val text = document?.let { ReadAction.nonBlocking(Callable { it.text }).executeSynchronously() }
            val revision = active.revision(name, text)
            val dependencies = listOf(file) + revision.files.filter { it.kind == QuartusFileKind.QIP }.map { it.path }
            cachedRevision = CachedRevision(revision, dependencies.associateWith(::stamp), documentStamp)
            return revision
        }

    private fun stamp(file: Path): Long =
        runCatching { Files.getLastModifiedTime(file).toMillis() xor Files.size(file) }.getOrDefault(-1)

    /** Makes [quartusProject] and its [revision] the ones everything refers to. */
    fun select(quartusProject: QuartusProject, revision: String = quartusProject.defaultRevision) {
        updateState { it.copy(projectFile = quartusProject.file.toString(), revision = revision) }
        if (found.none { it.file == quartusProject.file }) scanned = false
        // A design read from the project follows it to the revision that is compiled now.
        val design = SlangDesign.getInstance(project)
        if (QuartusDesign.isSettingsFile(design.path)) useAsDesign(announce = false)
        changed()
    }

    /** Looks for project files again, for one that was created outside the IDE. */
    fun rescan() {
        scanned = false
        changed()
    }

    /**
     * Makes the active revision the design of the IDE project, which slang-server, Verilator, Yosys and
     * Icarus runs then read their sources from. Returns whether there was a revision to use.
     */
    fun useAsDesign(announce: Boolean = true): Boolean {
        val settings = active?.let { it.settingsFile(activeRevisionName ?: return false) }?.takeIf { it.isRegularFile() }
            ?: return false
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(settings) ?: return false
        updateState { it.copy(designDeclined = false) }
        SlangDesign.getInstance(project).select(file)
        if (announce) {
            NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
                .createNotification(
                    "Design read from ${settings.name}",
                    "slang-server, Verilator lint, Check Synthesis and Icarus Verilog runs use the sources, search " +
                        "paths and macros of the Quartus project.",
                    NotificationType.INFORMATION,
                )
                .addAction(NotificationAction.createSimpleExpiring("Don't Use") { declineDesign() })
                .notify(project)
        }
        return true
    }

    /** Stops reading the design from the Quartus project, and remembers not to start again on its own. */
    fun declineDesign() {
        updateState { it.copy(designDeclined = true) }
        val design = SlangDesign.getInstance(project)
        if (QuartusDesign.isSettingsFile(design.path)) design.clear()
    }

    /**
     * Changes the text of the active revision's settings file with [change], as one undoable command in
     * its document, so that an open editor follows and unsaved edits are kept. Call on the UI thread.
     */
    fun edit(title: String, change: (String) -> String): Boolean {
        val settings = active?.let { it.settingsFile(activeRevisionName ?: return false) } ?: return false
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(settings) ?: return false
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        val before = document.text
        val after = change(before)
        if (after == before) return true
        WriteCommandAction.runWriteCommandAction(project, title, null, {
            // Only what differs is replaced, which leaves carets and folding of an open editor alone.
            val prefix = before.commonPrefixWith(after).length
            val suffix = before.substring(prefix).commonSuffixWith(after.substring(prefix)).length
            document.replaceString(prefix, before.length - suffix, after.substring(prefix, after.length - suffix))
            // Quartus and the generated file list read the file, not the editor.
            FileDocumentManager.getInstance().saveDocument(document)
        })
        changed()
        return true
    }

    /** Tells the listeners, once for changes that come in a burst, such as the files a compilation writes. */
    fun changed() {
        if (project.isDisposed) return
        changes.cancelAllRequests()
        changes.addRequest({
            if (project.isDisposed) return@addRequest
            available
            QuartusDesign.refresh(project)
            QuartusToolWindow.updateAvailability(project)
            project.messageBus.syncPublisher(QuartusProjectListener.TOPIC).quartusProjectChanged()
        }, CHANGE_DELAY_MS)
    }

    /** Makes the IDE see what a run has written into the project, then tells the listeners. */
    fun outputChanged() {
        val directories = listOfNotNull(active?.directory, runCatching { revision?.outputDirectory }.getOrNull()).distinct()
        val files = directories.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
        if (files.isNotEmpty()) VfsUtil.markDirtyAndRefresh(true, true, true, *files.toTypedArray())
        changed()
    }

    override fun dispose() {
    }

    companion object {
        private const val CHANGE_DELAY_MS = 300
        private const val SCAN_DEPTH = 3
        private val SETTINGS = setOf("qsf", "qpf", "qip")

        /** Extensions of the files whose change shows somewhere: settings, reports and programming files. */
        private val WATCHED = SETTINGS + setOf("rpt", "summary", "sof", "pof", "pin", "sdc")

        /** Directories the tools write, which hold no project of the user's. */
        private val SKIPPED_DIRECTORIES =
            setOf("db", "incremental_db", "output_files", "qdb", "dni", "tmp-clearbox", "greybox_tmp", "simulation", "build", "node_modules")

        fun getInstance(project: Project): QuartusProjectService = project.service()

        /** The projects at most [SCAN_DEPTH] directories below [root]: every `.qpf`, and every `.qsf` no `.qpf` names. */
        internal fun scan(root: Path?): List<QuartusProject> {
            if (root == null || !root.isDirectory()) return emptyList()
            val projects = mutableListOf<QuartusProject>()

            fun visit(directory: Path, depth: Int) {
                val entries = runCatching { directory.listDirectoryEntries().sorted() }.getOrDefault(emptyList())
                val inDirectory = entries.filter { it.isRegularFile() && it.extension.lowercase() == "qpf" }
                    .mapNotNull { QuartusProject.of(it) }
                val named = inDirectory.flatMap { it.revisions }.toSet()
                projects += inDirectory
                projects += entries
                    .filter { it.isRegularFile() && it.extension.lowercase() == "qsf" && it.nameWithoutExtension !in named }
                    .mapNotNull { QuartusProject.of(it) }
                if (depth == SCAN_DEPTH) return
                for (entry in entries) {
                    if (entry.isDirectory() && !entry.name.startsWith(".") && entry.name.lowercase() !in SKIPPED_DIRECTORIES) {
                        visit(entry, depth + 1)
                    }
                }
            }
            visit(root, 0)
            return projects.distinctBy { it.file }
        }
    }
}

/**
 * Finds the Quartus project when an IDE project opens and, unless a design is selected already or the
 * user declined it, makes it the design: a project that Quartus compiles is described by its `.qsf`.
 */
class QuartusStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = QuartusProjectService.getInstance(project)
        if (service.available.isEmpty()) return
        val design = SlangDesign.getInstance(project)
        ApplicationManager.getApplication().invokeLater({
            if (design.path.isEmpty() && !service.state.designDeclined) service.useAsDesign()
            service.changed()
        }, project.disposed)
    }
}

/**
 * The design of an IDE project when it is read from a Quartus revision. slang-server and the other tools
 * take a file list, so one is written from the settings file and kept up to date with it.
 */
object QuartusDesign {
    fun isSettingsFile(path: String): Boolean = path.endsWith(".qsf", ignoreCase = true)

    private class Generated(val fileList: Path, val stamps: Map<Path, Long>, val libraries: List<Path>)

    private val generated = java.util.concurrent.ConcurrentHashMap<Path, Generated>()

    /**
     * The file list that describes the revision of [settings], written anew when the settings, a `.qip`
     * they name or the simulation libraries to add have changed. Null when it cannot be written.
     */
    fun fileList(project: Project, settings: Path): Path? {
        val absolute = settings.toAbsolutePath().normalize()
        val libraries = libraries()
        generated[absolute]?.let { known ->
            if (known.libraries == libraries && Files.isRegularFile(known.fileList) &&
                known.stamps.all { (path, stamp) -> stamp(path) == stamp }
            ) {
                return known.fileList
            }
        }
        val quartusProject = QuartusProject.of(absolute) ?: return null
        val revision = quartusProject.revision(absolute.nameWithoutExtension)
        val target = directory(project).resolve(absolute.nameWithoutExtension + ".f")
        val text = "# The design of ${absolute.name}, written by Verilog Toolbox for slang-server and the other tools.\n" +
            "# It is written again whenever the Quartus settings change: edit those instead.\n" +
            YosysSynthesis.commandFile(revision.fileList(libraries), revision.topLevelEntity)
        try {
            Files.createDirectories(target.parent)
            if (runCatching { Files.readString(target) }.getOrNull() != text) Files.writeString(target, text)
        } catch (_: java.io.IOException) {
            return null
        }
        val dependencies = listOf(absolute) + revision.files.filter { it.kind == QuartusFileKind.QIP }.map { it.path }
        generated[absolute] = Generated(target, dependencies.associateWith(::stamp), libraries)
        return target
    }

    /** Writes the file list of the selected design again if it is out of date, and has slang-server read it. */
    fun refresh(project: Project) {
        val design = SlangDesign.getInstance(project)
        if (!isSettingsFile(design.path)) return
        val settings = runCatching { Path.of(design.path) }.getOrNull() ?: return
        val before = generated[settings.toAbsolutePath().normalize()]
        val text = before?.fileList?.let { runCatching { Files.readString(it) }.getOrNull() }
        val fileList = fileList(project, settings) ?: return
        if (before == null || runCatching { Files.readString(fileList) }.getOrNull() != text) design.sendToServer()
    }

    private fun libraries(): List<Path> =
        if (ToolchainSettings.getInstance().quartusSimulationLibraries) Quartus.find()?.simulationLibraries.orEmpty() else emptyList()

    private fun directory(project: Project): Path =
        (project.basePath?.let { Path.of(it).resolve("build") }
            ?: Path.of(com.intellij.openapi.util.io.FileUtil.getTempDirectory(), "verilog-toolbox", project.locationHash))
            .resolve("quartus")

    private fun stamp(file: Path): Long =
        runCatching { Files.getLastModifiedTime(file).toMillis() xor Files.size(file) }.getOrDefault(-1)
}
