package dev.jscpd.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import dev.jscpd.ide.lsp.JscpdServer

/** Starts the language server once the project is open. */
class JscpdStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        JscpdServer.getInstance(project).start()
        // `./gradlew runIde` sets this so the demo opens with the file and the tool window in view.
        if (System.getProperty("jscpd.ide.demo") == "true") {
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                val base = project.basePath ?: return@invokeLater
                LocalFileSystem.getInstance().refreshAndFindFileByPath("$base/src/loans.js")?.let {
                    FileEditorManager.getInstance(project).openFile(it, true)
                }
                ToolWindowManager.getInstance(project).getToolWindow("jscpd")?.show()
            }, project.disposed)
        }
    }
}
