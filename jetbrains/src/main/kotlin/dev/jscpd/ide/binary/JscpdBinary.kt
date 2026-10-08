package dev.jscpd.ide.binary

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import com.intellij.util.io.HttpRequests
import com.intellij.util.system.CpuArch
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Where the jscpd executable comes from: the setting, the PATH, or a release
 * build downloaded into the IDE's system folder and checked against the
 * release's checksums.txt.
 */
object JscpdBinary {
    private val LOG = logger<JscpdBinary>()
    private const val REPO = "kucherenko/jscpd"

    /** The first release with `--lsp`. */
    val MIN_VERSION = listOf(5, 4, 0)

    class Found(val command: String, val version: String, val source: String)

    private val exeName get() = if (SystemInfo.isWindows) "jscpd.exe" else "jscpd"

    /** The setting wins, then the PATH, then the newest downloaded release. */
    fun find(settingPath: String): Found? {
        if (settingPath.isNotBlank()) {
            val v = versionOf(settingPath)
            if (v != null) return Found(settingPath, v, "setting")
            LOG.warn("jscpd path $settingPath does not run as jscpd; looking elsewhere")
        }
        val names = if (SystemInfo.isWindows) listOf("jscpd.exe", "jscpd.cmd", "jscpd") else listOf("jscpd")
        for (name in names) {
            val file = findOnPath(name) ?: continue
            val v = versionOf(file.path) ?: continue
            if (atLeast(v, MIN_VERSION)) return Found(file.path, v, "path")
            LOG.info("jscpd $v at ${file.path} is older than 5.4.0, which added --lsp")
        }
        val local = downloaded() ?: return null
        val v = versionOf(local.toString()) ?: return null
        return Found(local.toString(), v, "downloaded")
    }

    /** The first executable named `name` on the PATH the IDE sees (the shell's on macOS, see EnvironmentUtil). */
    private fun findOnPath(name: String): File? {
        val path = EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH") ?: return null
        return path.split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }
    }

    /** What `<command> --version` prints, as `X.Y.Z`. */
    fun versionOf(command: String): String? = try {
        val output = CapturingProcessHandler(GeneralCommandLine(command, "--version")).runProcess(15_000)
        if (output.isTimeout || output.exitCode != 0) null
        else Regex("(\\d+\\.\\d+\\.\\d+)").find(output.stdout)?.groupValues?.get(1)
    } catch (e: Exception) {
        null
    }

    fun parse(version: String): List<Int> = version.removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    fun atLeast(version: String, min: List<Int>): Boolean {
        val v = parse(version)
        for (i in 0 until 3) {
            val d = (v.getOrNull(i) ?: 0) - (min.getOrNull(i) ?: 0)
            if (d != 0) return d > 0
        }
        return true
    }

    fun downloadDir(): Path = Path.of(PathManager.getSystemPath(), "jscpd", "bin")

    /** The newest downloaded release. */
    fun downloaded(): Path? {
        val dir = downloadDir()
        if (!Files.isDirectory(dir)) return null
        return Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it.resolve(exeName)) }
                .map { it.fileName.toString() }
                .toList()
                .sortedWith { a, b -> compareVersions(a, b) }
                .lastOrNull()
                ?.let { dir.resolve(it).resolve(exeName) }
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        for (i in 0 until 3) {
            val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }

    /** The release asset for this machine. */
    fun platformId(): String? {
        val arch = when {
            CpuArch.isArm64() -> "arm64"
            CpuArch.isIntel64() -> "x64"
            else -> return null
        }
        return when {
            SystemInfo.isMac -> "darwin-$arch"
            SystemInfo.isWindows -> "windows-$arch-msvc"
            SystemInfo.isLinux -> "linux-$arch-${if (isMusl()) "musl" else "gnu"}"
            else -> null
        }
    }

    private fun isMusl(): Boolean = try {
        File("/lib").listFiles()?.any { it.name.startsWith("ld-musl-") } == true
    } catch (_: Exception) {
        false
    }

    /** Downloads a release build, checks it against checksums.txt and unpacks it. */
    @Throws(IOException::class)
    fun download(wanted: String, indicator: ProgressIndicator): Found {
        val platform = platformId() ?: throw IOException("jscpd has no release build for ${SystemInfo.OS_NAME} ${CpuArch.CURRENT}; install it another way and set the path in the settings")
        val asset = "jscpd-$platform.tar.gz"
        val base = if (wanted == "latest" || wanted.isBlank()) "https://github.com/$REPO/releases/latest/download"
        else "https://github.com/$REPO/releases/download/${if (wanted.startsWith("v")) wanted else "v$wanted"}"
        indicator.text = "Downloading $asset"
        val archive = HttpRequests.request("$base/$asset").productNameAsUserAgent().readBytes(indicator)
        indicator.text = "Checking the download"
        val sums = String(HttpRequests.request("$base/checksums.txt").productNameAsUserAgent().readBytes(indicator), Charsets.UTF_8)
        val expected = sums.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 2 && it[1] == asset }
            ?.get(0)?.lowercase()
            ?: throw IOException("checksums.txt has no entry for $asset")
        val actual = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        if (actual != expected) throw IOException("checksum mismatch for $asset: expected $expected, got $actual")

        val files = untar(GZIPInputStream(ByteArrayInputStream(archive)).readBytes())
        val exe = files[exeName] ?: files["jscpd"] ?: throw IOException("$asset holds no $exeName")
        val tmp = downloadDir().resolve("tmp-${ProcessHandle.current().pid()}")
        Files.createDirectories(tmp)
        val tmpExe = tmp.resolve(exeName)
        Files.write(tmpExe, exe)
        tmpExe.toFile().setExecutable(true, false)
        val version = versionOf(tmpExe.toString()) ?: run {
            tmp.toFile().deleteRecursively()
            throw IOException("the downloaded binary does not run")
        }
        val dir = downloadDir().resolve("v$version")
        dir.toFile().deleteRecursively()
        Files.move(tmp, dir)
        val command = dir.resolve(exeName).toString()
        LOG.info("downloaded jscpd $version to $command")
        return Found(command, version, "downloaded")
    }

    /** The regular files of a tar archive by name, directories dropped. */
    fun untar(data: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var offset = 0
        while (offset + 512 <= data.size) {
            val header = data.copyOfRange(offset, offset + 512)
            if (header.all { it == 0.toByte() }) break
            fun field(from: Int, to: Int): String {
                val raw = header.copyOfRange(from, to)
                val end = raw.indexOf(0.toByte()).let { if (it < 0) raw.size else it }
                return String(raw, 0, end, Charsets.UTF_8).trim()
            }
            val name = field(0, 100)
            val size = field(124, 136).toIntOrNull(8) ?: 0
            val kind = header[156].toInt().toChar()
            offset += 512
            if ((kind == '0' || kind == '\u0000') && offset + size <= data.size) {
                files[name.substringAfterLast('/')] = data.copyOfRange(offset, offset + size)
            }
            offset += (size + 511) / 512 * 512
        }
        return files
    }
}
