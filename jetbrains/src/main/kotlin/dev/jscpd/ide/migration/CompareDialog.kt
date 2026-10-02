package dev.jscpd.ide.migration

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.io.File
import javax.swing.JComponent

/** Picks the folder you port from and the folder you port to. */
class CompareDialog(project: Project, initialSource: String?, initialTarget: String?) : DialogWrapper(project) {
    private val sourceField = TextFieldWithBrowseButton().apply {
        text = initialSource ?: ""
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Source: the Folder You Port From"))
    }
    private val targetField = TextFieldWithBrowseButton().apply {
        text = initialTarget ?: ""
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Target: the Folder You Port To"))
    }

    val source: String get() = sourceField.text.trim()
    val target: String get() = targetField.text.trim()

    init {
        title = "Compare Two Folders"
        setOKButtonText("Compare")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Source (port from):") { cell(sourceField).align(AlignX.FILL).resizableColumn() }
        row("Target (port to):") { cell(targetField).align(AlignX.FILL) }
        row { comment("jscpd pairs the functions of the two folders. Vendored folders from the settings are left out of both sides.") }
    }.apply { preferredSize = java.awt.Dimension(640, preferredSize.height) }

    override fun doValidate(): ValidationInfo? {
        if (!File(source).isDirectory) return ValidationInfo("Pick an existing folder", sourceField)
        if (!File(target).isDirectory) return ValidationInfo("Pick an existing folder", targetField)
        val a = File(source).absoluteFile.path
        val b = File(target).absoluteFile.path
        if (a == b || a.startsWith(b + File.separator) || b.startsWith(a + File.separator)) {
            return ValidationInfo("The two folders must not overlap", targetField)
        }
        return null
    }

    override fun getPreferredFocusedComponent(): JComponent = sourceField
}
