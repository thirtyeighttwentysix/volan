package io.github.thirtyeighttwentysix.volan.runtime

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.thirtyeighttwentysix.volan.dialect.Dialect
import io.github.thirtyeighttwentysix.volan.dialect.DialectProvider
import java.time.Clock
import java.util.ServiceLoader
import java.util.function.Function

/**
 * A connected database: a pool, a dialect, and the executor generated clients run through.
 *
 * One instance is meant to live as long as the application does. It is thread-safe, and a transaction
 * belongs to the thread that opened it.
 */
public class Volan internal constructor(
    private val dataSource: HikariDataSource,
    private val connections: ConnectionSource,
    private val registry: TableRegistry,
    /** The dialect in use, which is decided by the JDBC URL. */
    public val dialect: Dialect,
    clock: Clock,
) : AutoCloseable {
    /** What generated repositories run their descriptions through. */
    public val executor: QueryExecutor = JdbcExecutor(connections, QueryPlanner(registry, dialect), dialect, clock)

    /** What the runtime knows about the models of this schema. */
    public val tables: TableRegistry get() = registry

    /**
     * Runs [block] in one transaction, committing when it returns and rolling back when it throws.
     *
     * A transaction inside a transaction becomes a savepoint, so a nested block can fail without
     * taking the outer one down with it.
     *
     * @param isolation how much the transaction is protected from concurrent work.
     * @param retry whether to run the block again when the database asks two transactions to sort out
     *   an order between them. Only use it for a block that is safe to run twice.
     */
    @JvmOverloads
    public fun <T> transaction(
        isolation: Isolation = Isolation.DEFAULT,
        retry: RetryPolicy = RetryPolicy.NONE,
        block: Function<QueryExecutor, T>,
    ): T = connections.transaction(isolation, retry) { block.apply(executor) }

    /**
     * Runs a statement Volan did not build, and maps what comes back.
     *
     * The text is yours; the values are still bound as parameters, which is the only way this stays as
     * safe as everything Volan generates. Inside a transaction it runs on that transaction's connection.
     *
     * ```kotlin
     * val ids = db.rawQuery("select id from users where email = ?", listOf(email)) { it.getInt("id") }
     * ```
     */
    public fun <T> rawQuery(sql: String, parameters: List<Any?>, mapper: RowMapper<T>): List<T> =
        (executor as JdbcExecutor).rawQuery(sql, parameters, mapper)

    /**
     * Runs a statement Volan did not build, returning how many rows it changed.
     *
     * As with [rawQuery], values belong in [parameters] rather than in [sql].
     */
    public fun rawExecute(sql: String, parameters: List<Any?> = emptyList()): Long = (executor as JdbcExecutor).rawExecute(sql, parameters)

    /** Closes the pool. Statements in flight on other threads will fail. */
    override fun close() {
        dataSource.close()
    }

    override fun toString(): String = "Volan(${dialect.id}, ${registry.models.size} models)"

    public companion object {
        /** Starts configuring a connection. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }

    /**
     * Configures a [Volan].
     *
     * A URL and the schema's tables are required; everything else has a working default. Generated
     * clients fill the tables in themselves, so applications only ever set the connection details.
     */
    public class Builder internal constructor() {
        private var url: String? = null
        private var username: String? = null
        private var password: String? = null
        private var maxPoolSize: Int = DEFAULT_POOL_SIZE
        private var connectionTimeout: Long = DEFAULT_CONNECTION_TIMEOUT
        private var poolName: String = "volan"
        private var tables: List<TableMetadata> = emptyList()
        private var dialect: Dialect? = null
        private var clock: Clock = Clock.systemUTC()

        /** The JDBC URL to connect to. It also decides which dialect is used. */
        public fun url(url: String): Builder = apply { this.url = url }

        /** The user to connect as, when the URL does not carry it. */
        public fun username(username: String?): Builder = apply { this.username = username }

        /** The password to connect with, when the URL does not carry it. */
        public fun password(password: String?): Builder = apply { this.password = password }

        /** How many connections the pool may open. */
        public fun maxPoolSize(size: Int): Builder = apply { this.maxPoolSize = size }

        /** How long to wait for a connection from the pool, in milliseconds. */
        public fun connectionTimeout(millis: Long): Builder = apply { this.connectionTimeout = millis }

        /** The name the pool reports itself under, which is what shows up in metrics and thread names. */
        public fun poolName(name: String): Builder = apply { this.poolName = name }

        /** The models of the schema. Generated clients pass their own. */
        public fun tables(tables: List<TableMetadata>): Builder = apply { this.tables = tables.toList() }

        /** Overrides the dialect the URL would have chosen. */
        public fun dialect(dialect: Dialect): Builder = apply { this.dialect = dialect }

        /** The clock `@updatedAt` columns are written from. Tests set this to make time stand still. */
        public fun clock(clock: Clock): Builder = apply { this.clock = clock }

        /**
         * Opens the pool and returns a connected [Volan].
         *
         * @throws VolanConfigurationException if the URL is missing, or if nothing on the classpath
         *   knows how to talk to it.
         */
        public fun build(): Volan {
            val jdbcUrl = url ?: throw VolanConfigurationException(
                "no database URL was given.\n  Set one with `url(…)`, reading it from the environment as the schema does.",
            )
            val resolved = dialect ?: discover(jdbcUrl)
            val configuration = HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                this.username = this@Builder.username
                this.password = this@Builder.password
                this.maximumPoolSize = maxPoolSize
                this.connectionTimeout = this@Builder.connectionTimeout
                this.poolName = this@Builder.poolName
            }
            val dataSource = HikariDataSource(configuration)
            return Volan(dataSource, ConnectionSource(dataSource), TableRegistry(tables), resolved, clock)
        }

        /**
         * Finds the dialect for [jdbcUrl] among the ones on the classpath.
         *
         * Volan deliberately does not name any database itself: adding support for one is adding its
         * module to the build.
         */
        private fun discover(jdbcUrl: String): Dialect {
            val providers = ServiceLoader.load(DialectProvider::class.java, Volan::class.java.classLoader).toList()
            providers.firstOrNull { it.supports(jdbcUrl) }?.let { return it.dialect() }
            val available = providers.map { it.dialect().id }.sorted()
            throw VolanConfigurationException(
                "nothing on the classpath knows how to talk to `$jdbcUrl`.\n" +
                    if (available.isEmpty()) {
                        "  No Volan dialect is on the classpath at all; add one, such as `volan-dialect-postgres`."
                    } else {
                        "  The dialects available are ${available.joinToString(", ")}."
                    },
            )
        }

        private companion object {
            private const val DEFAULT_POOL_SIZE = 10
            private const val DEFAULT_CONNECTION_TIMEOUT = 30_000L
        }
    }
}
