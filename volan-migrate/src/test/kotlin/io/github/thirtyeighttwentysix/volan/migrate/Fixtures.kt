package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.ir.Schema
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import io.github.thirtyeighttwentysix.volan.schema.SourceFile

/** The schemas these tests diff and render, loaded the way a build would load them. */
internal object Fixtures {
    fun blog(): Schema = fixture("blog.volan")

    fun schema(text: String): Schema = SchemaLoader.loadOrThrow(SourceFile("test.volan", text))

    private fun fixture(name: String): Schema {
        val text = requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
            .replace("\r\n", "\n")
        return SchemaLoader.loadOrThrow(SourceFile(name, text))
    }
}
