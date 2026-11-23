package com.jotrorox.verilogtoolbox

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.PopupMenuListenerAdapter
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.Alarm
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.event.DocumentEvent
import javax.swing.event.PopupMenuEvent
import javax.swing.text.JTextComponent
import kotlin.math.min

/**
 * The device families and parts an installed Quartus Prime supports, as its own Tcl commands list them:
 * that is the only list that matches what the installation can compile for, since each family is an
 * optional download. Asking takes `quartus_sh` a few seconds, so the answers are kept per installation.
 */
object QuartusDevices {
    private const val TIMEOUT_MILLIS = 60_000

    private val families = ConcurrentHashMap<QuartusInstallation, List<String>>()
    private val parts = ConcurrentHashMap<Pair<QuartusInstallation, String>, List<String>>()

    /** Lines that tools print around the result, such as the allocator's `TBBmalloc: ...` remark. */
    private val NOISE = Regex("""^(TBBmalloc\b|\*|(Info|Warning|Critical Warning|Error)\b.*:)""", RegexOption.IGNORE_CASE)

    /**
     * The families of [installation], such as `MAX 10`; empty without one or when Quartus does not answer.
     * Blocks while `quartus_sh` runs, so call it in the background.
     */
    fun families(installation: QuartusInstallation?): List<String> {
        if (installation == null) return emptyList()
        families[installation]?.let { return it }
        // An empty answer is not kept: Quartus may just have failed to start this time.
        return evaluate(installation, "get_family_list").also { if (it.isNotEmpty()) families[installation] = it }
    }

    /** The families already known for [installation], without asking; null when they have not been asked for. */
    fun cachedFamilies(installation: QuartusInstallation?): List<String>? = installation?.let { families[it] }

    /**
     * The part numbers of [family], such as `10M50DAF484C7G`, in the order Quartus lists them; empty when
     * the family is not installed. Blocks while `quartus_sh` runs, so call it in the background.
     */
    fun parts(installation: QuartusInstallation?, family: String): List<String> {
        val name = family.trim()
        if (installation == null || name.isEmpty()) return emptyList()
        val key = installation to name
        parts[key]?.let { return it }
        return evaluate(installation, "get_part_list", "-family", name).also { if (it.isNotEmpty()) parts[key] = it }
    }

    fun cachedParts(installation: QuartusInstallation?, family: String): List<String>? =
        installation?.let { parts[it to family.trim()] }

    /** The Tcl list `quartus_sh --tcl_eval` prints for [command]. */
    private fun evaluate(installation: QuartusInstallation, vararg command: String): List<String> {
        val shell = installation.tool(Quartus.SHELL) ?: return emptyList()
        // The temporary directory: the shell may leave files behind where it runs, and needs no project.
        val commandLine = installation.commandLine(shell, Path.of(FileUtil.getTempDirectory()))
            .withParameters("--tcl_eval", *command)
        val output = try {
            CapturingProcessHandler(commandLine).runProcess(TIMEOUT_MILLIS)
        } catch (_: ExecutionException) {
            return emptyList()
        }
        if (output.isTimeout || output.exitCode != 0) return emptyList()
        return parseList(output.stdout)
    }

    /**
     * The elements of the Tcl list that `quartus_sh --tcl_eval` printed as its result, which is its last
     * line once the remarks of the tools around it are left out: `{MAX 10} {Cyclone V}` or
     * `10M08DAF256A7G 10M08DAF256C7G`.
     */
    internal fun parseList(output: String): List<String> {
        val result = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !NOISE.containsMatchIn(it) }
            .lastOrNull() ?: return emptyList()
        return QuartusScript.commands(result).firstOrNull()?.words.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * [items] that hold every word of [text], ignoring case: `f484 c8` finds `10M50DAF484C8G`. Those that
     * start with the first word come first, so that a typed prefix narrows the list as expected.
     */
    internal fun filter(items: List<String>, text: String): List<String> {
        val words = text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return items
        val matching = items.filter { item -> words.all { item.contains(it, ignoreCase = true) } }
        val (starting, others) = matching.partition { it.startsWith(words.first(), ignoreCase = true) }
        return starting + others
    }

    /**
     * The family of [families] that [text] names, written as Quartus writes it: `max10` gives `MAX 10`.
     * [text] itself, trimmed, when none matches.
     */
    internal fun canonicalFamily(families: List<String>, text: String): String {
        fun key(name: String) = name.filter { it.isLetterOrDigit() }.lowercase()
        val wanted = key(text)
        return families.firstOrNull { wanted.isNotEmpty() && key(it) == wanted } ?: text.trim()
    }
}

/**
 * An editable combo box whose list narrows, as the user types, to the items that hold the typed words,
 * which makes a part among hundreds quick to find. What is typed is the value, whether the list has it
 * or not.
 */
class QuartusFilteringComboBox : ComboBox<String>(CollectionComboBoxModel<String>()) {
    private val listModel: CollectionComboBoxModel<String>
        @Suppress("UNCHECKED_CAST") get() = model as CollectionComboBoxModel<String>

    private var all: List<String> = emptyList()

    /** Set while the list is narrowed for typing, so that opening the popup then does not widen it again. */
    private var narrowing = false

    init {
        // The setters, by name: in a subclass, `isEditable = true` would assign JComboBox's protected field of
        // that name, and the combo box would think itself editable without ever getting an editor.
        setEditable(true)
        setMaximumRowCount(VISIBLE_ROWS)
        textField?.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                if (changesText(e)) narrow()
            }
        })
        // The arrow button shows everything; the list is only narrowed by typing.
        addPopupMenuListener(object : PopupMenuListenerAdapter() {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
                if (!narrowing && listModel.size != all.size) replaceItems(all, text)
            }
        })
    }

    private val textField: JTextComponent? get() = editor?.editorComponent as? JTextComponent

    /** What the user typed or chose. */
    var text: String
        get() = textField?.text ?: editor?.item?.toString().orEmpty()
        set(value) {
            listModel.selectedItem = value
            editor?.item = value
        }

    /** The items to choose from. What is typed stays as it is. */
    var items: List<String>
        get() = all
        set(value) {
            all = value
            if (isPopupVisible) hidePopup()
            replaceItems(value, text)
        }

    private fun changesText(e: KeyEvent): Boolean {
        if (e.keyCode in NAVIGATION_KEYS) return false
        // Shortcuts change the text only when they paste or cut.
        if ((e.isControlDown || e.isMetaDown) && e.keyCode != KeyEvent.VK_V && e.keyCode != KeyEvent.VK_X) return false
        return true
    }

    private fun narrow() {
        val field = textField ?: return
        val typed = field.text
        val caret = field.caretPosition
        val matches = QuartusDevices.filter(all, typed)
        val rows = min(matches.size, maximumRowCount)
        val shownRows = min(listModel.size, maximumRowCount)
        narrowing = true
        try {
            replaceItems(matches, typed)
            field.caretPosition = caret.coerceIn(0, field.text.length)
            when {
                matches.isEmpty() || !isShowing -> hidePopup()
                // A popup that is open keeps its height; it is opened again to fit the new number of rows.
                isPopupVisible && rows != shownRows -> {
                    hidePopup()
                    showPopup()
                }
                !isPopupVisible -> showPopup()
            }
        } finally {
            narrowing = false
        }
    }

    /** Replacing the items has the combo box show its selection in the editor, so the text is selected first. */
    private fun replaceItems(items: List<String>, keep: String) {
        listModel.replaceAll(items)
        listModel.selectedItem = keep
        if (textField?.text != keep) editor?.item = keep
    }

    private companion object {
        const val VISIBLE_ROWS = 15
        val NAVIGATION_KEYS = setOf(
            KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_HOME, KeyEvent.VK_END,
            KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE, KeyEvent.VK_TAB,
            KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META, KeyEvent.VK_ALT_GRAPH,
            KeyEvent.VK_CAPS_LOCK, KeyEvent.VK_CONTEXT_MENU,
        )
    }
}

/**
 * Family and device of a Quartus project, as two rows of a Kotlin UI DSL panel (see [addTo]). The lists
 * come from [installation] and are loaded in the background; without an installation, or while they load,
 * both fields take any text, and so they do for a part the list does not have.
 *
 * [parentDisposable] is the dialog's: results that arrive after it is closed are dropped.
 */
class QuartusDeviceChooser(
    private val installation: QuartusInstallation?,
    family: String?,
    device: String?,
    parentDisposable: Disposable,
) {
    // A list the model can change: the families replace what is here once Quartus has named them.
    val familyField: ComboBox<String> =
        ComboBox(CollectionComboBoxModel(listOfNotNull(family?.takeIf { it.isNotBlank() }).toMutableList()))
    val deviceField: QuartusFilteringComboBox = QuartusFilteringComboBox()

    private lateinit var loadingIcon: JLabel
    private var status: JEditorPane? = null

    @Volatile
    private var disposed = false

    private var families: List<String> = emptyList()
    private var parts: List<String>? = null
    private var partsFamily: String? = null
    private var familiesLoaded = false
    private var pending = 0

    /** Counts the requests for parts, so that only the answer for the family chosen last is shown. */
    private var partsRequest = 0

    /** Waits for typing in the family field to pause before asking for its parts. */
    private val familyTyping = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    init {
        Disposer.register(parentDisposable) { disposed = true }
        familyField.isEditable = true
        familyField.editor.item = family.orEmpty()
        deviceField.text = device.orEmpty()
        familyField.addActionListener { loadParts() }
        (familyField.editor.editorComponent as? JTextComponent)?.document?.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                familyTyping.cancelAllRequests()
                familyTyping.addRequest({ loadParts() }, TYPING_DELAY_MS, ModalityState.any())
            }
        })
    }

    /** The family as Quartus names it, if it is one the installation has; else as typed. */
    val family: String
        get() = QuartusDevices.canonicalFamily(families, familyField.editor.item?.toString().orEmpty())

    /** The part number, upper-cased as Quartus writes it. */
    val device: String get() = deviceField.text.trim().uppercase()

    /** The field to focus first: the device, which is what is chosen most often. */
    val preferredFocusedComponent: JComponent get() = deviceField

    /** Adds the Family and Device rows to [panel], and starts loading what they offer. */
    fun addTo(panel: Panel) {
        with(panel) {
            row("Family:") {
                cell(familyField).columns(COLUMNS_MEDIUM).align(AlignX.FILL)
            }
            row("Device:") {
                val cell = cell(deviceField).columns(COLUMNS_MEDIUM).align(AlignX.FILL).resizableColumn()
                    .comment(if (installation == null) NO_QUARTUS else "Loading devices…")
                status = cell.comment
                loadingIcon = icon(AnimatedIcon.Default()).visible(false).component
            }
        }
        if (installation != null) {
            loadFamilies()
            loadParts()
        }
    }

    /** An error for an empty family or device; a warning for a part the family's list does not have. */
    fun validate(): ValidationInfo? {
        if (family.isEmpty()) return ValidationInfo("Choose the device family.", familyField)
        if (device.isEmpty()) return ValidationInfo("Choose the device.", deviceField)
        val known = parts.orEmpty()
        if (known.isNotEmpty() && partsFamily == family && known.none { it.equals(device, ignoreCase = true) }) {
            return ValidationInfo("$device is not a $family device that ${installation?.displayName} knows.", deviceField)
                .asWarning().withOKEnabled()
        }
        return null
    }

    private fun loadFamilies() {
        QuartusDevices.cachedFamilies(installation)?.let { return showFamilies(it) }
        background({ QuartusDevices.families(installation) }) { showFamilies(it) }
    }

    private fun showFamilies(loaded: List<String>) {
        families = loaded
        familiesLoaded = true
        val typed = familyField.editor.item?.toString().orEmpty()
        val model = familyField.model as CollectionComboBoxModel<String>
        model.replaceAll(loaded)
        // An installation with one family, as Lite often has, needs no choosing.
        val value = if (typed.isBlank() && loaded.isNotEmpty()) loaded.first()
        else QuartusDevices.canonicalFamily(loaded, typed)
        model.selectedItem = value
        familyField.editor.item = value
        loadParts()
        updateStatus()
    }

    private fun loadParts() {
        if (installation == null || disposed) return
        val family = family
        if (family == partsFamily) return
        partsFamily = family
        parts = null
        val request = ++partsRequest
        val cached = QuartusDevices.cachedParts(installation, family)
        when {
            family.isEmpty() -> showParts(emptyList())
            cached != null -> showParts(cached)
            else -> background({ QuartusDevices.parts(installation, family) }) {
                if (request == partsRequest) showParts(it)
            }
        }
        updateStatus()
    }

    private fun showParts(loaded: List<String>) {
        parts = loaded
        deviceField.items = loaded
        updateStatus()
    }

    private fun updateStatus() {
        val loading = pending > 0
        if (::loadingIcon.isInitialized) loadingIcon.isVisible = loading
        val family = partsFamily.orEmpty()
        val loaded = parts
        status?.text = when {
            installation == null -> NO_QUARTUS
            loaded == null -> "Loading devices…"
            family.isEmpty() -> "Choose a family to list its devices."
            loaded.isNotEmpty() ->
                "${loaded.size} $family devices. Type any part of a name to narrow the list, such as F484 or C8."
            familiesLoaded && families.none { it == family } ->
                "$family is not installed in ${installation.displayName}. Type the part number, or choose another family."
            loading -> "Loading devices…"
            else -> "${installation.displayName} listed no devices for $family. Type the part number."
        }
    }

    /** Runs [task] on a pooled thread and hands its result to [then] on the UI thread, unless the dialog is gone. */
    private fun background(task: () -> List<String>, then: (List<String>) -> Unit) {
        pending++
        updateStatus()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching(task).getOrDefault(emptyList())
            // Any modality: the dialog that asked is modal itself, and only its own fields are touched.
            ApplicationManager.getApplication().invokeLater({
                pending--
                if (!disposed) {
                    then(result)
                    updateStatus()
                }
            }, ModalityState.any())
        }
    }

    private companion object {
        const val TYPING_DELAY_MS = 600
        const val NO_QUARTUS =
            "Quartus Prime was not found, so its devices cannot be listed. Type the part number, such as 10M50DAF484C7G."
    }
}

/** Device…: changes the family and device of the active Quartus revision. */
class QuartusChangeDeviceAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && QuartusProjectService.getInstance(project).active != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = QuartusProjectService.getInstance(project)
        // Finding Quartus and reading the settings touch the disk, which the UI thread must not wait for.
        QuartusActions.inBackground(project, { Quartus.find() to service.revision }) { (installation, revision) ->
            if (revision == null) return@inBackground
            val dialog = ChangeDeviceDialog(project, installation, revision)
            if (dialog.showAndGet()) change(project, revision, dialog.chooser.family, dialog.chooser.device)
        }
    }

    private fun change(project: Project, revision: QuartusRevision, family: String, device: String) {
        val service = QuartusProjectService.getInstance(project)
        val changed = service.edit("Change Device") { text ->
            QuartusSettingsText.setGlobal(QuartusSettingsText.setGlobal(text, "FAMILY", family), "DEVICE", device)
        }
        if (changed) {
            QuartusActions.notify(project, "${revision.settingsFile.fileName} now targets $device ($family).")
        } else {
            val message = "${revision.settingsFile.fileName} could not be changed."
            QuartusActions.notify(project, message, NotificationType.ERROR)
        }
    }

    private class ChangeDeviceDialog(project: Project, installation: QuartusInstallation?, revision: QuartusRevision) :
        DialogWrapper(project) {
        val chooser = QuartusDeviceChooser(installation, revision.family, revision.device, disposable)

        init {
            title = "Device of ${revision.name}"
            setOKButtonText("Change")
            init()
        }

        override fun createCenterPanel(): JComponent = panel {
            chooser.addTo(this)
            row {
                comment(
                    "Pin assignments stay as they are. A device in another package may need new pin locations, " +
                        "and the next compilation starts from scratch."
                )
            }
        }

        override fun getPreferredFocusedComponent(): JComponent = chooser.preferredFocusedComponent

        override fun doValidate(): ValidationInfo? = chooser.validate()
    }
}
