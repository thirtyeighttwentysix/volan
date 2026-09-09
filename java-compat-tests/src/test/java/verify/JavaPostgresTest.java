package verify;

import com.example.blog.*;
import io.github.thirtyeighttwentysix.volan.runtime.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("dockerAvailable")
class JavaPostgresTest {
    private PostgreSQLContainer database;
    private VolanClient client;
    private ExecutorService worker;

    static boolean dockerAvailable() { return DockerClientFactory.instance().isDockerAvailable(); }

    @BeforeAll
    void start() {
        database = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
        database.start();
        worker = Executors.newFixedThreadPool(2);
        client = VolanClient.builder().url(database.getJdbcUrl())
                .username(database.getUsername()).password(database.getPassword())
                .asyncExecutor(worker).maxPoolSize(3).build();
    }

    @AfterAll
    void stop() {
        if (client != null) client.close();
        if (worker != null) worker.shutdownNow();
        if (database != null) database.stop();
    }

    @BeforeEach
    void reset() throws Exception {
        try (var input = getClass().getResourceAsStream("/blog-schema.sql");
             var connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("drop schema public cascade; create schema public");
            statement.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void crudFiltersProjectionsAndDefaultOverloads() {
        User alice = client.getUser().create(data -> {
            data.setEmail("alice@example.org");
            data.setName(null);
            data.setRole(Role.ADMIN);
        });
        assertNull(alice.getName());
        assertEquals(Role.ADMIN, alice.getRole());
        assertEquals(1, client.getUser().count());
        assertTrue(client.getUser().exists());
        assertEquals(alice, client.getUser().findFirst());
        assertEquals(alice, client.getUser().findUnique(q -> q.where(w -> w.getId().eq(alice.getId()))));
        var projection = client.getUser().projectFirst(q -> {
            q.where(w -> {
                w.or(or -> { or.getEmail().endsWith("@example.org"); or.getName().isNull(); });
                w.not(not -> not.getRole().eq(Role.USER));
            });
            q.orderBy(o -> o.getId().desc());
            q.setTake(1);
            q.select(s -> { s.email(); s.name(); });
        });
        assertEquals(alice.getEmail(), projection.getEmail());
        assertNull(projection.getName());
        assertThrows(VolanFieldNotSelectedException.class, projection::getId);
        User updated = client.getUser().update(u -> {
            u.where(w -> w.getId().eq(alice.getId()));
            u.data(d -> d.setName("Alice"));
        });
        assertEquals("Alice", updated.getName());
        assertEquals(1, client.getUser().updateMany(u -> {
            u.where(w -> w.getRole().eq(Role.ADMIN));
            u.data(d -> d.setName(null));
        }));
        User upserted = client.getUser().upsert(u -> {
            u.where(w -> w.getEmail().eq("bob@example.org"));
            u.create(d -> d.setEmail("bob@example.org"));
            u.update(d -> d.setName("Bob"));
        });
        assertEquals("bob@example.org", upserted.getEmail());
        assertEquals(2, client.getUser().findMany().size());
        assertEquals(alice.getId(), client.getUser().delete(d -> d.where(w -> w.getId().eq(alice.getId()))).getId());
        assertEquals(1, client.getUser().deleteMany(d -> d.where(w -> w.getId().eq(upserted.getId()))));
        assertNull(client.getUser().findFirst());
        assertThrows(VolanNotFoundException.class, () -> client.getUser().findFirstOrThrow());
        assertThrows(VolanNotFoundException.class, () -> client.getUser().findUniqueOrThrow());
    }

    @Test
    void nestedWritesIncludesCompositeKeysAndSummaries() {
        User alice = client.getUser().create(d -> {
            d.setEmail("alice@example.org");
            d.getPosts().create(post -> {
                post.setTitle("Java API");
                post.setViews(7);
                post.getTags().create(tag -> tag.setName("java"));
            });
            d.getProfile().create(p -> p.setBio("Java developer"));
        });
        User loaded = client.getUser().findFirstOrThrow(q -> {
            q.where(w -> w.posts(p -> p.some(post -> post.getTitle().contains("Java"))));
            q.include(i -> { i.posts(p -> p.include(pi -> pi.tags())); i.profile(); });
        });
        Post post = loaded.getPosts().get(0);
        assertEquals("java", post.getTags().get(0).getName());
        assertEquals("Java developer", loaded.getProfile().getBio());
        client.getComment().create(c -> { c.setPostId(post.getId()); c.setAuthorId(alice.getId()); c.setBody("Works"); });
        assertEquals(1, client.getComment().findMany(q -> q.cursor(post.getId(), alice.getId(), true)).size());
        assertEquals(0, client.getComment().findMany(q -> q.cursor(post.getId(), alice.getId())).size());
        var summary = client.getPost().aggregate(a -> { a.count(); a.sum(s -> s.views()); a.average(s -> s.views()); });
        assertEquals(1, summary.getCount());
        assertEquals(7, summary.getSumOfViews().intValue());
        var groups = client.getPost().groupBy(g -> {
            g.by(b -> b.authorId());
            g.count();
            g.having(h -> h.getCount().gt(0L));
        });
        assertEquals(alice.getId(), groups.get(0).getAuthorId());
        assertEquals(1, groups.get(0).getCount());
        assertEquals(2, client.getTag().createMany(m -> {
            m.row(d -> d.setName("orm"));
            m.row(d -> d.setName("jvm"));
        }));
        assertEquals(3, client.getTag().projectMany(q -> { q.select(s -> s.name()); q.distinct(d -> d.name()); }).size());
    }

    @Test
    void asyncCrudAndRawSql() throws Exception {
        User user = client.getUser().createAsync(d -> d.setEmail("async@example.org")).get(10, TimeUnit.SECONDS);
        var duplicate = assertThrows(ExecutionException.class,
                () -> client.getUser().createAsync(d -> d.setEmail(user.getEmail())).get(10, TimeUnit.SECONDS));
        assertInstanceOf(VolanUniqueConstraintException.class, duplicate.getCause());
        assertEquals(user, client.getUser().findUniqueAsync(q -> q.where(w -> w.getId().eq(user.getId()))).get(10, TimeUnit.SECONDS));
        assertEquals(1, client.getUser().countAsync().get(10, TimeUnit.SECONDS));
        assertTrue(client.getUser().existsAsync().get(10, TimeUnit.SECONDS));
        assertEquals(List.of(user.getId()), client.rawQueryAsync("select id from users where email = ?",
                List.of(user.getEmail()), row -> row.getInt("id")).get(10, TimeUnit.SECONDS));
        assertEquals(1, client.rawExecuteAsync("update users set name = 'Async'").get(10, TimeUnit.SECONDS));
        assertEquals("Async", client.getUser().findFirstOrThrowAsync().get(10, TimeUnit.SECONDS).getName());
        client.getUser().deleteAsync(d -> d.where(w -> w.getId().eq(user.getId()))).get(10, TimeUnit.SECONDS);
        assertNull(client.getUser().findFirstAsync().get(10, TimeUnit.SECONDS));
    }

    @Test
    void asyncTransactionsCommitRollbackAndRejectEscapingWork() throws Exception {
        int id = client.transactionAsync(Isolation.READ_COMMITTED, tx -> {
            User user = tx.getUser().create(d -> d.setEmail("tx@example.org"));
            assertEquals(1, tx.getUser().count());
            var failure = assertThrows(CompletionException.class, () -> tx.getUser().countAsync().join());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertThrows(IllegalArgumentException.class, () -> tx.transaction(nested -> {
                nested.getTag().create(d -> d.setName("rolled back savepoint"));
                throw new IllegalArgumentException("rollback nested");
            }));
            assertEquals(0, tx.getTag().count());
            return user.getId();
        }).get(10, TimeUnit.SECONDS);
        assertEquals(id, client.getUser().findFirstOrThrow().getId());
        var failure = assertThrows(CompletionException.class, () -> client.transactionAsync(tx -> {
            tx.getUser().create(d -> d.setEmail("rollback@example.org"));
            throw new IllegalArgumentException("rollback outer");
        }).join());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals(1, client.getUser().count());
    }
}
