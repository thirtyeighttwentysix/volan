package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.ir.Provider
import java.sql.Connection

/**
 * Reads a database as the schema it holds.
 *
 * The other half of a migration: where [SchemaMapper] says what a schema wants, this says what a
 * database has. Both produce a [DatabaseSchema], so what is left is a comparison.
 */
public interface DatabaseReader {
    /** What [connection]'s database holds, in its current schema. */
    public fun read(connection: Connection): DatabaseSchema

    public companion object {
        /**
         * The reader for [provider].
         *
         * @throws VolanMigrationException for a provider whose dialect has not landed yet.
         */
        @JvmStatic
        public fun forProvider(provider: Provider): DatabaseReader = when (provider) {
            Provider.POSTGRESQL -> PostgresReader()
            else -> throw VolanMigrationException(
                "Volan cannot yet read a ${provider.id} database back, so it cannot tell what one holds.\n" +
                    "  The other dialects, and the introspection that comes with them, arrive in M8.",
            )
        }
    }
}
