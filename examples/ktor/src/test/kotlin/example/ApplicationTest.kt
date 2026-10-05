package example

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class ApplicationTest {
    @Test
    fun `HTTP routes persist rows and report invalid or missing users`() = testApplication {
        application { users("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1") }
        val created = client.post("/users/Ada")
        assertEquals(HttpStatusCode.Created, created.status)
        val location = requireNotNull(created.headers["Location"])
        assertEquals("Ada", client.get(location).bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/users/99999").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/users/not-an-id").status)
    }
}
