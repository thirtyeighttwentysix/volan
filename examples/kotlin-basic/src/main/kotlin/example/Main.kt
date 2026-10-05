package example

import example.generated.User
import example.generated.VolanClient
import io.github.thirtyeighttwentysix.volan.migrate.MigrationDirectory
import io.github.thirtyeighttwentysix.volan.migrate.Migrator
import java.nio.file.Path
import java.sql.DriverManager

fun openDatabase(url: String): VolanClient {
    DriverManager.getConnection(url).use { connection ->
        Migrator(MigrationDirectory(Path.of("migrations"))).apply(connection)
    }
    return VolanClient.builder().url(url).build()
}

fun insertAndRead(db: VolanClient, name: String): User = db.transaction { tx ->
    val created = tx.user.create { this.name = name }
    tx.user.findFirstOrThrow { where { id eq created.id } }
}

fun main() {
    openDatabase("jdbc:h2:mem:kotlin-example;DB_CLOSE_DELAY=-1").use { db ->
        println("Saved and read: ${insertAndRead(db, "Ada").name}")
    }
}
