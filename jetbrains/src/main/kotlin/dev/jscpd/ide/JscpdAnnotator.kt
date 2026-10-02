package dev.jscpd.ide

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.util.IncorrectOperationException
import dev.jscpd.ide.lsp.JscpdServer
import dev.jscpd.ide.lsp.Uris
import dev.jscpd.ide.settings.JscpdSettings
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import javax.swing.Icon

/**
 * Turns the server's diagnostics into highlights: a gutter icon per kind of
 * clone, the faded "unused" style for dead code, and the server's code
 * actions as intentions.
 */
class JscpdAnnotator : ExternalAnnotator<PsiFile, List<JscpdAnnotator.Finding>>() {

    class Finding(val diagnostic: Diagnostic, val actions: List<Either<Command, CodeAction>>)

    override fun collectInformation(file: PsiFile): PsiFile? =
        file.takeIf { it.virtualFile?.isInLocalFileSystem == true }

    override fun doAnnotate(collectedInfo: PsiFile?): List<Finding>? {
        val file = collectedInfo ?: return null
        val virtualFile = file.virtualFile ?: return null
        val server = JscpdServer.getInstance(file.project)
        val diagnostics = server.diagnosticsFor(virtualFile)
        if (diagnostics.isEmpty()) return emptyList()
        val uri = Uris.of(virtualFile)
        return diagnostics.map { Finding(it, server.codeActions(uri, it)) }
    }

    override fun apply(file: PsiFile, annotationResult: List<Finding>?, holder: AnnotationHolder) {
        val findings = annotationResult ?: return
        val document = file.viewProvider.document ?: return
        val server = JscpdServer.getInstance(file.project)
        val gutterIcons = JscpdSettings.get().state.gutterIcons
        for (finding in findings) {
            val d = finding.diagnostic
            val start = server.offset(document, d.range.start)
            val end = server.offset(document, d.range.end).coerceAtLeast(start)
            val range = TextRange(start, end)
            val code = codeOf(d)
            val severity = when (d.severity) {
                DiagnosticSeverity.Error -> HighlightSeverity.ERROR
                DiagnosticSeverity.Warning -> HighlightSeverity.WARNING
                DiagnosticSeverity.Information -> HighlightSeverity.WEAK_WARNING
                else -> HighlightSeverity.INFORMATION
            }
            val related = d.relatedInformation.orEmpty().joinToString("") { info ->
                val path = Uris.toPath(info.location.uri)?.let { file.project.basePath?.let { base -> it.removePrefix(base).trimStart('/', '\\') } ?: it }
                "<br>${info.message}: $path:${info.location.range.start.line + 1}"
            }
            val message = messageOf(d)
            val builder = holder.newAnnotation(severity, message)
                .range(range)
                .tooltip("<html><b>jscpd</b> ${code ?: ""}<br>$message$related</html>")
            if (code != null && code.startsWith("unused-")) {
                builder.highlightType(ProblemHighlightType.LIKE_UNUSED_SYMBOL)
            }
            val fixes = finding.actions.map { JscpdFix(it, server) }
            if (gutterIcons) {
                JscpdIcons.forCode(code)?.let { icon ->
                    val goTo = fixes.firstOrNull { it.action.isLeft || it.action.right.command != null }
                    builder.gutterIconRenderer(JscpdGutterRenderer(icon, message, goTo))
                }
            }
            fixes.forEach { builder.withFix(it) }
            builder.create()
        }
    }

    private fun codeOf(d: Diagnostic): String? = d.code?.let { if (it.isLeft) it.left else it.right?.toString() }

    /** The message is plain text or markup in LSP 3.18; jscpd sends text. */
    private fun messageOf(d: Diagnostic): String = d.message?.let { if (it.isLeft) it.left else it.right?.value } ?: ""
}

/** An icon in the gutter; a click runs the "go to the other copy" action when the server offers one. */
class JscpdGutterRenderer(private val icon: Icon, private val tooltip: String, private val goTo: JscpdFix?) : GutterIconRenderer() {
    override fun getIcon(): Icon = icon
    override fun getTooltipText(): String = tooltip
    override fun isNavigateAction(): Boolean = goTo != null
    override fun getClickAction(): AnAction? = goTo?.let { fix ->
        object : AnAction(fix.text) {
            override fun actionPerformed(e: AnActionEvent) {
                val project = e.project ?: return
                fix.invoke(project, null, null)
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is JscpdGutterRenderer && other.icon == icon && other.tooltip == tooltip
    override fun hashCode(): Int = 31 * icon.hashCode() + tooltip.hashCode()
}

/** A code action of the server as an intention: a command, or edits to apply. */
class JscpdFix(val action: Either<Command, CodeAction>, private val server: JscpdServer) : IntentionAction {
    private val title: String get() = if (action.isLeft) action.left.title else action.right.title

    override fun getText(): String = title
    override fun getFamilyName(): String = "jscpd"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true
    override fun startInWriteAction(): Boolean = false

    @Throws(IncorrectOperationException::class)
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (action.isLeft) {
            server.executeCommand(action.left)
            return
        }
        val codeAction = action.right
        codeAction.edit?.let { applyEdit(project, it) }
        codeAction.command?.let { server.executeCommand(it) }
    }

    private fun applyEdit(project: Project, edit: WorkspaceEdit) {
        val perUri = LinkedHashMap<String, MutableList<TextEdit>>()
        // Edits come as TextEdit, or wrapped in Either since LSP 3.18.
        fun textEdits(list: List<*>?): List<TextEdit> = list.orEmpty().mapNotNull { e ->
            when (e) {
                is TextEdit -> e
                is Either<*, *> -> e.left as? TextEdit
                else -> null
            }
        }
        edit.changes?.forEach { (uri, edits) -> perUri.getOrPut(uri) { mutableListOf() }.addAll(textEdits(edits)) }
        edit.documentChanges?.forEach { change ->
            if (change.isLeft) perUri.getOrPut(change.left.textDocument.uri) { mutableListOf() }.addAll(textEdits(change.left.edits))
        }
        if (perUri.isEmpty()) return
        WriteCommandAction.runWriteCommandAction(project, title, "jscpd", {
            for ((uri, edits) in perUri) {
                val file = Uris.toFile(uri) ?: continue
                val document = FileDocumentManager.getInstance().getDocument(file) ?: continue
                // Later edits first, so earlier offsets stay valid.
                for (textEdit in edits.sortedWith(compareByDescending<TextEdit> { it.range.start.line }.thenByDescending { it.range.start.character })) {
                    val start = server.offset(document, textEdit.range.start)
                    val end = server.offset(document, textEdit.range.end).coerceAtLeast(start)
                    document.replaceString(start, end, textEdit.newText)
                }
            }
        })
    }
}
