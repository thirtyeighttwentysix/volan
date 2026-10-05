package example;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseTest {
    @Test
    void migrationsCrudAndRollbackWorkFromJava() throws Exception {
        try (var db = Main.openDatabase("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")) {
            var created = Main.insertAndRead(db, "Ada");
            assertEquals("Ada", created.getName());
            assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
                tx.getUser().create(data -> data.setName("Rolled back"));
                throw new IllegalStateException("Abort");
            }));
            assertEquals(java.util.List.of(created), db.getUser().findMany());
        }
    }
}
