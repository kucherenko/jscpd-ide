package dev.jscpd.ide.lsp

import com.google.gson.JsonElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture

/** The language server plus the custom requests jscpd answers. */
interface JscpdRemote : LanguageServer {
    @JsonRequest("jscpd/clones")
    fun clones(): CompletableFuture<JsonElement>

    @JsonRequest("jscpd/statistics")
    fun statistics(): CompletableFuture<JsonElement>

    @JsonRequest("jscpd/semantic")
    fun semantic(): CompletableFuture<JsonElement>

    @JsonRequest("jscpd/deadCode")
    fun deadCode(): CompletableFuture<JsonElement>

    @JsonRequest("jscpd/complexity")
    fun complexity(): CompletableFuture<JsonElement>

    @JsonRequest("jscpd/rescan")
    fun rescan(): CompletableFuture<JsonElement?>
}

/** `file://` URIs the way the server writes and reads them. */
object Uris {
    fun of(file: VirtualFile): String = of(file.path)

    fun of(path: String): String {
        val uri = File(path).toURI().toString()
        // File.toURI gives `file:/a/b`; the protocol wants `file:///a/b`.
        return if (uri.startsWith("file:/") && !uri.startsWith("file:///")) "file://" + uri.substring(5) else uri
    }

    fun toPath(uri: String): String? = try {
        val parsed = URI(uri)
        if (parsed.scheme != "file") null else File(parsed).path
    } catch (_: Exception) {
        null
    }

    fun toFile(uri: String): VirtualFile? =
        toPath(uri)?.let { LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(it)) }

    /** The known file first; a refresh only for one the VFS has not seen, which is slow and must stay off the EDT. */
    fun fileOf(path: String): VirtualFile? {
        val independent = FileUtil.toSystemIndependentName(path)
        val fs = LocalFileSystem.getInstance()
        return fs.findFileByPath(independent) ?: if (ApplicationManager.getApplication().isDispatchThread) null else fs.refreshAndFindFileByPath(independent)
    }
}
