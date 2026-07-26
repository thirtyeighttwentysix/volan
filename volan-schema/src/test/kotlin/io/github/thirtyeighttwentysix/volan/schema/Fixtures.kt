package io.github.thirtyeighttwentysix.volan.schema

/** Loads schema fixtures from the test resources. */
internal object Fixtures {
    /**
     * Reads the fixture at `/fixtures/[name]` with line endings normalised, so that the tests behave
     * identically on Windows and on Linux.
     */
    fun read(name: String): String {
        val stream = requireNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) {
            "missing test fixture /fixtures/$name"
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }.replace("\r\n", "\n")
    }

    /** Reads the fixture at `/fixtures/[name]` as a [SourceFile] named after the fixture. */
    fun source(name: String): SourceFile = SourceFile(name, read(name))
}
