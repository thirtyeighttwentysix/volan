package smoke;

import smoke.generated.VolanClient;
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader;
import io.github.thirtyeighttwentysix.volan.runtime.VolanUniqueConstraintException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "volan.sqlite", matches = "true")
class PublishedSqliteTest {
    @Test
    void sqliteProviderIsDiscoveredFromThePublishedJar() throws Exception {
        var schema = Files.readString(Path.of("schema.volan")).replace("postgresql", "sqlite");
        assertNotNull(SchemaLoader.load("sqlite.volan", schema).schemaOrThrow());
        try (var client = VolanClient.builder().url("jdbc:sqlite::memory:").maxPoolSize(4).build()) {
            client.rawExecute("CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, email TEXT NOT NULL UNIQUE, name TEXT)");
            var user = client.getUser().create(d -> d.setEmail("sqlite@example.org"));
            assertNull(user.getName());
            assertEquals(user, client.getUser().findFirstOrThrow());
            assertThrows(VolanUniqueConstraintException.class,
                    () -> client.getUser().create(d -> d.setEmail(user.getEmail())));
            assertThrows(IllegalStateException.class, () -> client.transaction(tx -> {
                tx.getUser().create(d -> d.setEmail("rollback@example.org"));
                throw new IllegalStateException("rollback");
            }));
            assertEquals(1, client.getUser().count());
        }
    }
}
