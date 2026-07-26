package io.github.thirtyeighttwentysix.volan.ir

import io.github.thirtyeighttwentysix.volan.schema.SourceFile

/** Loads schema fixtures from the test resources. */
internal object Fixtures {
    /** Reads the fixture at `/fixtures/[name]`, normalising line endings so tests behave the same on every platform. */
    fun read(name: String): String {
        val stream = requireNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) {
            "missing test fixture /fixtures/$name"
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }.replace("\r\n", "\n")
    }

    /** Reads the fixture at `/fixtures/[name]` as a [SourceFile]. */
    fun source(name: String): SourceFile = SourceFile(name, read(name))
}
