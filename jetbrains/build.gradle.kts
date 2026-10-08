import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.jscpd"
version = "0.1.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IntelliJ IDEA is one distribution since 2025.3; the Community edition is a mode of it.
        intellijIdea("2025.3")
        pluginVerifier()
        zipSigner()
    }
    // The LSP client. Gson comes from the platform.
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0") {
        exclude(group = "com.google.code.gson", module = "gson")
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.jscpd.ide"
        name = "jscpd"
        version = project.version.toString()
        // The newest section of CHANGELOG.md, as HTML for the Marketplace page.
        changeNotes = provider {
            val lines = file("CHANGELOG.md").readLines()
            val start = lines.indexOfFirst { it.startsWith("## ") }
            val body = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
            val items = body.filter { it.startsWith("- ") }.joinToString("") { "<li>${it.removePrefix("- ")}</li>" }
            val intro = body.filter { it.isNotBlank() && !it.startsWith("- ") }.joinToString(" ")
            "<p>$intro</p><ul>$items</ul>"
        }
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        // The oldest supported majors next to the build IDE: the Marketplace
        // verifies against every compatible build after an upload, and 0.1.1
        // passed here while failing there on 2025.1 and 2025.2.
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2")
            current()
        }
    }
    publishing {
        // ./gradlew publishPlugin with a Marketplace token; the first upload is done by hand.
        token = providers.environmentVariable("JETBRAINS_PUBLISH_TOKEN")
    }
}

tasks {
    runIde {
        // Opens the lsp-demo fixture with loans.js and the tool window in view, the trust dialog answered.
        args = listOf(rootProject.file("../fixtures/lsp-demo").absolutePath)
        // The sandbox IDE reports its own startup errors in a balloon; keep it quiet in the demo.
        jvmArgs("-Didea.trust.all.projects=true", "-Djscpd.ide.demo=true", "-Didea.log.debug.categories=dev.jscpd.ide", "-Didea.fatal.error.notification=disabled")
        // JSCPD_DEMO_TAB=Migration JSCPD_DEMO_COMPARE=true ./gradlew runIde shows the compare-demo pairs.
        System.getenv("JSCPD_DEMO_TAB")?.let { jvmArgs("-Djscpd.ide.demo.tab=$it") }
        if (System.getenv("JSCPD_DEMO_COMPARE") == "true") jvmArgs("-Djscpd.ide.demo.compare=true")
    }
}
