package dev.jscpd.ide.settings

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** The plugin's settings, the same names as the VS Code extension's. */
@Service(Service.Level.APP)
@State(name = "dev.jscpd.ide.settings", storages = [Storage("jscpd.xml")])
class JscpdSettings : SimplePersistentStateComponent<JscpdSettings.State>(State()) {

    class State : BaseState() {
        var enabled by property(true)
        /** Path to the executable; empty means the PATH, then a downloaded release. */
        var path by string("")
        /** ask, always or never download a release when nothing is installed. */
        var download by string("ask")
        /** The release to download: latest or a tag. */
        var version by string("latest")

        var clones by property(true)
        var similarFunctions by property(false)
        var semantic by property(false)
        var deadCode by property(false)
        var complexity by property(false)
        var allFiles by property(false)
        /** Clones of at least this many tokens are warnings, smaller ones information; 0 keeps every clone a warning. */
        var warningTokens by property(0)
        /** Percent of syntax-tree shape two functions must share. */
        var similarity by property(85)
        var functionLimit by property(15)

        /** Keys of .jscpd.json applied on top of every project's own config, as JSON. */
        var extraSettings by string("{}")
        /** Extra arguments for `jscpd --lsp`, space separated. */
        var args by string("")
        var gutterIcons by property(true)

        var compareIgnore by string(DEFAULT_IGNORE)
        var compareWatch by property(true)
    }

    /** The editor's settings as the server takes them: the keys of `.jscpd.json` with the `lsp` section. */
    fun serverSettings(): JsonObject {
        val s = state
        val base = try {
            JsonParser.parseString(s.extraSettings ?: "{}").takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        } catch (_: Exception) {
            JsonObject()
        }
        val lsp = JsonObject()
        lsp.add("clones", JsonObject().apply {
            addProperty("enabled", s.clones)
            if (s.warningTokens > 0) addProperty("warningTokens", s.warningTokens)
        })
        lsp.add("ast", JsonObject().apply {
            addProperty("enabled", s.similarFunctions)
            addProperty("similarity", s.similarity / 100.0)
        })
        lsp.add("semantic", JsonObject().apply { addProperty("enabled", s.semantic) })
        lsp.add("deadCode", JsonObject().apply { addProperty("enabled", s.deadCode) })
        lsp.add("complexity", JsonObject().apply {
            addProperty("enabled", s.complexity)
            addProperty("functionLimit", s.functionLimit)
        })
        lsp.addProperty("allFiles", s.allFiles)
        base.add("lsp", lsp)
        return base
    }

    fun extraArguments(): List<String> =
        (state.args ?: "").split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_IGNORE = "**/node_modules/**,**/target/**,**/vendor/**,**/dist/**,**/build/**,**/.git/**"
        fun get(): JscpdSettings = service()
    }
}
