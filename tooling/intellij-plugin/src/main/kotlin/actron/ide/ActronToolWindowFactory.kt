package actron.ide

import com.intellij.find.FindManager
import com.intellij.json.psi.*
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiManager
import com.intellij.ui.content.ContentFactory
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

private sealed interface ExportSelection {
    data object AwaitingExport : ExportSelection

    data class Opened(
        val file: JsonFile,
    ) : ExportSelection
}

class ActronToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val container = JPanel(BorderLayout())
        val status = JLabel("Open a model.actron.json export, then Refresh.")
        val diagram = DiagramPanel()
        val rows = DefaultListModel<String>()
        val list: JList<String> = JList(rows)
        var selection: ExportSelection = ExportSelection.AwaitingExport
        var keys = emptyList<String>()

        fun navigate(key: String) {
            ReadAction.run<RuntimeException> {
                val opened = selection as? ExportSelection.Opened ?: return@run
                if (!ActronModel.source(project, opened.file, key) { it.navigate(true) }) {
                    for (declaration in ActronModel.declarations(opened.file, key)) {
                        OpenFileDescriptor(project, declaration.containingFile.virtualFile, declaration.textOffset).navigate(true)
                        break
                    }
                }
            }
        }
        val refresh =
            JButton("Refresh").apply {
                addActionListener { _: java.awt.event.ActionEvent ->
                    ReadAction.run<RuntimeException> {
                        selection = ExportSelection.AwaitingExport
                        status.text = "Select a format-1 .actron.json export."
                        diagram.load(emptyList(), emptyList(), emptySet(), ::navigate)
                        rows.clear()
                        keys = emptyList()
                        val virtual =
                            FileEditorManager
                                .getInstance(project)
                                .selectedFiles
                                .filterNotNull()
                                .firstOrNull { it.name.endsWith(".actron.json") } ?: return@run
                        val file = PsiManager.getInstance(project).findFile(virtual) as? JsonFile ?: return@run
                        ActronModel.withModel(file) { model ->
                            selection = ExportSelection.Opened(file)
                            val nodes =
                                buildList<String> {
                                    for (entry in ActronModel.objects(model, "states")) ActronModel.text(entry, "id") { add(it) }
                                }
                            val edges =
                                buildList<DiagramEdge> {
                                    for (entry in ActronModel.objects(model, "transitions")) {
                                        ActronModel.text(entry, "id") { id ->
                                            ActronModel.text(entry, "source") { source ->
                                                ActronModel.text(entry, "target") { target ->
                                                    var label = ""
                                                    ActronModel.text(entry, "trigger") { label = it }
                                                    add(DiagramEdge(id, source, target, label))
                                                }
                                            }
                                        }
                                    }
                                }
                            val active =
                                buildSet<String> {
                                    ActronModel.withRoot(file) { root ->
                                        val array = root.findProperty("active")?.value as? JsonArray ?: return@withRoot
                                        addAll(array.valueList.filterIsInstance<JsonStringLiteral>().map { it.value })
                                    }
                                }
                            diagram.load(nodes, edges, active, ::navigate)
                            rows.clear()
                            edges.forEach { rows.addElement("${it.id}: ${it.source} —${it.label}→ ${it.target}") }
                            keys = edges.map { it.id }
                            status.text = "${nodes.size} states, ${edges.size} transitions; double-click to navigate."
                        }
                    }
                }
            }
        val usages =
            JButton("Find usages").apply {
                addActionListener { _: java.awt.event.ActionEvent ->
                    val key = keys.getOrNull(list.selectedIndex) ?: return@addActionListener
                    val opened = selection as? ExportSelection.Opened ?: return@addActionListener
                    for (declaration in ActronModel.declarations(opened.file, key)) {
                        FindManager.getInstance(project).findUsages(declaration)
                        break
                    }
                }
            }
        list.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) keys.getOrNull(list.selectedIndex)?.let(::navigate)
                }
            },
        )
        val controls =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(refresh)
                add(usages)
                add(status)
            }
        container.add(controls, BorderLayout.NORTH)
        container.add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(diagram), JScrollPane(list)).apply { resizeWeight = 0.8 }, BorderLayout.CENTER)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(container, "Behaviour", false))
    }
}

internal data class DiagramEdge(
    val id: String,
    val source: String,
    val target: String,
    val label: String,
)

/** Native drawing only: model labels are text, no HTML/JavaScript or external renderer is executed. */
internal class DiagramPanel : JPanel() {
    private var nodes = emptyList<String>()
    private var edges = emptyList<DiagramEdge>()
    private var active = emptySet<String>()
    private var onNavigate: (String) -> Unit = {}

    private fun bounds(index: Int) = Rectangle(30 + (index % 4) * 220, 40 + (index / 4) * 140, 160, 50)

    init {
        background = Color.WHITE
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) nodes.indexOfFirst { bounds(nodes.indexOf(it)).contains(e.point) }.takeIf { it >= 0 }?.let { onNavigate("state:${nodes[it]}") }
                }
            },
        )
    }

    fun load(
        nodes: List<String>,
        edges: List<DiagramEdge>,
        active: Set<String>,
        navigate: (String) -> Unit,
    ) {
        this.nodes = nodes.take(200)
        this.edges = edges
        this.active = active
        this.onNavigate = navigate
        preferredSize = Dimension(920, maxOf(200, ((this.nodes.size + 3) / 4) * 140 + 80))
        revalidate()
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val graphics = g as Graphics2D
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        for (edge in edges) {
            val from = nodes.indexOf(edge.source)
            val to = nodes.indexOf(edge.target)
            if (from < 0 || to < 0) continue
            val a = bounds(from)
            val b = bounds(to)
            graphics.color = Color.GRAY
            graphics.drawLine(a.x + a.width / 2, a.y + a.height, b.x + b.width / 2, b.y)
            graphics.fillPolygon(intArrayOf(b.x + b.width / 2 - 4, b.x + b.width / 2 + 4, b.x + b.width / 2), intArrayOf(b.y - 8, b.y - 8, b.y), 3)
        }
        nodes.forEachIndexed { index, label ->
            val box = bounds(index)
            graphics.color = if (label in active) Color(201, 236, 212) else Color(235, 242, 250)
            graphics.fillRoundRect(box.x, box.y, box.width, box.height, 12, 12)
            graphics.color = Color.DARK_GRAY
            graphics.drawRoundRect(box.x, box.y, box.width, box.height, 12, 12)
            graphics.drawString(label.take(22), box.x + 8, box.y + 29)
        }
        if (nodes.size == 200) graphics.drawString("Canvas shows the first 200 nodes; the transition list remains complete.", 20, 20)
    }
}
