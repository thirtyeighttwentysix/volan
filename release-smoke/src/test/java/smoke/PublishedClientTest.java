package smoke;

import smoke.generated.VolanClient;
import io.github.thirtyeighttwentysix.volan.dialect.postgres.PostgresDialect;
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader;
import io.github.thirtyeighttwentysix.volan.migrate.DatabaseSync;
import io.github.thirtyeighttwentysix.volan.migrate.PostgresReader;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PublishedClientTest {
    @Test
    void generatedClientWorksUsingOnlyPublishedDependencies() throws Exception {
        try (var database = new PostgreSQLContainer("postgres:17-alpine")) {
            database.start();
            try (var connection = DriverManager.getConnection(database.getJdbcUrl(),
                    database.getUsername(), database.getPassword())) {
                var schema = SchemaLoader.load("schema.volan", Files.readString(Path.of("schema.volan"))).schemaOrThrow();
                var sync = new DatabaseSync(new PostgresReader(), PostgresDialect.INSTANCE);
                sync.push(connection, schema);
                assertTrue(sync.plan(connection, schema).isEmpty());
            }
            var worker = Executors.newFixedThreadPool(2);
            try (var client = VolanClient.builder().url(database.getJdbcUrl())
                    .username(database.getUsername()).password(database.getPassword())
                    .asyncExecutor(worker).build()) {
                var user = client.getUser().create(d -> {
                    d.setEmail("release@example.org");
                    d.setName(null);
                });
                assertNull(user.getName());
                assertEquals(user, client.getUser().findUniqueAsync(q ->
                        q.where(w -> w.getId().eq(user.getId()))).get(10, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> client.transaction(tx -> {
                    tx.getUser().create(d -> d.setEmail("rollback@example.org"));
                    throw new IllegalStateException("rollback");
                }));
                assertEquals(1, client.getUser().count());
            } finally {
                worker.shutdownNow();
            }
        }
    }
}
