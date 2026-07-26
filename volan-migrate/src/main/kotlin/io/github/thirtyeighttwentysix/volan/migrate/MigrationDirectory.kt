package io.github.thirtyeighttwentysix.volan.migrate

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * One migration on disk.
 *
 * @property id the directory name, which is also the order it runs in and the name it is recorded
 *   under. It begins with a timestamp so that two people writing migrations on the same day still
 *   produce an order both of their databases agree on.
 * @property sql the statements, separated by `;`.
 * @property checksum of [sql]; what makes an edit to an applied migration something Volan can notice.
 */
public data class MigrationFile(public val id: String, public val sql: String) {
    /** The part of [id] after the timestamp, which is what a person named it. */
    public val name: String get() = id.substringAfter('_', id)

    public val checksum: String by lazy { checksumOf(sql) }

    public companion object {
        /** The SHA-256 of [sql], as lowercase hex, ignoring the line endings a checkout may change. */
        @JvmStatic
        public fun checksumOf(sql: String): String = MessageDigest.getInstance("SHA-256")
            .digest(sql.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/**
 * The directory a project keeps its migrations in.
 *
 * One directory per migration, holding one `migration.sql`, ordered by name. The layout is a directory
 * rather than a file so that a migration can grow companion files — a note about what it does, a data
 * script that goes with it — without changing what Volan reads.
 */
public class MigrationDirectory(private val root: Path) {
    /** Every migration on disk, in the order they run. */
    public fun read(): List<MigrationFile> {
        if (!root.isDirectory()) return emptyList()
        return root.listDirectoryEntries()
            .filter { it.isDirectory() }
            .sortedBy { it.name }
            .mapNotNull { directory ->
                val file = directory.resolve(SCRIPT)
                if (Files.exists(file)) MigrationFile(directory.name, file.readText()) else null
            }
    }

    /**
     * Writes [sql] as a new migration called [name], and returns it.
     *
     * The timestamp is passed in rather than read from the clock, so that a build can produce the same
     * migration twice and a test can produce a known one.
     */
    public fun write(at: Instant, name: String, sql: String): MigrationFile {
        val id = "${TIMESTAMP.format(at.atOffset(ZoneOffset.UTC))}_${slug(name)}"
        val directory = root.resolve(id)
        directory.createDirectories()
        directory.resolve(SCRIPT).writeText(sql)
        return MigrationFile(id, sql)
    }

    /** Turns a person's description into something that is safe as a directory name. */
    private fun slug(name: String): String {
        val cleaned = name.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return cleaned.trim('_').replace(Regex("_+"), "_").ifEmpty { "migration" }
    }

    private companion object {
        private const val SCRIPT = "migration.sql"
        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}
