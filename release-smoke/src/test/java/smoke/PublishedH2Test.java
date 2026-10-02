package smoke;

import smoke.generated.VolanClient;
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader;
import io.github.thirtyeighttwentysix.volan.dialect.DialectProvider;
import io.github.thirtyeighttwentysix.volan.dialect.DdlRenderer;
import io.github.thirtyeighttwentysix.volan.migrate.DatabaseSchema;
import io.github.thirtyeighttwentysix.volan.migrate.SchemaDiffer;
import io.github.thirtyeighttwentysix.volan.migrate.SchemaMapper;
import io.github.thirtyeighttwentysix.volan.runtime.VolanUniqueConstraintException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "volan.h2", matches = "true")
class PublishedH2Test {
    @Test
    void h2ProviderIsDiscoveredAndReturnsWrittenRowsFromThePublishedJar() throws Exception {
        var schema = Files.readString(Path.of("schema.volan")).replace("postgresql", "h2");
        var model = SchemaLoader.load("h2.volan", schema).schemaOrThrow();
        var url = "jdbc:h2:mem:" + UUID.randomUUID();
        var dialect = (DdlRenderer) ServiceLoader.load(DialectProvider.class).stream()
                .map(ServiceLoader.Provider::get).filter(provider -> provider.supports(url))
                .findFirst().orElseThrow().dialect();
        var statements = SchemaDiffer.diff(new DatabaseSchema(), SchemaMapper.map(model)).render(dialect);
        try (var client = VolanClient.builder().url(url).maxPoolSize(4).build()) {
            for (var statement : statements) client.rawExecute(statement);
            var user = client.getUser().create(d -> d.setEmail("h2@example.org"));
            assertNull(user.getName());
            assertEquals(user, client.getUser().findFirstAsync().get(10, TimeUnit.SECONDS));
            var changed = client.getUser().update(d -> {
                d.where(w -> w.getId().eq(user.getId()));
                d.data(v -> v.setName("H2"));
            });
            assertEquals("H2", changed.getName());
            assertThrows(VolanUniqueConstraintException.class,
                    () -> client.getUser().create(d -> d.setEmail(user.getEmail())));
            assertThrows(IllegalStateException.class, () -> client.transaction(tx -> {
                tx.getUser().create(d -> d.setEmail("rollback@example.org"));
                throw new IllegalStateException("rollback");
            }));
            assertEquals(1, client.getUser().count());
            assertEquals(changed, client.getUser().delete(d -> d.where(w -> w.getId().eq(user.getId()))));
            assertEquals(0, client.getUser().count());
        }
    }
}
