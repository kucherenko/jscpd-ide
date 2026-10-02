package dev.jscpd.ide.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import dev.jscpd.ide.lsp.JscpdServer
import java.awt.Component
import java.awt.event.MouseEvent

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

class JscpdStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var statusBar: StatusBar? = null
    @Volatile private var text = "jscpd"
    @Volatile private var tooltip = "jscpd"

    override fun ID(): String = JscpdStatusBarWidgetFactory.ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val server = JscpdServer.getInstance(project)
        server.addStateListener({ update() }, this)
        server.addReportListener({ update() }, this)
        update()
    }

    override fun dispose() {
        statusBar = null
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getText(): String = text
    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT
    override fun getTooltipText(): String = tooltip
    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ToolWindowManager.getInstance(project).getToolWindow("jscpd")?.activate(null)
    }

    private fun update() {
        val server = JscpdServer.getInstance(project)
        when (server.state) {
            JscpdServer.State.RUNNING -> {
                val summary = Reports.summary(server.statistics)
                val progress = server.progress
                text = if (progress != null) "jscpd: $progress" else "jscpd %.1f%%".format(java.util.Locale.ROOT, summary.percentage)
                tooltip = "jscpd ${server.binary?.version ?: ""}: ${summary.files} files, ${summary.clones} clones, ${summary.duplicatedLines} of ${summary.lines} lines duplicated"
            }
            JscpdServer.State.STARTING -> { text = "jscpd…"; tooltip = "jscpd is starting" }
            JscpdServer.State.MISSING -> { text = "jscpd: not installed"; tooltip = "Tools | jscpd | Download or Update the jscpd Binary" }
            JscpdServer.State.FAILED -> { text = "jscpd: failed"; tooltip = server.detail }
            JscpdServer.State.DISABLED -> { text = "jscpd: off"; tooltip = "jscpd is off in Settings | Tools | jscpd" }
            JscpdServer.State.STOPPED -> { text = "jscpd"; tooltip = "jscpd is not running" }
        }
        statusBar?.updateWidget(ID())
    }
}
