package dev.jscpd.ide.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import dev.jscpd.ide.lsp.JscpdServer
import dev.jscpd.ide.migration.CompareRunner

abstract class JscpdAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}

class RescanAction : JscpdAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { JscpdServer.getInstance(it).state == JscpdServer.State.RUNNING } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        JscpdServer.getInstance(e.project ?: return).rescan()
    }
}

class RestartAction : JscpdAction() {
    override fun actionPerformed(e: AnActionEvent) {
        JscpdServer.getInstance(e.project ?: return).restart()
    }
}

class DownloadBinaryAction : JscpdAction() {
    override fun actionPerformed(e: AnActionEvent) {
        JscpdServer.getInstance(e.project ?: return).download()
    }
}

class OpenSettingsAction : JscpdAction() {
    override fun actionPerformed(e: AnActionEvent) {
        JscpdServer.getInstance(e.project ?: return).openSettings()
    }
}

class CompareAction : JscpdAction() {
    override fun actionPerformed(e: AnActionEvent) {
        CompareRunner.getInstance(e.project ?: return).compare()
    }
}

class CompareRerunAction : JscpdAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { CompareRunner.getInstance(it).last != null } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        CompareRunner.getInstance(e.project ?: return).rerun()
    }
}

class OpenMapAction : JscpdAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { CompareRunner.getInstance(it).last?.htmlPath != null } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        CompareRunner.getInstance(e.project ?: return).openMap()
    }
}
