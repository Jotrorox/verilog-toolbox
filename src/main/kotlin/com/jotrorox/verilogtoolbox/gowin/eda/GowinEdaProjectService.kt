package com.jotrorox.verilogtoolbox.gowin.eda

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
import com.jotrorox.verilogtoolbox.lsp.SlangDesign
import com.jotrorox.verilogtoolbox.yosys.YosysSynthesis
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/** Told when the GOWIN EDA project of an IDE project, its files or its reports have changed. */
fun interface GowinEdaProjectListener {
    fun gowinProjectChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<GowinEdaProjectListener> = Topic.create("GOWIN EDA project changed", GowinEdaProjectListener::class.java)
    }
}

/**
 * The GOWIN EDA project an IDE project works on: the `.gprj` found in it, or the one chosen among several.
 * Everything GOWIN EDA in the IDE refers to this one project, so that building, programming and the pins
 * need no setting up.
 */
@Service(Service.Level.PROJECT)
@State(name = "VerilogToolboxGowinEda", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class GowinEdaProjectService(private val project: Project) :
    SerializablePersistentStateComponent<GowinEdaProjectService.State>(State()), Disposable {

    /** Fields are `var` so that the platform saves them, see [com.jotrorox.verilogtoolbox.settings.ToolchainSettings.State]. */
    data class State(
        /** The chosen `.gprj`; empty to use the first one found. */
        @JvmField var projectFile: String = "",
        /** Set when the user took the GOWIN EDA project back as the design, so that it is not made that again. */
        @JvmField var designDeclined: Boolean = false,
    )

    @Volatile
    private var found: List<Path> = emptyList()

    @Volatile
    private var scanned = false

    @Volatile
    private var cached: Cached? = null

    private class Cached(val project: GowinEdaProject, val stamps: Map<Path, Long>, val documentStamp: Long)

    private val changes = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val relevant = events.filter { event -> event.path.substringAfterLast('.', "").lowercase() in EDITED || isOutput(event.path) }
                if (relevant.isEmpty()) return
                // A project file that appeared or went away changes what there is to choose from.
                if (relevant.any { it.path.endsWith(".${GowinEdaProject.EXTENSION}", ignoreCase = true) }) scanned = false
                changed()
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (file.extension?.lowercase() in EDITED) changed()
            }
        }, this)
    }

    /** The `.gprj` files of the IDE project, the nearest to its root first. Scans the first time; not for the UI thread then. */
    val available: List<Path>
        get() {
            if (!scanned) {
                found = scan(project.basePath?.let { runCatching { Path.of(it) }.getOrNull() })
                scanned = true
            }
            return found
        }

    /** What [available] last found, without looking: safe on the UI thread, empty until the first scan is done. */
    val known: List<Path> get() = found

    /** The `.gprj` everything refers to: the chosen one while it exists, else the first one found. */
    val active: Path?
        get() = state.projectFile.takeIf { it.isNotEmpty() }?.let { runCatching { Path.of(it) }.getOrNull() }
            ?.takeIf { it.isRegularFile() } ?: known.firstOrNull()

    /**
     * The active project, with the changes not yet saved in an editor of its `.gprj`. Read again only when
     * that or its options have changed. Null without a project.
     */
    val gowinProject: GowinEdaProject?
        get() {
            val file = active ?: return null
            val document = LocalFileSystem.getInstance().findFileByNioFile(file)
                ?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
            val documentStamp = document?.modificationStamp ?: -1
            val dependencies = listOf(file, file.resolveSibling(GowinEdaProject.IMPL).resolve(GowinEdaProject.OPTIONS_FILE))
            cached?.let { known ->
                if (known.project.file == file && known.documentStamp == documentStamp &&
                    known.stamps.all { (path, stamp) -> stamp(path) == stamp } && known.stamps.keys == dependencies.toSet()
                ) {
                    return known.project
                }
            }
            val text = document?.let { ReadAction.nonBlocking(Callable { it.text }).executeSynchronously() }
            val parsed = GowinEdaProject.of(file, text) ?: return null
            cached = Cached(parsed, dependencies.associateWith(::stamp), documentStamp)
            return parsed
        }

    /** Makes [file] the project everything refers to. */
    fun select(file: Path) {
        updateState { it.copy(projectFile = file.toString()) }
        if (found.none { it == file }) scanned = false
        // A design read from the project follows it.
        if (GowinEdaDesign.isProjectFile(SlangDesign.getInstance(project).path)) useAsDesign(announce = false)
        changed()
    }

    /** Looks for project files again, for one that was created outside the IDE. */
    fun rescan() {
        scanned = false
        changed()
    }

    /**
     * Makes the active project the design of the IDE project, which slang-server, Verilator, Yosys and Icarus
     * runs then read their sources from. Returns whether there was a project to use.
     */
    fun useAsDesign(announce: Boolean = true): Boolean {
        val file = active?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) } ?: return false
        updateState { it.copy(designDeclined = false) }
        SlangDesign.getInstance(project).select(file)
        if (announce) {
            NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
                .createNotification(
                    "Design read from ${file.name}",
                    "slang-server, Verilator lint, Check Synthesis and Icarus Verilog runs use the sources and the top module of the GOWIN EDA project.",
                    NotificationType.INFORMATION,
                )
                .addAction(NotificationAction.createSimpleExpiring("Don't Use") { declineDesign() })
                .notify(project)
        }
        return true
    }

    /** Stops reading the design from the GOWIN EDA project, and remembers not to start again on its own. */
    fun declineDesign() {
        updateState { it.copy(designDeclined = true) }
        val design = SlangDesign.getInstance(project)
        if (GowinEdaDesign.isProjectFile(design.path)) design.clear()
    }

    /** Changes the text of the active `.gprj` with [change], as one undoable command. Call on the UI thread. */
    fun edit(title: String, change: (String) -> String): Boolean {
        val file = active ?: return false
        return editFile(file, title, create = false) { change(it ?: return@editFile null) }
    }

    /** Changes the options of the active project, creating the options file if it has none yet. Call on the UI thread. */
    fun editOptions(title: String, change: (String?) -> String): Boolean {
        val file = active ?: return false
        return editFile(file.resolveSibling(GowinEdaProject.IMPL).resolve(GowinEdaProject.OPTIONS_FILE), title, create = true, change)
    }

    /**
     * Changes the text of [path] with [change], which gets null for a file that does not exist yet and returns
     * null to leave it alone. The change goes through the file's document, as one undoable command, so that an
     * open editor follows and unsaved edits are kept, and is saved, because the tools read the file. Call on the UI thread.
     */
    fun editFile(path: Path, title: String, create: Boolean, change: (String?) -> String?): Boolean {
        val existing = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        if (existing == null) {
            if (!create) return false
            val text = change(null) ?: return true
            return try {
                WriteCommandAction.writeCommandAction(project).withName(title).run<IOException> {
                    val directory = VfsUtil.createDirectories(path.parent.toString())
                    val file = directory.findChild(path.name) ?: directory.createChildData(this, path.name)
                    VfsUtil.saveText(file, text)
                }
                changed()
                true
            } catch (_: IOException) {
                false
            }
        }
        val document = FileDocumentManager.getInstance().getDocument(existing) ?: return false
        val before = document.text
        val after = change(before) ?: return true
        if (after == before) return true
        WriteCommandAction.runWriteCommandAction(project, title, null, {
            // Only what differs is replaced, which leaves carets and folding of an open editor alone.
            val prefix = before.commonPrefixWith(after).length
            val suffix = before.substring(prefix).commonSuffixWith(after.substring(prefix)).length
            document.replaceString(prefix, before.length - suffix, after.substring(prefix, after.length - suffix))
            FileDocumentManager.getInstance().saveDocument(document)
        })
        changed()
        return true
    }

    /** Tells the listeners, once for changes that come in a burst, such as the files a run writes. */
    fun changed() {
        if (project.isDisposed) return
        changes.cancelAllRequests()
        changes.addRequest({
            if (project.isDisposed) return@addRequest
            available
            GowinEdaDesign.refresh(project)
            GowinEdaToolWindow.updateAvailability(project)
            project.messageBus.syncPublisher(GowinEdaProjectListener.TOPIC).gowinProjectChanged()
        }, CHANGE_DELAY_MS)
    }

    /** Makes the IDE see what a run has written into the project, then tells the listeners. */
    fun outputChanged() {
        val directory = active?.resolveSibling(GowinEdaProject.IMPL)
        val file = directory?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
        if (file != null) VfsUtil.markDirtyAndRefresh(true, true, true, file)
        changed()
    }

    private fun isOutput(path: String): Boolean = path.replace('\\', '/').contains("/${GowinEdaProject.IMPL}/")

    override fun dispose() {
    }

    companion object {
        private const val CHANGE_DELAY_MS = 300
        private const val SCAN_DEPTH = 3

        /** Files edited in the IDE whose change shows somewhere: the project and its constraints. Its options and reports are in `impl`. */
        private val EDITED = setOf(GowinEdaProject.EXTENSION, "cst", "sdc")

        /** Directories the tools write, which hold no project of the user's. */
        private val SKIPPED_DIRECTORIES = setOf(GowinEdaProject.IMPL, "build", "out", "node_modules", "simulation")

        fun getInstance(project: Project): GowinEdaProjectService = project.service()

        private fun stamp(file: Path): Long =
            runCatching { Files.getLastModifiedTime(file).toMillis() xor Files.size(file) }.getOrDefault(-1)

        /** The `.gprj` files at most [SCAN_DEPTH] directories below [root], those nearest to it first. */
        internal fun scan(root: Path?): List<Path> {
            if (root == null || !root.isDirectory()) return emptyList()
            val projects = mutableListOf<Path>()

            fun visit(directory: Path, depth: Int) {
                val entries = runCatching { directory.listDirectoryEntries().sorted() }.getOrDefault(emptyList())
                projects += entries.filter { it.isRegularFile() && it.extension.lowercase() == GowinEdaProject.EXTENSION }
                    .map { it.toAbsolutePath().normalize() }
                if (depth == SCAN_DEPTH) return
                for (entry in entries) {
                    if (entry.isDirectory() && !entry.name.startsWith(".") && entry.name.lowercase() !in SKIPPED_DIRECTORIES) {
                        visit(entry, depth + 1)
                    }
                }
            }
            visit(root, 0)
            return projects
        }
    }
}

/**
 * Finds the GOWIN EDA project when an IDE project opens and, unless a design is selected already or the user
 * declined it, makes it the design.
 */
class GowinEdaStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = GowinEdaProjectService.getInstance(project)
        if (service.available.isEmpty()) return
        val design = SlangDesign.getInstance(project)
        ApplicationManager.getApplication().invokeLater({
            if (design.path.isEmpty() && !service.state.designDeclined) service.useAsDesign()
            service.changed()
        }, project.disposed)
    }
}

/**
 * The design of an IDE project when it is read from a GOWIN EDA project. slang-server and the other tools take a
 * file list, so one is written from the `.gprj` and kept up to date with it.
 */
object GowinEdaDesign {
    fun isProjectFile(path: String): Boolean = path.endsWith(".${GowinEdaProject.EXTENSION}", ignoreCase = true)

    private class Generated(val fileList: Path, val stamps: Map<Path, Long>)

    private val generated = ConcurrentHashMap<Path, Generated>()

    /** The file list that describes the project of [projectFile], written anew when it has changed. Null when it cannot be written. */
    fun fileList(project: Project, projectFile: Path): Path? {
        val absolute = projectFile.toAbsolutePath().normalize()
        val gowinProject = GowinEdaProject.of(absolute) ?: return null
        // The top module may be worked out from the sources and constraints, so they count too.
        val dependencies = listOf(absolute, gowinProject.optionsFile) + gowinProject.verilogSources + listOfNotNull(gowinProject.constraintFile)
        generated[absolute]?.let { known ->
            if (Files.isRegularFile(known.fileList) && known.stamps.keys == dependencies.toSet() &&
                known.stamps.all { (path, stamp) -> stamp(path) == stamp }
            ) {
                return known.fileList
            }
        }
        val target = directory(project).resolve(absolute.nameWithoutExtension + ".f")
        val text = "# The design of ${absolute.name}, written by Verilog Toolbox for slang-server and the other tools.\n" +
            "# It is written again whenever the GOWIN EDA project changes: edit that instead.\n" +
            // Without a module to start from, slang-server elaborates every module nobody instantiates.
            YosysSynthesis.commandFile(gowinProject.fileList(), gowinProject.topModule ?: "").removeSuffix("--top \n")
        try {
            Files.createDirectories(target.parent)
            if (runCatching { Files.readString(target) }.getOrNull() != text) Files.writeString(target, text)
        } catch (_: IOException) {
            return null
        }
        generated[absolute] = Generated(target, dependencies.associateWith(::stamp))
        return target
    }

    /** Writes the file list of the selected design again if it is out of date, and has slang-server read it. */
    fun refresh(project: Project) {
        val design = SlangDesign.getInstance(project)
        if (!isProjectFile(design.path)) return
        val file = runCatching { Path.of(design.path) }.getOrNull() ?: return
        val before = generated[file.toAbsolutePath().normalize()]
        val text = before?.fileList?.let { runCatching { Files.readString(it) }.getOrNull() }
        val fileList = fileList(project, file) ?: return
        if (before == null || runCatching { Files.readString(fileList) }.getOrNull() != text) design.sendToServer()
    }

    private fun directory(project: Project): Path =
        (project.basePath?.let { Path.of(it).resolve("build") }
            ?: Path.of(com.intellij.openapi.util.io.FileUtil.getTempDirectory(), "verilog-toolbox", project.locationHash))
            .resolve("gowin-eda")

    private fun stamp(file: Path): Long =
        runCatching { Files.getLastModifiedTime(file).toMillis() xor Files.size(file) }.getOrDefault(-1)
}
