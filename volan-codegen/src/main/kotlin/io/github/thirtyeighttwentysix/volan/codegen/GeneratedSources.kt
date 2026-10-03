package io.github.thirtyeighttwentysix.volan.codegen

import io.github.thirtyeighttwentysix.volan.ir.Schema
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText

/** Writes generated sources, retaining a manifest so removed models do not leave stale Kotlin files. */
public object GeneratedSources {
    /**
     * Generates everything before modifying [directory]. Only files owned by a previous manifest are
     * removed or replaced; unrelated files and symbolic links inside the output are never overwritten.
     * The output root is canonicalized, allowing system-directory aliases such as macOS `/var`.
     */
    @JvmStatic
    public fun write(schema: Schema, directory: Path): List<Path> {
        val generated = VolanGenerator.generate(schema)
        val output = directory.toFile().canonicalFile.toPath()
        val manifest = output.resolve(MANIFEST)
        checkParents(manifest)
        val previous = if (manifest.exists()) manifest.readLines().filter { it.isNotEmpty() }.toSet() else emptySet()
        val targets = generated.associate { it.relativePath to safeTarget(output, it.relativePath) }
        require(targets.values.toSet().size == generated.size) { "Generated source paths collide on this filesystem." }
        val stale = previous.minus(targets.keys).map { safeTarget(output, it) }
        generated.forEach { file ->
            val target = targets.getValue(file.relativePath)
            require(!target.exists() || file.relativePath in previous || target.readText() == file.contents) {
                "Refusing to overwrite an unrelated file: $target"
            }
        }
        generated.forEach { file ->
            val target = targets.getValue(file.relativePath)
            Files.createDirectories(target.parent)
            if (!target.exists() || target.readText() != file.contents) Files.writeString(target, file.contents)
        }
        stale.forEach { Files.deleteIfExists(it) }
        Files.writeString(manifest, targets.keys.sorted().joinToString("\n", postfix = "\n"))
        return targets.values.toList()
    }

    private fun safeTarget(output: Path, name: String): Path {
        val relative = Path.of(name)
        val target = output.resolve(relative).normalize()
        require(!relative.isAbsolute && target.startsWith(output) && target != output && name.endsWith(".kt")) {
            "Invalid generated source path: $name"
        }
        checkParents(target)
        return target
    }

    private fun checkParents(path: Path) {
        var current: Path? = path
        while (current != null) {
            require(!Files.isSymbolicLink(current)) { "Generated output cannot traverse a symbolic link: $current" }
            current = current.parent
        }
    }

    private const val MANIFEST = ".volan-generated-files"
}
