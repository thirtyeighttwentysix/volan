package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.ColumnDefault
import io.github.thirtyeighttwentysix.volan.dialect.ColumnDefinition
import io.github.thirtyeighttwentysix.volan.dialect.ColumnType
import io.github.thirtyeighttwentysix.volan.dialect.ForeignKeyAction
import io.github.thirtyeighttwentysix.volan.dialect.ForeignKeyDefinition
import io.github.thirtyeighttwentysix.volan.dialect.PrimaryKeyDefinition
import io.github.thirtyeighttwentysix.volan.dialect.SqlType
import io.github.thirtyeighttwentysix.volan.dialect.UniqueDefinition

/** Reads the declarations themselves: PRAGMA does not retain constraint names or logical types. */
internal class SqliteDeclarations(private val table: String) {
    private val columns = ArrayList<ColumnDefinition>()
    private val uniques = ArrayList<UniqueDefinition>()
    private val keys = ArrayList<ForeignKeyDefinition>()
    private var primary: PrimaryKeyDefinition? = null

    fun read(sql: String): TableDefinition {
        val tokens = SqliteSql.tokens(sql)
        val prefix = SqliteCursor(tokens)
        prefix.expect("CREATE")
        prefix.expect("TABLE")
        if (prefix.accept("IF")) {
            prefix.expect("NOT")
            prefix.expect("EXISTS")
        }
        if (prefix.take().value != table) SqliteSql.unsupported("table declaration name differs from $table")
        val rest = prefix.remaining()
        if (rest.firstOrNull()?.text != "(" || rest.lastOrNull()?.text != ")") {
            SqliteSql.unsupported("table options or a nonstandard CREATE TABLE on $table")
        }
        SqliteSql.groups(rest.drop(1).dropLast(1)).forEach { declaration(it) }
        if (columns.isEmpty()) SqliteSql.unsupported("table $table has no columns")
        return TableDefinition(table, columns, primary, uniques.sortedBy { it.name }, foreignKeys = keys.sortedBy { it.name })
    }

    private fun declaration(tokens: List<SqliteToken>) {
        val cursor = SqliteCursor(tokens)
        val named = if (cursor.accept("CONSTRAINT")) cursor.take().value else null
        when {
            cursor.accept("PRIMARY") -> primary(cursor, named)
            cursor.accept("UNIQUE") -> unique(cursor, named)
            cursor.accept("FOREIGN") -> foreign(cursor, named)
            named != null -> SqliteSql.unsupported("constraint $named on $table")
            else -> column(tokens)
        }
        if (named != null && !cursor.done) SqliteSql.unsupported("extra constraint clauses on $table")
    }

    private fun primary(cursor: SqliteCursor, name: String?) {
        cursor.expect("KEY")
        if (primary != null) SqliteSql.unsupported("multiple primary keys on $table")
        primary = PrimaryKeyDefinition(name ?: "${table}_pkey", cursor.names())
        if (!cursor.done) SqliteSql.unsupported("primary key options on $table")
    }

    private fun unique(cursor: SqliteCursor, name: String?) {
        val names = cursor.names()
        uniques += UniqueDefinition(name ?: conventional(names, "key"), names)
        if (!cursor.done) SqliteSql.unsupported("unique constraint options on $table")
    }

    private fun foreign(cursor: SqliteCursor, name: String?) {
        cursor.expect("KEY")
        val names = cursor.names()
        cursor.expect("REFERENCES")
        val target = cursor.take().value
        val references = cursor.names()
        var delete = ForeignKeyAction.NO_ACTION
        var update = ForeignKeyAction.NO_ACTION
        while (!cursor.done) {
            cursor.expect("ON")
            when {
                cursor.accept("DELETE") -> delete = action(cursor)
                cursor.accept("UPDATE") -> update = action(cursor)
                else -> SqliteSql.unsupported("foreign key action on $table")
            }
        }
        keys += ForeignKeyDefinition(name ?: conventional(names, "fkey"), names, target, references, delete, update)
    }

    private fun action(cursor: SqliteCursor): ForeignKeyAction = when {
        cursor.accept("CASCADE") -> ForeignKeyAction.CASCADE
        cursor.accept("RESTRICT") -> ForeignKeyAction.RESTRICT
        cursor.accept("NO") -> {
            cursor.expect("ACTION")
            ForeignKeyAction.NO_ACTION
        }
        cursor.accept("SET") -> when {
            cursor.accept("NULL") -> ForeignKeyAction.SET_NULL
            cursor.accept("DEFAULT") -> ForeignKeyAction.SET_DEFAULT
            else -> SqliteSql.unsupported("unknown SET action on $table")
        }
        else -> SqliteSql.unsupported("unknown foreign key action on $table")
    }

    private fun column(tokens: List<SqliteToken>) {
        val name = tokens.firstOrNull()?.value ?: SqliteSql.unsupported("empty column declaration on $table")
        val typeEnd = tokens.indexOfFirst { token -> COLUMN_CLAUSES.any { token.keyword(it) } }
            .takeIf { it >= 0 } ?: tokens.size
        val typeTokens = tokens.subList(1, typeEnd)
        val long = typeTokens.any { it.value == "volan:Long" }
        val declared = typeTokens.filterNot { it.value == "volan:Long" }.joinToString(" ") { it.value }
        val type = if (long && declared.equals("INTEGER", ignoreCase = true)) {
            ColumnType.Scalar(SqlType.BIGINT)
        } else {
            SqliteTypes.read(declared, null, null, null)
        }
        val cursor = SqliteCursor(tokens.drop(typeEnd))
        var nullable = true
        var automatic = false
        var default: ColumnDefault? = null
        while (!cursor.done) {
            val constraint = if (cursor.accept("CONSTRAINT")) cursor.take().value else null
            when {
                cursor.accept("NOT") -> {
                    cursor.expect("NULL")
                    nullable = false
                }
                cursor.accept("NULL") -> nullable = true
                cursor.accept("PRIMARY") -> {
                    cursor.expect("KEY")
                    if (primary != null) SqliteSql.unsupported("multiple primary keys on $table")
                    primary = PrimaryKeyDefinition(constraint ?: "${table}_pkey", listOf(name))
                    automatic = cursor.accept("AUTOINCREMENT")
                    // Volan emits NOT NULL. An INTEGER rowid alias is non-null even without it.
                    if (declared.equals("INTEGER", ignoreCase = true)) nullable = false
                }
                cursor.accept("UNIQUE") -> uniques += UniqueDefinition(constraint ?: conventional(listOf(name), "key"), listOf(name))
                cursor.accept("DEFAULT") -> default = SqliteDefaults.read(cursor.remaining(), type)
                else -> SqliteSql.unsupported("column options on $table.$name")
            }
        }
        columns += ColumnDefinition(name, type, nullable, default, automatic)
    }

    private fun conventional(names: List<String>, suffix: String): String = (listOf(table) + names + suffix).joinToString("_")

    private companion object {
        val COLUMN_CLAUSES =
            listOf("NOT", "NULL", "DEFAULT", "CONSTRAINT", "PRIMARY", "UNIQUE", "REFERENCES", "CHECK", "COLLATE", "GENERATED")
    }
}

internal object SqliteDefaults {
    private const val NOW = "strftime('%Y-%m-%dT%H:%M:%f000000Z', 'now')"
    private const val UUID =
        "lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)), 2) || '-' || " +
            "substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6)))"
    private val numeric = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

    fun read(input: List<SqliteToken>, type: ColumnType): ColumnDefault {
        if (input.isEmpty()) SqliteSql.unsupported("empty default expression")
        validate(input)
        val tokens = if (input.first().text == "(" && input.last().text == ")") input.drop(1).dropLast(1) else input
        val single = tokens.singleOrNull()
        if (single?.literal == true) return ColumnDefault.Text(single.value)
        if (single?.keyword("TRUE") == true || single?.keyword("FALSE") == true) {
            return ColumnDefault.Boolean(single.keyword("TRUE"))
        }
        val number = tokens.joinToString("") { it.text }
        if (numeric.matches(number)) return number(number, type)
        val expression = SqliteSql.normalize(tokens.joinToString(" ") { it.text })
        return when (expression) {
            SqliteSql.normalize(NOW) -> ColumnDefault.CurrentTimestamp
            SqliteSql.normalize(UUID) -> ColumnDefault.GeneratedUuid
            else -> ColumnDefault.Expression(expression)
        }
    }

    private fun validate(input: List<SqliteToken>) {
        var depth = 0
        input.forEach {
            if (depth == 0 && listOf("NOT", "CONSTRAINT", "PRIMARY", "UNIQUE", "REFERENCES", "CHECK", "COLLATE").any(it::keyword)) {
                SqliteSql.unsupported("column constraints after a default")
            }
            if (it.text == "(") depth++
            if (it.text == ")") depth--
        }
        SqliteSql.groups(input)
    }

    private fun number(number: String, type: ColumnType): ColumnDefault =
        if (type == ColumnType.Scalar(SqlType.BOOLEAN) && number in listOf("0", "1")) {
            ColumnDefault.Boolean(number == "1")
        } else {
            ColumnDefault.Number(number)
        }
}
