package dev.jscpd.ide.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import dev.jscpd.ide.JscpdIcons
import dev.jscpd.ide.migration.Comparison
import java.io.File
import java.util.Locale
import javax.swing.Icon

/** A place in a file; lines count from 1, columns from 0. */
class Loc(val path: String, val startLine: Int, val endLine: Int, val column: Int = 0)

/** One row of a tool window tree. */
class Node(
    val label: String,
    val description: String? = null,
    val icon: Icon? = null,
    val tooltip: String? = null,
    /** One location opens a file; two open both sides. */
    val locations: List<Loc> = emptyList(),
    val children: List<Node> = emptyList(),
    val expanded: Boolean = true,
) {
    override fun toString(): String = label
}

/** Builds the trees of the tool window from the server's reports, the same way the VS Code extension does. */
object Reports {
    private val KIND_LABELS = mapOf("exact" to "Exact copies", "renamed" to "Renamed copies", "similar" to "Similar code", "semantic" to "Semantic clones")
    private val KIND_ORDER = listOf("exact", "renamed", "similar", "semantic")
    private val CATEGORY_LABELS = mapOf(
        "unused-file" to "Unused files",
        "unused-export" to "Unused exports",
        "unused-symbol" to "Unused symbols",
        "unused-import" to "Unused imports",
        "unused-member" to "Unused members",
    )

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(key: String): Int = get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
    private fun JsonObject.double(key: String): Double? = get(key)?.takeIf { it.isJsonPrimitive }?.asDouble
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(key: String): List<JsonObject> = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject } ?: emptyList()
    private fun projects(report: JsonElement?): List<JsonObject> = report?.takeIf { it.isJsonObject }?.asJsonObject?.arr("projects") ?: emptyList()

    /** A clone's `name` is relative to the project's root; with several roots it starts with the root's folder name. */
    fun resolveProjectPath(roots: List<String>, name: String): String {
        if (File(name).isAbsolute) return name
        if (roots.size <= 1) return File(roots.firstOrNull() ?: "", name).path
        val first = name.split('/', '\\').first()
        val root = roots.firstOrNull { File(it).name == first }
        return if (root != null) File(File(root).parent ?: "", name).path else File(roots[0], name).path
    }

    fun rel(path: String, project: Project): String {
        val base = project.basePath ?: return path
        return if (path.startsWith(base)) path.removePrefix(base).trimStart('/', '\\') else path
    }

    private fun side(roots: List<String>, side: JsonObject?): Loc? {
        side ?: return null
        val name = side.str("name") ?: return null
        return Loc(resolveProjectPath(roots, name), side.int("start"), side.int("end"), side.obj("startLoc")?.int("column") ?: 0)
    }

    fun clonesTree(clones: JsonElement?, semantic: JsonElement?, project: Project): Pair<List<Node>, Int> {
        val byKind = LinkedHashMap<String, MutableList<Node>>()
        var count = 0
        for (p in projects(clones) + projects(semantic)) {
            val roots = p.get("roots")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList()
            for (dup in p.arr("duplicates")) {
                val a = side(roots, dup.obj("firstFile")) ?: continue
                val b = side(roots, dup.obj("secondFile")) ?: continue
                val kind = dup.str("kind") ?: "exact"
                val sameName = File(a.path).name == File(b.path).name
                fun short(loc: Loc) = "${if (sameName) rel(loc.path, project) else File(loc.path).name}:${loc.startLine}-${loc.endLine}"
                val score = dup.double("similarity")?.let { " · %.2f".format(Locale.ROOT, it) } ?: ""
                val fragment = dup.str("fragment")?.lines()?.take(12)?.joinToString("\n")
                val tooltip = "<html>${rel(a.path, project)}:${a.startLine}-${a.endLine} ↔ ${rel(b.path, project)}:${b.startLine}-${b.endLine}" +
                    (fragment?.let { "<pre>${escape(it)}</pre>" } ?: "") + "</html>"
                val children = listOf(a to "first copy", b to "second copy").map { (loc, what) ->
                    Node("${rel(loc.path, project)}:${loc.startLine}-${loc.endLine}", what, AllIcons.Actions.EditSource, locations = listOf(loc))
                }
                byKind.getOrPut(kind) { mutableListOf() } += Node(
                    "${short(a)} ↔ ${short(b)}",
                    "${dup.int("tokens")} tokens, ${dup.int("lines")} lines$score",
                    JscpdIcons.forKind(kind),
                    tooltip,
                    listOf(a, b),
                    children,
                    expanded = false,
                )
                count += 1
            }
        }
        val roots = byKind.entries
            .sortedBy { KIND_ORDER.indexOf(it.key).let { i -> if (i < 0) 99 else i } }
            .map { (kind, nodes) -> Node("${KIND_LABELS[kind] ?: kind} (${nodes.size})", icon = JscpdIcons.forKind(kind), children = nodes) }
        return roots to count
    }

    fun deadCodeTree(report: JsonElement?, project: Project): Pair<List<Node>, Int> {
        val byCategory = LinkedHashMap<String, MutableList<Node>>()
        var count = 0
        for (p in projects(report)) {
            val roots = p.get("roots")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList()
            for (f in p.arr("findings")) {
                val path = f.str("path")?.let { if (File(it).isAbsolute) it else resolveProjectPath(roots, it) } ?: continue
                val start = f.obj("start")?.int("line") ?: 1
                val end = f.obj("end")?.int("line") ?: start
                val loc = Loc(path, start, end, f.obj("start")?.int("column") ?: 0)
                val reasons = f.get("reasons")?.takeIf { it.isJsonArray }?.asJsonArray?.joinToString("") { "<br>- ${escape(it.asString)}" } ?: ""
                byCategory.getOrPut(f.str("category") ?: "unused") { mutableListOf() } += Node(
                    f.str("name") ?: "?",
                    "${rel(path, project)}:$start · ${f.int("confidence")}%",
                    AllIcons.Actions.GC,
                    "<html>${escape(f.str("message") ?: "")}$reasons</html>",
                    listOf(loc),
                )
                count += 1
            }
        }
        val roots = byCategory.entries.map { (category, nodes) -> Node("${CATEGORY_LABELS[category] ?: category} (${nodes.size})", icon = AllIcons.Actions.GC, children = nodes) }
        return roots to count
    }

    fun complexityTree(report: JsonElement?, project: Project, complexFile: Int = 50): Pair<List<Node>, Int> {
        val files = projects(report).flatMap { it.obj("summary")?.arr("files") ?: emptyList() }
            .filter { it.int("complexity") > 0 }
            .sortedByDescending { it.int("complexity") }
        val nodes = files.take(100).map { f ->
            val path = f.str("path") ?: ""
            val lines = f.int("lines")
            val dup = f.int("duplicatedLines")
            val dupText = if (lines > 0 && dup > 0) " · %.1f%% duplicated".format(Locale.ROOT, dup * 100.0 / lines) else ""
            Node(rel(path, project), "CX ${f.int("complexity")} · $lines lines$dupText", JscpdIcons.Complexity, locations = listOf(Loc(path, 1, 1)))
        }
        return nodes to files.count { it.int("complexity") >= complexFile }
    }

    /** Files, clones and the duplication of the open projects. */
    class Summary(val files: Int, val clones: Int, val lines: Int, val duplicatedLines: Int) {
        val percentage: Double get() = if (lines > 0) duplicatedLines * 100.0 / lines else 0.0
    }

    fun summary(statistics: JsonElement?): Summary {
        var files = 0
        var clones = 0
        var lines = 0
        var duplicated = 0
        for (p in projects(statistics)) {
            files += p.int("files")
            val total = p.obj("statistics")?.obj("total") ?: continue
            clones += total.int("clones")
            lines += total.int("lines")
            duplicated += total.int("duplicatedLines")
        }
        return Summary(files, clones, lines, duplicated)
    }

    // --------------------------------------------------------- migration

    private fun fn(root: String, f: JsonObject): Loc = Loc(File(root, f.str("file") ?: "").path, f.int("start"), f.int("end"))

    private fun section(section: JsonObject, source: String, target: String, title: String, expanded: Boolean): List<Node> {
        val sides = section.arr("sides")
        val src = sides.getOrNull(0) ?: return emptyList()
        val tgt = sides.getOrNull(1) ?: return emptyList()
        val pairsByFile = LinkedHashMap<String, MutableList<JsonObject>>()
        for (pair in section.arr("pairs")) {
            val file = pair.obj("a")?.str("file") ?: continue
            pairsByFile.getOrPut(file) { mutableListOf() } += pair
        }
        val fileNodes = src.arr("files").map { row ->
            val file = row.str("file") ?: ""
            val pairs = pairsByFile[file].orEmpty().map { pair ->
                val a = pair.obj("a")!!
                val b = pair.obj("b")!!
                val renamed = pair.get("renamed")?.asBoolean == true
                val level = pair.str("level") ?: ""
                val icon = when (level) {
                    "high" -> JscpdIcons.Similar
                    "medium" -> JscpdIcons.Renamed
                    else -> JscpdIcons.Exact
                }
                val byName = if (pair.str("matchedBy") == "name") " · by name" else ""
                Node(
                    if (renamed) "${a.str("name")} → ${b.str("name")}" else a.str("name") ?: "?",
                    "${b.str("file")} · %.2f $level$byName".format(Locale.ROOT, pair.double("similarity") ?: 0.0),
                    icon,
                    locations = listOf(fn(source, a), fn(target, b)),
                )
            }
            val counterpart = row.str("counterpart")?.let { " → $it" } ?: ""
            val similarity = row.double("similarity")?.let { " · %.2f".format(Locale.ROOT, it) } ?: ""
            val matched = row.int("matched")
            val functions = row.int("functions")
            val icon = when {
                matched == functions -> AllIcons.RunConfigurations.TestPassed
                matched == 0 -> AllIcons.RunConfigurations.TestNotRan
                else -> AllIcons.RunConfigurations.TestPaused
            }
            Node(file, "$matched / $functions$counterpart$similarity", icon, locations = listOf(Loc(File(source, file).path, 1, 1)), children = pairs, expanded = false)
        }
        val nodes = mutableListOf(
            Node(
                "$title: ${src.double("percentage")?.toInt() ?: 0}% · ${src.int("matched")} of ${src.int("functions")} in ${File(src.str("path") ?: source).name} have a counterpart",
                icon = AllIcons.Actions.Diff,
                children = fileNodes,
                expanded = expanded,
            ),
        )
        val ready = src.arr("readyToPort")
        if (ready.isNotEmpty()) {
            nodes += Node("Ready to port (${ready.size})", icon = AllIcons.Actions.Execute, expanded = expanded, children = ready.map { f ->
                val callers = f.get("callers")?.takeIf { it.isJsonPrimitive }?.asInt
                Node(f.str("name") ?: "?", "${f.str("file")}:${f.int("start")}-${f.int("end")}" + (callers?.let { " · $it ${if (it == 1) "caller" else "callers"}" } ?: ""), AllIcons.Nodes.Function, locations = listOf(fn(source, f)))
            })
        }
        for ((sideObj, root, arrow) in listOf(Triple(src, source, "Only in"), Triple(tgt, target, "Only in"))) {
            val unmatched = sideObj.arr("unmatched")
            if (unmatched.isEmpty()) continue
            nodes += Node("$arrow ${File(sideObj.str("path") ?: root).name} (${unmatched.size})", icon = AllIcons.Nodes.Function, expanded = false, children = unmatched.map { f ->
                Node(f.str("name") ?: "?", "${f.str("file")}:${f.int("start")}-${f.int("end")}", AllIcons.Nodes.Function, locations = listOf(fn(root, f)))
            })
        }
        return nodes
    }

    fun migrationTree(c: Comparison?, project: Project): List<Node> {
        c ?: return emptyList()
        fun short(folder: String): String {
            val r = rel(folder, project)
            return if (File(r).isAbsolute) File(File(folder).parentFile?.name ?: "", File(folder).name).path else r
        }
        val nodes = mutableListOf(Node("${short(c.source)} → ${short(c.target)}", c.ranAt, AllIcons.Actions.SwapPanels, "${c.source} → ${c.target}"))
        c.report.obj("code")?.let { nodes += section(it, c.source, c.target, "Code", true) }
        c.report.obj("tests")?.let { tests ->
            val total = tests.arr("sides").sumOf { it.int("functions") }
            if (total > 0) nodes += section(tests, c.source, c.target, "Tests", false)
        }
        return nodes
    }

    private fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
