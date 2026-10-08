package dev.jscpd.ide.migration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import dev.jscpd.ide.lsp.JscpdServer
import dev.jscpd.ide.settings.JscpdSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

/** The result of one `jscpd --compare source target` run. */
class Comparison(val source: String, val target: String, val report: JsonObject, val htmlPath: Path?, val ranAt: String)

/** Runs `jscpd --compare` for the project and keeps the last result for the Migration view. */
@Service(Service.Level.PROJECT)
class CompareRunner(private val project: Project) : Disposable {
    @Volatile var last: Comparison? = null
        private set
    @Volatile var running: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<JscpdServer.Listener>()
    private val watchAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    companion object {
        private val LOG = logger<CompareRunner>()
        private const val SOURCE_KEY = "jscpd.compare.source"
        private const val TARGET_KEY = "jscpd.compare.target"

        fun getInstance(project: Project): CompareRunner = project.service()

        /** The deepest folder that holds both paths. */
        fun commonParent(a: String, b: String): File {
            val pa = File(a).absoluteFile.path.split(File.separatorChar)
            val pb = File(b).absoluteFile.path.split(File.separatorChar)
            val shared = pa.zip(pb).takeWhile { (x, y) -> x == y }.map { it.first }
            val joined = shared.joinToString(File.separator)
            return if (joined.isEmpty()) File(File.separator) else File(joined)
        }
    }

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val c = last ?: return
                if (!JscpdSettings.get().state.compareWatch) return
                val inside = events.any { e -> e.path.startsWith(c.source + "/") || e.path.startsWith(c.target + "/") }
                if (!inside) return
                watchAlarm.cancelAllRequests()
                watchAlarm.addRequest({ run(c.source, c.target, announce = false) }, 2000)
            }
        })
    }

    fun addListener(listener: JscpdServer.Listener, parent: Disposable) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    fun rememberedSource(): String? = PropertiesComponent.getInstance(project).getValue(SOURCE_KEY)
    fun rememberedTarget(): String? = PropertiesComponent.getInstance(project).getValue(TARGET_KEY)

    /** Asks for the two folders, then runs the comparison. */
    fun compare() {
        val dialog = CompareDialog(project, rememberedSource() ?: project.basePath, rememberedTarget())
        if (!dialog.showAndGet()) return
        val source = dialog.source
        val target = dialog.target
        PropertiesComponent.getInstance(project).setValue(SOURCE_KEY, source)
        PropertiesComponent.getInstance(project).setValue(TARGET_KEY, target)
        run(source, target, announce = true)
    }

    fun rerun() {
        val c = last
        if (c == null) compare() else run(c.source, c.target, announce = true)
    }

    private fun outDir(): Path = Path.of(PathManager.getSystemPath(), "jscpd", "compare", project.locationHash)

    fun run(source: String, target: String, announce: Boolean) {
        if (running) return
        val server = JscpdServer.getInstance(project)
        val command = server.binary?.command
        if (command == null) {
            server.notify("jscpd is not available", "Set the path in the settings or download the binary first.", NotificationType.WARNING)
            return
        }
        running = true
        object : Task.Backgroundable(project, "jscpd: comparing ${File(source).name} with ${File(target).name}", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val outDir = outDir()
                    Files.createDirectories(outDir)
                    val cwd = commonParent(source, target)
                    val rel = { p: String -> cwd.toPath().relativize(File(p).absoluteFile.toPath()).toString().ifEmpty { "." } }
                    val args = mutableListOf("--compare", rel(source), rel(target), "-r", "json,html", "-o", outDir.toString(), "--silent")
                    val ignore = JscpdSettings.get().state.compareIgnore.orEmpty()
                    if (ignore.isNotBlank()) args += listOf("--ignore", ignore)
                    val cmd = GeneralCommandLine(command).withParameters(args).withWorkDirectory(cwd).withCharset(Charsets.UTF_8)
                    LOG.info("$ ${cmd.commandLineString}  (in $cwd)")
                    val output = CapturingProcessHandler(cmd).runProcessWithProgressIndicator(indicator)
                    if (output.exitCode != 0 || output.isCancelled) {
                        val text = output.stderr.ifBlank { output.stdout }
                        if (Regex("semantic-download|embedding model|model .*not (found|downloaded)", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                            offerModel(command, source, target, announce)
                        } else if (!output.isCancelled) {
                            server.notify("jscpd --compare failed", text.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" "), NotificationType.ERROR)
                        }
                        return
                    }
                    val json = JsonParser.parseString(Files.readString(outDir.resolve("jscpd-compare.json"))).asJsonObject
                    val html = outDir.resolve("jscpd-compare.html").takeIf { Files.exists(it) }
                    val comparison = Comparison(source, target, json, html, SimpleDateFormat("HH:mm:ss").format(Date()))
                    last = comparison
                    listeners.forEach { it.changed() }
                    if (announce) {
                        val src = json.getAsJsonObject("code")?.getAsJsonArray("sides")?.get(0)?.asJsonObject
                        if (src != null) {
                            server.notify(
                                "${src.get("percentage").asDouble.toInt()}% of ${File(source).name} has a counterpart in ${File(target).name}",
                                "${src.get("matched").asInt} of ${src.get("functions").asInt} functions are paired.",
                                NotificationType.INFORMATION,
                                NotificationAction.createSimple("Open the map") { openMap() },
                            )
                        }
                    }
                } catch (e: Exception) {
                    LOG.warn("compare failed", e)
                    server.notify("jscpd --compare failed", e.message ?: e.toString(), NotificationType.ERROR)
                } finally {
                    running = false
                }
            }
        }.queue()
    }

    private fun offerModel(command: String, source: String, target: String, announce: Boolean) {
        val server = JscpdServer.getInstance(project)
        server.notify(
            "jscpd --compare needs the embedding model",
            "548 MB, downloaded once with jscpd --semantic-download.",
            NotificationType.WARNING,
            NotificationAction.createSimpleExpiring("Download the model") {
                object : Task.Backgroundable(project, "jscpd: downloading the embedding model", true) {
                    override fun run(indicator: ProgressIndicator) {
                        val cmd = GeneralCommandLine(command, "--semantic-download").withWorkDirectory(project.basePath)
                        val output = CapturingProcessHandler(cmd).runProcessWithProgressIndicator(indicator)
                        if (output.exitCode == 0) {
                            ApplicationManager.getApplication().invokeLater { run(source, target, announce) }
                        } else {
                            server.notify("The model download failed", output.stderr.lines().takeLast(3).joinToString(" "), NotificationType.ERROR)
                        }
                    }
                }.queue()
            },
        )
    }

    /** The HTML map of the last comparison, in the browser. */
    fun openMap() {
        val html = last?.htmlPath
        if (html == null || !Files.exists(html)) {
            JscpdServer.getInstance(project).notify("No migration map yet", "Compare two folders first.", NotificationType.INFORMATION)
            return
        }
        BrowserUtil.browse(html.toUri())
    }

    override fun dispose() {}
}
