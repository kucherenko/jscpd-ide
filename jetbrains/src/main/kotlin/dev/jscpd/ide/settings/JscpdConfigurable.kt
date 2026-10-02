package dev.jscpd.ide.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.jscpd.ide.lsp.JscpdServer

/** Settings | Tools | jscpd. */
class JscpdConfigurable : BoundConfigurable("jscpd") {
    private val state get() = JscpdSettings.get().state

    override fun createPanel(): DialogPanel = panel {
        row {
            checkBox("Run jscpd for open projects").bindSelected(state::enabled)
        }
        row("Path to jscpd:") {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("jscpd executable"))
                .bindText({ state.path ?: "" }, { state.path = it })
                .align(AlignX.FILL)
                .comment("Empty: jscpd from the PATH, then a release build downloaded from GitHub.")
        }
        row("When jscpd is missing:") {
            comboBox(listOf("ask", "always", "never"))
                .bindItem({ state.download ?: "ask" }, { state.download = it ?: "ask" })
                .comment("Ask before downloading a release build, download without asking, or never download.")
        }
        row("Release to download:") {
            textField().bindText({ state.version ?: "latest" }, { state.version = it.ifBlank { "latest" } })
                .comment("latest, or a tag such as v5.4.0")
        }
        group("Analyses") {
            row { comment("An analysis left off here runs when the project's own .jscpd.json turns it on. Values at their defaults leave the project's own in place.") }
            row { checkBox("Clones: exact, renamed and near-miss copies").bindSelected(state::clones) }
            row { checkBox("Similar functions: the same syntax-tree shape (JavaScript, TypeScript)").bindSelected(state::similarFunctions) }
            row { checkBox("Semantic clones: the same job in different code (needs the embedding model)").bindSelected(state::semantic) }
            row { checkBox("Dead code: unused files, exports, symbols, imports and members").bindSelected(state::deadCode) }
            row { checkBox("Complexity: functions and files over the limit").bindSelected(state::complexity) }
            row { checkBox("Report closed files too, so the Problems view lists the whole project").bindSelected(state::allFiles) }
            row("Warning from this many tokens:") {
                intTextField(0..1_000_000).bindIntText(state::warningTokens)
                    .comment("Smaller clones are information. 0: every clone is a warning.")
            }
            row("Similarity of functions, %:") { intTextField(0..100).bindIntText(state::similarity) }
            row("Function complexity limit:") { intTextField(1..10_000).bindIntText(state::functionLimit) }
        }
        group("Advanced") {
            row("Extra .jscpd.json keys (JSON):") {
                expandableTextField().bindText({ state.extraSettings ?: "{}" }, { state.extraSettings = it.ifBlank { "{}" } })
                    .align(AlignX.FILL)
                    .comment("Applied to every project on top of its own config, for example {\"minTokens\": 70}")
            }
            row("Extra arguments for jscpd --lsp:") {
                expandableTextField().bindText({ state.args ?: "" }, { state.args = it })
                    .align(AlignX.FILL)
            }
            row { checkBox("Gutter icons for findings").bindSelected(state::gutterIcons) }
        }
        group("Migration (jscpd --compare)") {
            row("Folders to ignore:") {
                expandableTextField().bindText({ state.compareIgnore ?: JscpdSettings.DEFAULT_IGNORE }, { state.compareIgnore = it })
                    .align(AlignX.FILL)
                    .comment("Comma-separated globs left out of both sides")
            }
            row { checkBox("Run the last comparison again when a file in either folder is saved").bindSelected(state::compareWatch) }
        }
    }

    override fun apply() {
        super.apply()
        for (project in ProjectManager.getInstance().openProjects) {
            JscpdServer.getInstance(project).settingsChanged()
        }
    }
}
