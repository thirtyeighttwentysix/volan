package io.github.thirtyeighttwentysix.volan.dialect

/**
 * Announces a dialect to whatever is on the classpath.
 *
 * The runtime finds dialects through `ServiceLoader` rather than by naming them, which is what keeps
 * it from depending on any particular database. Adding PostgreSQL support to an application is adding
 * `volan-dialect-postgres` to its dependencies; nothing else changes.
 */
public interface DialectProvider {
    /** Whether this provider handles [jdbcUrl], for example one starting `jdbc:postgresql:`. */
    public fun supports(jdbcUrl: String): Boolean

    /** The dialect itself. */
    public fun dialect(): Dialect
}
