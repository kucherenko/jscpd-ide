package dev.jscpd.ide.lsp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import com.intellij.util.Alarm
import dev.jscpd.ide.binary.JscpdBinary
import dev.jscpd.ide.settings.JscpdConfigurable
import dev.jscpd.ide.settings.JscpdSettings
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ClientInfo
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionCapabilities
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeConfigurationCapabilities
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesCapabilities
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.GeneralClientCapabilities
import org.eclipse.lsp4j.HoverCapabilities
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities
import org.eclipse.lsp4j.ShowDocumentCapabilities
import org.eclipse.lsp4j.ShowDocumentParams
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WindowClientCapabilities
import org.eclipse.lsp4j.WorkDoneProgressBegin
import org.eclipse.lsp4j.WorkDoneProgressEnd
import org.eclipse.lsp4j.WorkDoneProgressReport
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.launch.LSPLauncher
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * One `jscpd --lsp` per project: starts it, keeps its documents in sync with
 * the editors, stores its diagnostics for the annotator, and answers the
 * tool window with the server's reports.
 */
@Service(Service.Level.PROJECT)
class JscpdServer(private val project: Project) : Disposable {

    enum class State { STOPPED, STARTING, RUNNING, MISSING, FAILED, DISABLED }

    fun interface Listener {
        fun changed()
    }

    @Volatile var state: State = State.STOPPED
        private set
    @Volatile var detail: String = ""
        private set
    @Volatile var binary: JscpdBinary.Found? = null
        private set
    @Volatile var progress: String? = null
        private set

    /** The last reports, filled after every batch of diagnostics. */
    @Volatile var clones: JsonElement? = null
        private set
    @Volatile var semantic: JsonElement? = null
        private set
    @Volatile var statistics: JsonElement? = null
        private set
    @Volatile var deadCode: JsonElement? = null
        private set
    @Volatile var complexity: JsonElement? = null
        private set

    private val lock = Any()
    private var process: Process? = null
    private var launcher: Launcher<JscpdRemote>? = null
    private var listening: Future<Void>? = null
    private val diagnostics = ConcurrentHashMap<String, List<Diagnostic>>()
    private val versions = ConcurrentHashMap<String, Int>()
    private val dirty: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val changeAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val reportAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val sender: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "jscpd-send") }
    private val stateListeners = CopyOnWriteArrayList<Listener>()
    private val reportListeners = CopyOnWriteArrayList<Listener>()
    @Volatile private var watching = false
    @Volatile private var listenersInstalled = false

    private val remote: JscpdRemote? get() = launcher?.remoteProxy

    companion object {
        private val LOG = logger<JscpdServer>()
        const val NOTIFICATIONS = "jscpd"

        fun getInstance(project: Project): JscpdServer = project.service()
    }

    // ------------------------------------------------------------ lifecycle

    fun start() {
        ApplicationManager.getApplication().executeOnPooledThread { startNow() }
    }

    fun restart() = start()

    fun settingsChanged() {
        val r = remote
        if (r == null || state != State.RUNNING) {
            start()
            return
        }
        send {
            val settings = JsonObject().apply { add("jscpd", JscpdSettings.get().serverSettings()) }
            r.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(settings))
        }
        scheduleReports()
    }

    private fun startNow() {
        synchronized(lock) {
            stopNow()
            val settings = JscpdSettings.get().state
            if (!settings.enabled) {
                setState(State.DISABLED)
                return
            }
            setState(State.STARTING)
            val found = JscpdBinary.find(settings.path.orEmpty())
            if (found == null) {
                setState(State.MISSING, "jscpd was not found")
                offerDownload()
                return
            }
            binary = found
            try {
                val command = GeneralCommandLine(found.command, "--lsp")
                    .withParameters(JscpdSettings.get().extraArguments())
                    .withWorkDirectory(project.basePath)
                    .withCharset(Charsets.UTF_8)
                val proc = command.createProcess()
                drainStderr(proc)
                val client = JscpdLanguageClient(this)
                val l = LSPLauncher.Builder<JscpdRemote>()
                    .setLocalService(client)
                    .setRemoteInterface(JscpdRemote::class.java)
                    .setInput(proc.inputStream)
                    .setOutput(proc.outputStream)
                    .create()
                listening = l.startListening()
                val result = l.remoteProxy.initialize(initializeParams()).get(120, TimeUnit.SECONDS)
                l.remoteProxy.initialized(InitializedParams())
                process = proc
                launcher = l
                LOG.info("jscpd ${result.serverInfo?.version ?: found.version} started from ${found.command} (${found.source})")
                setState(State.RUNNING)
                installListeners()
                ApplicationManager.getApplication().invokeLater({
                    if (!project.isDisposed) {
                        FileEditorManager.getInstance(project).openFiles.forEach { didOpen(it) }
                    }
                }, project.disposed)
                scheduleReports()
            } catch (e: Exception) {
                LOG.warn("jscpd did not start", e)
                stopNow()
                setState(State.FAILED, e.message ?: e.toString())
                notify("jscpd did not start", e.message ?: e.toString(), NotificationType.ERROR)
            }
        }
    }

    private fun stopNow() {
        val r = remote
        if (r != null) {
            try {
                r.shutdown().get(3, TimeUnit.SECONDS)
                r.exit()
            } catch (_: Exception) {
                // the server may be gone already
            }
        }
        listening?.cancel(true)
        process?.destroy()
        process = null
        launcher = null
        listening = null
        watching = false
        progress = null
        versions.clear()
        dirty.clear()
        if (diagnostics.isNotEmpty()) {
            diagnostics.clear()
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart()
            }, project.disposed)
        }
    }

    private fun drainStderr(proc: Process) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                proc.errorStream.bufferedReader().forEachLine { LOG.info("jscpd: $it") }
            } catch (_: Exception) {
                // the stream closes with the process
            }
        }
    }

    private fun initializeParams(): InitializeParams {
        val folders = workspaceFolders()
        return InitializeParams().apply {
            processId = ProcessHandle.current().pid().toInt()
            clientInfo = ClientInfo("jscpd-jetbrains", "0.1.0")
            workspaceFolders = folders
            @Suppress("DEPRECATION")
            rootUri = folders.firstOrNull()?.uri
            initializationOptions = JscpdSettings.get().serverSettings()
            capabilities = ClientCapabilities().apply {
                workspace = WorkspaceClientCapabilities().apply {
                    workspaceFolders = true
                    configuration = true
                    didChangeConfiguration = DidChangeConfigurationCapabilities()
                    didChangeWatchedFiles = DidChangeWatchedFilesCapabilities(true)
                }
                textDocument = TextDocumentClientCapabilities().apply {
                    publishDiagnostics = PublishDiagnosticsCapabilities(true)
                    codeAction = CodeActionCapabilities()
                    hover = HoverCapabilities()
                }
                window = WindowClientCapabilities().apply {
                    showDocument = ShowDocumentCapabilities(true)
                    workDoneProgress = true
                }
                general = GeneralClientCapabilities().apply {
                    positionEncodings = listOf("utf-16")
                }
            }
        }
    }

    fun workspaceFolders(): List<WorkspaceFolder> {
        val roots = ReadAction.compute<List<VirtualFile>, RuntimeException> {
            if (project.isDisposed) emptyList() else ProjectRootManager.getInstance(project).contentRoots.toList()
        }
        val folders = roots.filter { it.isInLocalFileSystem }.map { WorkspaceFolder(Uris.of(it), it.name) }
        if (folders.isNotEmpty()) return folders
        val base = project.basePath ?: return emptyList()
        return listOf(WorkspaceFolder(Uris.of(base), project.name))
    }

    private fun setState(next: State, detail: String = "") {
        state = next
        this.detail = detail
        stateListeners.forEach { it.changed() }
    }

    fun addStateListener(listener: Listener, parent: Disposable) {
        stateListeners += listener
        Disposer.register(parent) { stateListeners -= listener }
    }

    fun addReportListener(listener: Listener, parent: Disposable) {
        reportListeners += listener
        Disposer.register(parent) { reportListeners -= listener }
    }

    override fun dispose() {
        synchronized(lock) { stopNow() }
        sender.shutdownNow()
    }

    // ------------------------------------------------------- the binary

    private fun offerDownload() {
        when (JscpdSettings.get().state.download) {
            "never" -> notify(
                "jscpd is not installed",
                "Set the path to a jscpd executable in Settings | Tools | jscpd, or install jscpd 5.4.0 or newer.",
                NotificationType.WARNING,
                NotificationAction.createSimple("Open settings") { openSettings() },
            )
            "always" -> download()
            else -> notify(
                "jscpd is not installed",
                "Download the release build for ${JscpdBinary.platformId() ?: "this machine"} (about 8 MB) into the IDE's system folder?",
                NotificationType.INFORMATION,
                NotificationAction.createSimpleExpiring("Download jscpd") { download() },
                NotificationAction.createSimple("Open settings") { openSettings() },
            )
        }
    }

    fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, JscpdConfigurable::class.java)
    }

    /** Downloads the release from the settings, then starts the server with it. */
    fun download() {
        object : Task.Backgroundable(project, "Downloading jscpd", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val found = JscpdBinary.download(JscpdSettings.get().state.version ?: "latest", indicator)
                    notify("jscpd ${found.version} is ready", found.command, NotificationType.INFORMATION)
                    startNow()
                } catch (e: Exception) {
                    LOG.warn("download failed", e)
                    notify("Could not download jscpd", e.message ?: e.toString(), NotificationType.ERROR)
                }
            }
        }.queue()
    }

    // ------------------------------------------------------ document sync

    private fun send(block: () -> Unit) {
        if (sender.isShutdown) return
        sender.execute {
            try {
                block()
            } catch (e: Exception) {
                LOG.warn("jscpd message failed", e)
            }
        }
    }

    private fun installListeners() {
        if (listenersInstalled) return
        listenersInstalled = true
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) = didOpen(file)
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) = didClose(file)
        })
        connection.subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            override fun beforeDocumentSaving(document: Document) {
                val file = FileDocumentManager.getInstance().getFile(document) ?: return
                val uri = Uris.of(file)
                if (!versions.containsKey(uri)) return
                val r = remote ?: return
                send { r.textDocumentService.didSave(DidSaveTextDocumentParams(TextDocumentIdentifier(uri))) }
            }
        })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (!watching) return
                val r = remote ?: return
                val roots = workspaceFolders().mapNotNull { Uris.toPath(it.uri) }
                val changes = events.mapNotNull { event ->
                    val path = event.path
                    if (roots.none { path.startsWith(it) } || path.contains("/node_modules/") || path.contains("/.git/")) return@mapNotNull null
                    val type = when (event) {
                        is VFileCreateEvent -> FileChangeType.Created
                        is VFileDeleteEvent -> FileChangeType.Deleted
                        else -> FileChangeType.Changed
                    }
                    FileEvent(Uris.of(path), type)
                }
                if (changes.isNotEmpty()) {
                    send { r.workspaceService.didChangeWatchedFiles(DidChangeWatchedFilesParams(changes)) }
                }
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                scheduleChange(file)
            }
        }, this)
    }

    fun watchFiles(on: Boolean) {
        watching = on
    }

    private fun didOpen(file: VirtualFile) {
        val r = remote ?: return
        if (!file.isInLocalFileSystem || file.isDirectory) return
        val uri = Uris.of(file)
        if (versions.putIfAbsent(uri, 1) != null) return
        val text = ReadAction.compute<String?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(file)?.text ?: try {
                VfsUtilCore.loadText(file)
            } catch (_: Exception) {
                null
            }
        } ?: return
        val language = file.fileType.name.lowercase()
        send { r.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, language, 1, text))) }
    }

    private fun didClose(file: VirtualFile) {
        val r = remote ?: return
        val uri = Uris.of(file)
        if (versions.remove(uri) == null) return
        dirty.remove(uri)
        send { r.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri))) }
    }

    private fun scheduleChange(file: VirtualFile) {
        val uri = Uris.of(file)
        if (!versions.containsKey(uri)) return
        dirty += uri
        changeAlarm.cancelAllRequests()
        changeAlarm.addRequest({ flushChanges() }, 250)
    }

    private fun flushChanges() {
        val r = remote ?: return
        val uris = dirty.toList()
        dirty.clear()
        for (uri in uris) {
            val file = Uris.toFile(uri) ?: continue
            val text = ReadAction.compute<String?, RuntimeException> {
                FileDocumentManager.getInstance().getCachedDocument(file)?.text
            } ?: continue
            val version = versions.compute(uri) { _, v -> (v ?: 0) + 1 } ?: continue
            send {
                r.textDocumentService.didChange(
                    DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, version), listOf(TextDocumentContentChangeEvent(text))),
                )
            }
        }
    }

    // ------------------------------------------------------- diagnostics

    fun onDiagnostics(uri: String, list: List<Diagnostic>) {
        if (list.isEmpty()) diagnostics.remove(uri) else diagnostics[uri] = list
        LOG.debug { "${list.size} diagnostics for $uri" }
        scheduleReports()
        val path = Uris.toPath(uri) ?: return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val file = Uris.fileOf(path) ?: return@invokeLater
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@invokeLater
            DaemonCodeAnalyzer.getInstance(project).restart(psi)
        }, project.disposed)
    }

    fun diagnosticsFor(file: VirtualFile): List<Diagnostic> = diagnostics[Uris.of(file)].orEmpty()

    /** The code actions the server offers for one diagnostic, within a short wait. */
    fun codeActions(uri: String, diagnostic: Diagnostic): List<Either<Command, CodeAction>> {
        val r = remote ?: return emptyList()
        return try {
            r.textDocumentService
                .codeAction(CodeActionParams(TextDocumentIdentifier(uri), diagnostic.range, CodeActionContext(listOf(diagnostic))))
                .get(3, TimeUnit.SECONDS)
                .orEmpty()
        } catch (e: Exception) {
            LOG.debug("code actions failed", e)
            emptyList()
        }
    }

    fun executeCommand(command: Command) {
        val r = remote ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                r.workspaceService.executeCommand(ExecuteCommandParams(command.command, command.arguments)).get(10, TimeUnit.SECONDS)
            } catch (e: Exception) {
                LOG.warn("command ${command.command} failed", e)
            }
        }
    }

    fun showDocument(params: ShowDocumentParams) {
        val path = Uris.toPath(params.uri) ?: return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val file = Uris.fileOf(path) ?: return@invokeLater
            val selection = params.selection
            val descriptor = if (selection != null) OpenFileDescriptor(project, file, selection.start.line, selection.start.character)
            else OpenFileDescriptor(project, file)
            val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, params.takeFocus ?: true)
            if (editor != null && selection != null) {
                val start = offset(editor.document, selection.start)
                val end = offset(editor.document, selection.end)
                editor.selectionModel.setSelection(start, end)
                editor.caretModel.moveToOffset(start)
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            }
        }, project.disposed)
    }

    fun onProgress(params: ProgressParams) {
        val value = params.value ?: return
        if (!value.isLeft) return
        progress = when (val n = value.left) {
            is WorkDoneProgressBegin -> n.title
            is WorkDoneProgressReport -> n.message ?: progress
            is WorkDoneProgressEnd -> {
                // An analysis finished; its findings may be in files that are not open.
                scheduleReports()
                null
            }
            else -> progress
        }
        stateListeners.forEach { it.changed() }
    }

    fun showMessage(params: MessageParams) {
        val type = when (params.type) {
            MessageType.Error -> NotificationType.ERROR
            MessageType.Warning -> NotificationType.WARNING
            else -> NotificationType.INFORMATION
        }
        notify("jscpd", params.message, type)
    }

    fun notify(title: String, content: String, type: NotificationType, vararg actions: NotificationAction) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATIONS).createNotification(title, content, type)
        actions.forEach { notification.addAction(it) }
        notification.notify(project)
    }

    // ----------------------------------------------------------- reports

    fun rescan() {
        val r = remote ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                r.rescan().get(120, TimeUnit.SECONDS)
            } catch (e: Exception) {
                LOG.warn("rescan failed", e)
            }
            fetchReports()
        }
    }

    private fun scheduleReports() {
        reportAlarm.cancelAllRequests()
        reportAlarm.addRequest({ fetchReports() }, 700)
    }

    private fun <T> request(call: (JscpdRemote) -> CompletableFuture<T>): T? {
        val r = remote ?: return null
        return try {
            call(r).get(60, TimeUnit.SECONDS)
        } catch (e: Exception) {
            LOG.warn("jscpd request failed", e)
            null
        }
    }

    /** Every report is asked for: a project's own config may run an analysis the editor's settings leave alone. */
    private fun fetchReports() {
        if (state != State.RUNNING) return
        clones = request { it.clones() }
        statistics = request { it.statistics() }
        semantic = request { it.semantic() }
        deadCode = request { it.deadCode() }
        complexity = request { it.complexity() }
        reportListeners.forEach { it.changed() }
    }

    // ------------------------------------------------------------ helpers

    fun offset(document: Document, position: Position): Int {
        val line = position.line.coerceIn(0, maxOf(0, document.lineCount - 1))
        if (document.lineCount == 0) return 0
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        return (start + position.character).coerceIn(start, end)
    }
}
