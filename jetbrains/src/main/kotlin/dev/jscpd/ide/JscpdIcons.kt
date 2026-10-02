package dev.jscpd.ide

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** One icon per kind of finding, the same drawings as the VS Code extension. */
object JscpdIcons {
    @JvmField val ToolWindow: Icon = load("/icons/toolwindow.svg")
    @JvmField val Exact: Icon = load("/icons/clone-exact.svg")
    @JvmField val Renamed: Icon = load("/icons/clone-renamed.svg")
    @JvmField val Similar: Icon = load("/icons/clone-similar.svg")
    @JvmField val Function: Icon = load("/icons/clone-function.svg")
    @JvmField val Semantic: Icon = load("/icons/clone-semantic.svg")
    @JvmField val Complexity: Icon = load("/icons/complexity.svg")

    private fun load(path: String): Icon = IconLoader.getIcon(path, JscpdIcons::class.java)

    /** The icon for a diagnostic code of the server, none for dead code. */
    fun forCode(code: String?): Icon? = when (code) {
        "jscpd/duplicate-code" -> Exact
        "jscpd/renamed-code" -> Renamed
        "jscpd/similar-code" -> Similar
        "jscpd/similar-function" -> Function
        "jscpd/semantic-code" -> Semantic
        "jscpd/complex-function", "jscpd/complex-file" -> Complexity
        else -> null
    }

    /** The icon for a `kind` of the clones report. */
    fun forKind(kind: String?): Icon = when (kind) {
        "renamed" -> Renamed
        "similar" -> Similar
        "semantic" -> Semantic
        else -> Exact
    }
}
