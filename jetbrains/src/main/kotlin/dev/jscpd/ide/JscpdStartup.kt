package dev.jscpd.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Alarm
import dev.jscpd.ide.lsp.JscpdServer
import dev.jscpd.ide.migration.CompareRunner
import java.io.File

/** Starts the language server once the project is open. */
class JscpdStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val server = JscpdServer.getInstance(project)
        server.start()
        // `./gradlew runIde` sets this so the demo opens with the file and the
        // tool window in view; with `.compare` it also runs compare-demo.
        if (System.getProperty("jscpd.ide.demo") == "true") {
            val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, server)
            // A moment later than the IDE's own README tab, so loans.js stays on top.
            alarm.addRequest({
                if (project.isDisposed) return@addRequest
                val base = project.basePath ?: return@addRequest
                LocalFileSystem.getInstance().refreshAndFindFileByPath("$base/src/loans.js")?.let {
                    FileEditorManager.getInstance(project).openFile(it, true)
                }
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("jscpd")
                toolWindow?.show()
                val tab = System.getProperty("jscpd.ide.demo.tab")
                if (tab != null) {
                    toolWindow?.contentManager?.contents?.firstOrNull { it.displayName == tab }?.let { toolWindow.contentManager.setSelectedContent(it) }
                }
                if (System.getProperty("jscpd.ide.demo.compare") == "true") {
                    val demo = File(base).resolveSibling("compare-demo")
                    CompareRunner.getInstance(project).run(demo.resolve("python").path, demo.resolve("typescript").path, announce = false)
                }
            }, 4000)
        }
    }
}
