package verify

import io.github.thirtyeighttwentysix.volan.codegen.VolanGenerator
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import io.github.thirtyeighttwentysix.volan.schema.SourceFile
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.system.exitProcess

/**
 * Generates a Volan client from a schema, the way a build plugin will.
 *
 * Arguments: the schema file, then the directory to write the sources into.
 */
fun main(arguments: Array<String>) {
    if (arguments.size != 2) {
        System.err.println("usage: generate-client <schema.volan> <output directory>")
        exitProcess(2)
    }
    val schemaPath = Path.of(arguments[0])
    val result = SchemaLoader.load(SourceFile(schemaPath.fileName.toString(), schemaPath.readText()))
    if (result.hasErrors) {
        System.err.print(result.render())
        exitProcess(1)
    }
    // Clearing first means a model removed from the schema disappears from the sources too, rather
    // than lingering as a file that still compiles.
    val output = Path.of(arguments[1])
    output.toFile().deleteRecursively()
    val written = VolanGenerator.writeTo(result.schemaOrThrow(), output)
    written.forEach { println("generated $it") }
}
