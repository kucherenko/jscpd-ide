package dev.jscpd.ide.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import dev.jscpd.ide.lsp.JscpdServer
import dev.jscpd.ide.lsp.Uris
import dev.jscpd.ide.migration.CompareRunner
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** The jscpd tool window: Clones, Dead code, Complexity and Migration. */
class JscpdToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        val panels = listOf(
            ReportPanel(project, "Clones", "jscpd.FindingsToolbar", toolWindow.disposable) { server ->
                Reports.clonesTree(server.clones, server.semantic, project).first
            },
            ReportPanel(project, "Dead code", "jscpd.FindingsToolbar", toolWindow.disposable) { server ->
                Reports.deadCodeTree(server.deadCode, project).first
            },
            ReportPanel(project, "Complexity", "jscpd.FindingsToolbar", toolWindow.disposable) { server ->
                Reports.complexityTree(server.complexity, project).first
            },
            MigrationPanel(project, toolWindow.disposable),
        )
        for (panel in panels) {
            val content = factory.createContent(panel, panel.title, false)
            content.isCloseable = false
            toolWindow.contentManager.addContent(content)
        }
    }
}

/** A toolbar over a tree of [Node]s; double click or Enter opens the node's locations. */
abstract class TreePanel(protected val project: Project, val title: String, toolbarGroupId: String) : SimpleToolWindowPanel(true, true) {
    protected val root = DefaultMutableTreeNode()
    protected val model = DefaultTreeModel(root)
    protected val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = NodeRenderer()
    }

    init {
        val group = ActionManager.getInstance().getAction(toolbarGroupId) as ActionGroup
        val actionToolbar = ActionManager.getInstance().createActionToolbar("jscpd.$title", group, true)
        actionToolbar.targetComponent = tree
        setToolbar(actionToolbar.component)
        setContent(JBScrollPane(tree))
        TreeSpeedSearch.installOn(tree)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                navigateSelected()
                return true
            }
        }.installOn(tree)
        tree.registerKeyboardAction({ navigateSelected() }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)
    }

    protected fun setNodes(nodes: List<Node>, emptyText: String) {
        root.removeAllChildren()
        val toExpand = mutableListOf<DefaultMutableTreeNode>()
        fun add(parent: DefaultMutableTreeNode, node: Node) {
            val child = DefaultMutableTreeNode(node)
            parent.add(child)
            if (node.expanded && node.children.isNotEmpty()) toExpand += child
            node.children.forEach { add(child, it) }
        }
        nodes.forEach { add(root, it) }
        model.reload()
        toExpand.forEach { tree.expandPath(TreeUtil.getPathFromRoot(it)) }
        tree.emptyText.text = emptyText
    }

    private fun navigateSelected() {
        val node = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Node ?: return
        when (node.locations.size) {
            0 -> return
            1 -> open(node.locations[0], split = false)
            else -> {
                open(node.locations[0], split = false)
                open(node.locations[1], split = true)
            }
        }
    }

    private fun open(loc: Loc, split: Boolean) {
        val file = Uris.fileOf(loc.path) ?: return
        if (split) {
            // The platform's own "Open in Right Split" action, fed the file the
            // way the project view feeds it. Not EditorWindow.split(): its
            // Kotlin default arguments compile to a split$default method that
            // only exists from 2025.3, so 2025.1 and 2025.2 threw (Marketplace
            // verifier); and not OpenInRightSplitAction directly, which is
            // internal API.
            ActionManager.getInstance().getAction("OpenInRightSplit")?.let { action ->
                val context = SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, project)
                    .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(file))
                    .build()
                val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.TOOLWINDOW_CONTENT, ActionUiKind.NONE, null)
                ActionUtil.performActionDumbAwareWithCallbacks(action, event)
            }
        }
        val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file, loc.startLine - 1, loc.column), true) ?: return
        val document = editor.document
        if (document.lineCount == 0) return
        val startLine = (loc.startLine - 1).coerceIn(0, document.lineCount - 1)
        val endLine = (loc.endLine - 1).coerceIn(startLine, document.lineCount - 1)
        editor.selectionModel.setSelection(document.getLineStartOffset(startLine), document.getLineEndOffset(endLine))
    }

    private class NodeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? Node
            if (node == null) {
                append(value?.toString() ?: "")
                return
            }
            icon = node.icon
            append(node.label)
            node.description?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            toolTipText = node.tooltip
        }
    }
}

/** Clones, dead code or complexity, rebuilt whenever the server has new reports. */
class ReportPanel(project: Project, title: String, toolbarGroupId: String, parent: Disposable, private val build: (JscpdServer) -> List<Node>) :
    TreePanel(project, title, toolbarGroupId) {

    init {
        val server = JscpdServer.getInstance(project)
        server.addReportListener({ ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed) }, parent)
        server.addStateListener({ ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed) }, parent)
        refresh()
    }

    private fun refresh() {
        val server = JscpdServer.getInstance(project)
        val empty = when (server.state) {
            JscpdServer.State.MISSING -> "jscpd is not installed. Tools | jscpd | Download or Update the jscpd Binary"
            JscpdServer.State.DISABLED -> "jscpd is off in Settings | Tools | jscpd"
            JscpdServer.State.FAILED -> "jscpd stopped: ${server.detail}"
            JscpdServer.State.STARTING -> "jscpd is starting"
            JscpdServer.State.STOPPED -> "jscpd is not running"
            JscpdServer.State.RUNNING -> when (title) {
                "Dead code" -> "No dead code reported. Turn the analysis on in Settings | Tools | jscpd or in the project's .jscpd.json."
                "Complexity" -> "Nothing over the complexity limit. Turn the analysis on in Settings | Tools | jscpd or in the project's .jscpd.json."
                else -> "No clones in the open projects"
            }
        }
        setNodes(build(server), empty)
    }
}

/** The pairs of the last `jscpd --compare` run. */
class MigrationPanel(project: Project, parent: Disposable) : TreePanel(project, "Migration", "jscpd.MigrationToolbar") {
    init {
        val runner = CompareRunner.getInstance(project)
        runner.addListener({ ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed) }, parent)
        refresh()
    }

    private fun refresh() {
        val runner = CompareRunner.getInstance(project)
        setNodes(Reports.migrationTree(runner.last, project), "")
        if (runner.last == null) {
            tree.emptyText.clear()
            tree.emptyText.appendText("Measure a port: pick the folder you port from and the folder you port to, and jscpd pairs their functions.")
            tree.emptyText.appendSecondaryText("Compare two folders", SimpleTextAttributes.LINK_ATTRIBUTES) { runner.compare() }
        }
    }
}
