package io.github.thirtyeighttwentysix.volan.gradle

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

class VolanPluginTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `generation reuses configuration cache regenerates changes and removes stale models`() {
        directory.resolve("settings.gradle").writeText("rootProject.name = 'consumer'")
        directory.resolve("build.gradle").writeText(
            """
            plugins {
                id 'org.jetbrains.kotlin.jvm' version '2.4.20'
                id 'io.github.thirtyeighttwentysix.volan'
            }
            """.trimIndent(),
        )
        val schema = directory.resolve("schema.volan")
        val text = """
            datasource db { provider = "h2" url = env("UNSET_URL") }
            generator client { provider = "volan-kotlin" package = "example" }
            model Before { id Int @id }
        """.trimIndent()
        schema.writeText(text)
        fun runner() = GradleRunner.create().withProjectDir(directory.toFile()).withPluginClasspath()
            .withArguments("volanGenerate", "--configuration-cache", "--max-workers=2", "--stacktrace")
        runner().build().task(":volanGenerate")?.outcome shouldBe TaskOutcome.SUCCESS
        runner().build().apply {
            task(":volanGenerate")?.outcome shouldBe TaskOutcome.UP_TO_DATE
            output shouldContain "Reusing configuration cache"
        }
        schema.writeText(text.replace("Before", "After"))
        runner().build().task(":volanGenerate")?.outcome shouldBe TaskOutcome.SUCCESS
        directory.resolve("build/generated/volan/example/Before.kt").exists() shouldBe false
        directory.resolve("build/generated/volan/example/After.kt").exists() shouldBe true
        schema.writeText(text.replace("Int", "NotAType"))
        runner().buildAndFail().output shouldContain "NotAType"
        directory.resolve("build/generated/volan/example/After.kt").exists() shouldBe true
    }
}
