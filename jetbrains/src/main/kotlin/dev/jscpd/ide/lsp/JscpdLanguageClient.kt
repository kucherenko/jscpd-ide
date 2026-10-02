package dev.jscpd.ide.lsp

import com.intellij.openapi.diagnostic.logger
import dev.jscpd.ide.settings.JscpdSettings
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowDocumentParams
import org.eclipse.lsp4j.ShowDocumentResult
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture

/** What the server asks of the editor; everything is handed to [JscpdServer]. */
class JscpdLanguageClient(private val server: JscpdServer) : LanguageClient {
    private val log = logger<JscpdLanguageClient>()

    override fun telemetryEvent(obj: Any?) {}

    override fun publishDiagnostics(params: PublishDiagnosticsParams) {
        server.onDiagnostics(params.uri, params.diagnostics ?: emptyList())
    }

    override fun showMessage(params: MessageParams) {
        server.showMessage(params)
    }

    override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> {
        server.showMessage(MessageParams(params.type, params.message))
        return CompletableFuture.completedFuture<MessageActionItem>(params.actions?.firstOrNull())
    }

    override fun logMessage(params: MessageParams) {
        log.info("jscpd: ${params.message}")
    }

    override fun registerCapability(params: RegistrationParams): CompletableFuture<Void> {
        if (params.registrations.any { it.method == "workspace/didChangeWatchedFiles" }) {
            server.watchFiles(true)
        }
        return CompletableFuture.completedFuture(null)
    }

    override fun unregisterCapability(params: UnregistrationParams): CompletableFuture<Void> {
        if (params.unregisterations.any { it.method == "workspace/didChangeWatchedFiles" }) {
            server.watchFiles(false)
        }
        return CompletableFuture.completedFuture(null)
    }

    override fun showDocument(params: ShowDocumentParams): CompletableFuture<ShowDocumentResult> {
        server.showDocument(params)
        return CompletableFuture.completedFuture(ShowDocumentResult(true))
    }

    override fun createProgress(params: WorkDoneProgressCreateParams): CompletableFuture<Void> =
        CompletableFuture.completedFuture(null)

    override fun notifyProgress(params: ProgressParams) {
        server.onProgress(params)
    }

    override fun workspaceFolders(): CompletableFuture<List<WorkspaceFolder>> =
        CompletableFuture.completedFuture(server.workspaceFolders())

    override fun configuration(params: ConfigurationParams): CompletableFuture<List<Any>> =
        CompletableFuture.completedFuture<List<Any>>(params.items.map { JscpdSettings.get().serverSettings() })
}
