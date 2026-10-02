package io.github.thirtyeighttwentysix.volan.cli

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText

class LauncherTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `installed launcher displays help without native access warnings`() {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val launcher = Path.of(System.getProperty("volan.cli.installDir"), "bin", if (windows) "volan.bat" else "volan")
        val args = if (windows) {
            listOf(
                "cmd.exe",
                "/d",
                "/c",
                launcher.toString(),
                "--help",
            )
        } else {
            listOf("sh", launcher.toString(), "--help")
        }
        val output = directory.resolve("stdout.txt")
        val errors = directory.resolve("stderr.txt")
        val builder = ProcessBuilder(args).directory(directory.toFile())
            .redirectOutput(output.toFile()).redirectError(errors.toFile())
        val environment = builder.environment()
        listOf("JAVA_OPTS", "VOLAN_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").forEach { environment.remove(it) }
        environment["JAVA_HOME"] = System.getProperty("java.home")
        if (Runtime.version().feature() >= 24) environment["VOLAN_OPTS"] = "--illegal-native-access=deny"
        val process = builder.start()
        val completed = process.waitFor(30, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        completed shouldBe true
        process.exitValue() shouldBe 0
        output.readText() shouldContain "Usage: volan"
        errors.readText() shouldBe ""
    }
}
