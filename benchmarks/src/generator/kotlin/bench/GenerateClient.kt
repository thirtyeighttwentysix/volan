package bench

import io.github.thirtyeighttwentysix.volan.codegen.VolanGenerator
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import java.nio.file.Path
import kotlin.io.path.readText

fun main(args: Array<String>) {
    val schema = Path.of(args[0])
    VolanGenerator.writeTo(SchemaLoader.load(schema.fileName.toString(), schema.readText()).schemaOrThrow(), Path.of(args[1]))
}
