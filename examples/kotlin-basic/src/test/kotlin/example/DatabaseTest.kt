package example

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class DatabaseTest {
    @Test
    fun `migrations CRUD and rollback use the generated client`() {
        openDatabase("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1").use { db ->
            val created = insertAndRead(db, "Ada")
            assertEquals("Ada", created.name)
            assertThrows(IllegalStateException::class.java) {
                db.transaction { tx ->
                    tx.user.create { name = "Rolled back" }
                    error("Abort")
                }
            }
            assertEquals(listOf(created), db.user.findMany())
        }
    }
}
