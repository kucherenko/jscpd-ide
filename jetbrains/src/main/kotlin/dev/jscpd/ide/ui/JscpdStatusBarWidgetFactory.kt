package dev.jscpd.ide.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.jscpd.ide.lsp.JscpdServer
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import javax.swing.JComponent

/** "jscpd 10.5%" in the status bar: the duplication of the open projects, or why jscpd is not running. */
class JscpdStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = "jscpd"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = JscpdStatusBarWidget(project)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val ID = "jscpd"
    }
}

/** A label of its own, so the text can grow from "jscpd" to "jscpd 10.5%" without being cut. */
class JscpdStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val label = JBLabel("jscpd").apply {
        border = JBUI.Borders.empty(0, 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                ToolWindowManager.getInstance(project).getToolWindow("jscpd")?.activate(null)
            }
        })
    }

    init {
        // Listeners go on from creation: a widget restored from a saved layout may not get `install`.
        val server = JscpdServer.getInstance(project)
        server.addStateListener({ update() }, this)
        server.addReportListener({ update() }, this)
        update()
    }

    override fun ID(): String = JscpdStatusBarWidgetFactory.ID
    override fun getComponent(): JComponent = label
    override fun install(statusBar: StatusBar) {
        update()
    }

    override fun dispose() {}

    private fun update() {
        val server = JscpdServer.getInstance(project)
        val (text, tooltip) = when (server.state) {
            JscpdServer.State.RUNNING -> {
                val summary = Reports.summary(server.statistics)
                val progress = server.progress
                val text = if (progress != null) "jscpd: $progress" else "jscpd %.1f%%".format(Locale.ROOT, summary.percentage)
                text to "jscpd ${server.binary?.version ?: ""}: ${summary.files} files, ${summary.clones} clones, ${summary.duplicatedLines} of ${summary.lines} lines duplicated"
            }
            JscpdServer.State.STARTING -> "jscpd…" to "jscpd is starting"
            JscpdServer.State.MISSING -> "jscpd: not installed" to "Tools | jscpd | Download or Update the jscpd Binary"
            JscpdServer.State.FAILED -> "jscpd: failed" to server.detail
            JscpdServer.State.DISABLED -> "jscpd: off" to "jscpd is off in Settings | Tools | jscpd"
            JscpdServer.State.STOPPED -> "jscpd" to "jscpd is not running"
        }
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            label.text = text
            label.toolTipText = tooltip
            label.revalidate()
            label.repaint()
        }, project.disposed)
    }
}
