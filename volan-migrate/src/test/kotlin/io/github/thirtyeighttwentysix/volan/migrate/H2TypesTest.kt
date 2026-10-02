package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.ColumnType
import io.github.thirtyeighttwentysix.volan.dialect.SqlType
import io.github.thirtyeighttwentysix.volan.ir.NativeType
import io.github.thirtyeighttwentysix.volan.ir.Provider
import io.github.thirtyeighttwentysix.volan.ir.ScalarType
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.DriverManager
import java.util.UUID

class H2TypesTest {
    @Test
    fun `standard H2 storage declarations preserve scalar distinctions`() {
        val declarations = listOf(
            "CHARACTER VARYING", "INTEGER", "BIGINT", "REAL", "DOUBLE PRECISION", "NUMERIC", "BOOLEAN",
            "TIMESTAMP WITH TIME ZONE", "DATE", "TIME", "JSON", "UUID", "BINARY VARYING",
        )
        val scalars = listOf(
            SqlType.TEXT, SqlType.INTEGER, SqlType.BIGINT, SqlType.REAL, SqlType.DOUBLE, SqlType.NUMERIC, SqlType.BOOLEAN,
            SqlType.TIMESTAMP, SqlType.DATE, SqlType.TIME, SqlType.JSON, SqlType.UUID, SqlType.BLOB,
        )
        declarations.zip(scalars).forEach { (declaration, scalar) ->
            H2Types.read(declaration.lowercase(), null, null, null) shouldBe ColumnType.Scalar(scalar)
        }
        H2Types.read("NUMERIC", null, 65, 30) shouldBe ColumnType.Scalar(SqlType.NUMERIC)
        H2Types.read("TIME", null, 9, null) shouldBe ColumnType.Scalar(SqlType.TIME)
        H2Types.read("CHARACTER VARYING", 1_000_000_000, null, null) shouldBe ColumnType.Scalar(SqlType.TEXT)
        NativeTypeTable.forProvider(Provider.H2) shouldBe H2Types
    }

    @Test
    fun `unsupported native shapes are not silently mapped to default storage`() {
        assertThrows<VolanMigrationException> { H2Types.read("CHARACTER VARYING", 10, null, null) }
        assertThrows<VolanMigrationException> { H2Types.read("NUMERIC", null, 5, 30) }
        assertThrows<VolanMigrationException> { H2Types.read("NUMERIC", null, 65, 2) }
        assertThrows<VolanMigrationException> { H2Types.read("TIME", null, 3, null) }
        assertThrows<VolanMigrationException> { H2Types.read("GEOMETRY", null, null, null) }
        assertThrows<VolanMigrationException> { H2Types.canonical(ScalarType.STRING, NativeType("VarChar", listOf("10"))) }
    }

    @Test
    fun `H2 schemas store mapped enums and enum arrays as text`() {
        val schema = SchemaLoader.load(
            "h2.volan",
            """
            datasource db {
              provider = "h2"
              url = env("DATABASE_URL")
            }
            enum Role {
              USER @map("member")
            }
            model Item {
              id Int @id
              role Role @default(USER)
              roles Role[] @default([])
            }
            """.trimIndent(),
        ).schemaOrThrow()
        val database = SchemaMapper.map(schema)
        database.enums shouldBe emptyList()
        database.tables.single().columns[1].type shouldBe ColumnType.Scalar(SqlType.TEXT)
        database.tables.single().columns[2].type shouldBe ColumnType.Array(ColumnType.Scalar(SqlType.TEXT))
        (DatabaseReader.forProvider(Provider.H2) is H2Reader) shouldBe true
    }
}
