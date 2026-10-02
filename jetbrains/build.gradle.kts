plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.jscpd"
version = "0.1.0"

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
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    runIde {
        // Opens the lsp-demo fixture with loans.js and the tool window in view, the trust dialog answered.
        args = listOf(rootProject.file("../fixtures/lsp-demo").absolutePath)
        jvmArgs("-Didea.trust.all.projects=true", "-Djscpd.ide.demo=true")
    }
}
