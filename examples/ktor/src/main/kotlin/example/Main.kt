package example

import example.generated.VolanClient
import io.github.thirtyeighttwentysix.volan.coroutines.suspendQuery
import io.github.thirtyeighttwentysix.volan.migrate.MigrationDirectory
import io.github.thirtyeighttwentysix.volan.migrate.Migrator
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.nio.file.Path
import java.sql.DriverManager

fun Application.users(url: String) {
    DriverManager.getConnection(url).use { connection ->
        Migrator(MigrationDirectory(Path.of("migrations"))).apply(connection)
    }
    val db = VolanClient.builder().url(url).build()
    monitor.subscribe(ApplicationStopped) { db.close() }
    routing {
        post("/users/{name}") {
            val name = call.parameters["name"]
            if (name.isNullOrBlank() || name.length > 255) {
                call.respondText("Name must contain 1 to 255 characters", status = HttpStatusCode.BadRequest)
                return@post
            }
            val created = db.suspendQuery { user.create { this.name = name } }
            call.response.headers.append("Location", "/users/${created.id}")
            call.respondText(created.id.toString(), status = HttpStatusCode.Created)
        }
        get("/users/{id}") {
            val userId = call.parameters["id"]?.toIntOrNull()
            if (userId == null) {
                call.respondText("ID must be an integer", status = HttpStatusCode.BadRequest)
                return@get
            }
            val found = db.suspendQuery { user.findFirst { where { id eq userId } } }
            if (found == null) call.respondText("User not found", status = HttpStatusCode.NotFound)
            else call.respondText(found.name)
        }
    }
}

fun main() {
    embeddedServer(Netty, host = "127.0.0.1", port = 8080) {
        users("jdbc:h2:mem:ktor-example;DB_CLOSE_DELAY=-1")
    }.start(wait = true)
}
