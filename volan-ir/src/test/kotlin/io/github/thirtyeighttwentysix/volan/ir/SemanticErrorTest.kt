package io.github.thirtyeighttwentysix.volan.ir

import io.github.thirtyeighttwentysix.volan.schema.DiagnosticCode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Every case is a schema that parses cleanly but describes a model that cannot exist. As with the
 * parser, each asserts the code, the message and the suggestion, because the suggestion is usually
 * the whole fix.
 */
class SemanticErrorTest {
    private data class Case(
        val name: String,
        val schema: String,
        val code: DiagnosticCode,
        val message: String,
        val help: String? = null,
        val withDatasource: Boolean = true,
    )

    private val cases = listOf(
        Case(
            name = "unknown field type",
            schema = "model User {\n  id Int @id\n  name Strng\n}\n",
            code = SemanticCode.UNKNOWN_TYPE,
            message = "unknown type `Strng`",
            help = "did you mean `String`?",
        ),
        Case(
            name = "unknown type with no close match",
            schema = "model User {\n  id Int @id\n  address Address\n}\n",
            code = SemanticCode.UNKNOWN_TYPE,
            message = "unknown type `Address`",
            help = "declare a `model Address { … }`",
        ),
        Case(
            name = "two declarations share a name",
            schema = "model User {\n  id Int @id\n}\n\nenum User {\n  A\n}\n",
            code = SemanticCode.DUPLICATE_DECLARATION,
            message = "`User` is declared twice",
            help = "models and enums share one namespace",
        ),
        Case(
            name = "duplicate field",
            schema = "model User {\n  id Int @id\n  name String\n  name String\n}\n",
            code = SemanticCode.DUPLICATE_MEMBER,
            message = "`User` already has a field called `name`",
        ),
        Case(
            name = "duplicate enum value",
            schema = "enum Role {\n  USER\n  USER\n}\n\nmodel User {\n  id Int @id\n  role Role\n}\n",
            code = SemanticCode.DUPLICATE_MEMBER,
            message = "`Role` already has a value called `USER`",
        ),
        Case(
            name = "no datasource",
            schema = "model User {\n  id Int @id\n}\n",
            code = SemanticCode.MISSING_DATASOURCE,
            message = "the schema has no `datasource` block",
            withDatasource = false,
        ),
        Case(
            name = "two datasources",
            schema = "datasource other {\n  provider = \"sqlite\"\n  url = \"file:./dev.db\"\n}\n\n" +
                "model User {\n  id Int @id\n}\n",
            code = SemanticCode.DUPLICATE_DATASOURCE,
            message = "a schema may declare only one `datasource`",
        ),
        Case(
            name = "unknown provider",
            schema = "datasource db {\n  provider = \"postgres\"\n  url = env(\"DATABASE_URL\")\n}\n\n" +
                "model User {\n  id Int @id\n}\n",
            code = SemanticCode.UNKNOWN_PROVIDER,
            message = "`postgres` is not a database Volan supports",
            help = "did you mean `postgresql`?",
            withDatasource = false,
        ),
        Case(
            name = "unknown datasource property",
            schema = "datasource db {\n  provider = \"postgresql\"\n  url = env(\"DATABASE_URL\")\n  shadowUrl = \"x\"\n}\n\n" +
                "model User {\n  id Int @id\n}\n",
            code = SemanticCode.UNKNOWN_OPTION,
            message = "a `datasource` block has no property `shadowUrl`",
            withDatasource = false,
        ),
        Case(
            name = "datasource without a url",
            schema = "datasource db {\n  provider = \"postgresql\"\n}\n\nmodel User {\n  id Int @id\n}\n",
            code = SemanticCode.MISSING_OPTION,
            message = "this `datasource` block does not set `url`",
            withDatasource = false,
        ),
        Case(
            name = "generator without a package",
            schema = "generator client {\n  provider = \"volan-kotlin\"\n}\n\nmodel User {\n  id Int @id\n}\n",
            code = SemanticCode.MISSING_OPTION,
            message = "this `generator` block does not set `package`",
        ),
        Case(
            name = "unknown generator",
            schema = "generator client {\n  provider = \"prisma-client-js\"\n  package = \"com.example\"\n}\n\n" +
                "model User {\n  id Int @id\n}\n",
            code = SemanticCode.INVALID_OPTION_VALUE,
            message = "`prisma-client-js` is not a generator Volan knows",
        ),
        Case(
            name = "model without a primary key",
            schema = "model User {\n  email String\n}\n",
            code = SemanticCode.MISSING_PRIMARY_KEY,
            message = "`User` has no primary key",
            help = "add `@id` to a field",
        ),
        Case(
            name = "two id fields",
            schema = "model User {\n  id Int @id\n  code String @id\n}\n",
            code = SemanticCode.DUPLICATE_PRIMARY_KEY,
            message = "`User` declares more than one `@id` field",
            help = "@@id([id, code])",
        ),
        Case(
            name = "id declared on a field and on the block",
            schema = "model User {\n  id Int @id\n  code String\n\n  @@id([id, code])\n}\n",
            code = SemanticCode.DUPLICATE_PRIMARY_KEY,
            message = "`User` declares its primary key twice",
        ),
        Case(
            name = "optional primary key",
            schema = "model User {\n  id Int? @id\n}\n",
            code = SemanticCode.INVALID_ID_FIELD,
            message = "`id` cannot be the primary key",
            help = "remove the `?`",
        ),
        Case(
            name = "unique on a list",
            schema = "model User {\n  id Int @id\n  tags String[] @unique\n}\n",
            code = SemanticCode.INVALID_UNIQUE_FIELD,
            message = "`tags` holds many values, so it cannot be unique",
        ),
        Case(
            name = "updatedAt on a number",
            schema = "model User {\n  id Int @id\n  version Int @updatedAt\n}\n",
            code = SemanticCode.INVALID_UPDATED_AT,
            message = "`@updatedAt` needs a single `DateTime` field",
        ),
        Case(
            name = "unknown attribute",
            schema = "model User {\n  id Int @id\n  email String @uniqe\n}\n",
            code = SemanticCode.UNKNOWN_ATTRIBUTE,
            message = "`@uniqe` cannot be used on `email`",
            help = "did you mean `@unique`?",
        ),
        Case(
            name = "unknown attribute namespace",
            schema = "model User {\n  id Int @id\n  name String @pg.Text\n}\n",
            code = SemanticCode.UNKNOWN_ATTRIBUTE_NAMESPACE,
            message = "`@pg.Text` is not an attribute Volan knows",
            help = "native database types are written on a field as `@db.VarChar(200)`",
        ),
        Case(
            name = "unknown attribute argument",
            schema = "model User {\n  id Int @id(name: \"pk\")\n}\n",
            code = SemanticCode.UNKNOWN_ATTRIBUTE_ARGUMENT,
            message = "`@id` has no argument `name`",
            help = "it accepts `map`",
        ),
        Case(
            name = "attribute applied twice",
            schema = "model User {\n  id Int @id\n  email String @unique @unique\n}\n",
            code = SemanticCode.DUPLICATE_ATTRIBUTE,
            message = "`@unique` is applied twice",
        ),
        Case(
            name = "default of the wrong type",
            schema = "model User {\n  id Int @id\n  name String @default(1)\n}\n",
            code = SemanticCode.INVALID_DEFAULT,
            message = "a number cannot be the default of a `String` field",
        ),
        Case(
            name = "default naming a value the enum does not have",
            schema = "enum Role {\n  USER\n  ADMIN\n}\n\nmodel User {\n  id Int @id\n  role Role @default(ADMN)\n}\n",
            code = SemanticCode.INVALID_DEFAULT,
            message = "`Role` has no value `ADMN`",
            help = "did you mean `ADMIN`?",
        ),
        Case(
            name = "autoincrement on a string",
            schema = "model User {\n  id String @id @default(autoincrement())\n}\n",
            code = SemanticCode.INVALID_DEFAULT,
            message = "`autoincrement()` cannot be the default of a `String` field",
        ),
        Case(
            name = "unknown default function",
            schema = "model User {\n  id Int @id\n  createdAt DateTime @default(nwo())\n}\n",
            code = SemanticCode.INVALID_DEFAULT,
            message = "`nwo()` is not a default Volan can produce",
            help = "did you mean `now()`?",
        ),
        Case(
            name = "literal default on a list",
            schema = "model User {\n  id Int @id\n  tags String[] @default(\"a\")\n}\n",
            code = SemanticCode.INVALID_DEFAULT,
            message = "a list field can only default to the empty list",
        ),
        Case(
            name = "index over a field that does not exist",
            schema = "model User {\n  id Int @id\n\n  @@index([emial])\n}\n",
            code = SemanticCode.UNKNOWN_FIELD_REFERENCE,
            message = "`@@index` refers to `emial`, which is not a field of this model",
        ),
        Case(
            name = "index listing the same field twice",
            schema = "model User {\n  id Int @id\n  email String\n\n  @@index([email, email])\n}\n",
            code = SemanticCode.DUPLICATE_MEMBER,
            message = "`@@index` lists `email` twice",
        ),
        Case(
            name = "relation with no field on the other side",
            schema = "model User {\n  id Int @id\n  posts Post[]\n}\n\nmodel Post {\n  id Int @id\n}\n",
            code = SemanticCode.RELATION_MISSING_OPPOSITE,
            message = "`Post` has no field pointing back at `User`",
        ),
        Case(
            name = "two relations between the same models with no names",
            schema = "model User {\n  id Int @id\n  written Post[]\n  edited Post[]\n}\n\n" +
                "model Post {\n  id Int @id\n" +
                "  author User @relation(fields: [authorId], references: [id])\n  authorId Int\n" +
                "  editor User @relation(fields: [editorId], references: [id])\n  editorId Int\n}\n",
            code = SemanticCode.RELATION_AMBIGUOUS,
            message = "4 fields claim the relation `PostToUser`",
            help = "give each relation its own name",
        ),
        Case(
            name = "fields and references of different lengths",
            schema = "model User {\n  id Int @id\n  posts Post[]\n}\n\nmodel Post {\n  id Int @id\n" +
                "  author User @relation(fields: [authorId, extra], references: [id])\n  authorId Int\n  extra Int\n}\n",
            code = SemanticCode.RELATION_ARGUMENT_MISMATCH,
            message = "`fields` lists 2 fields but `references` lists 1",
        ),
        Case(
            name = "relation pointing at a field that is not unique",
            schema = "model User {\n  id Int @id\n  email String\n  posts Post[]\n}\n\nmodel Post {\n  id Int @id\n" +
                "  author User @relation(fields: [authorEmail], references: [email])\n  authorEmail String\n}\n",
            code = SemanticCode.RELATION_REFERENCE_NOT_UNIQUE,
            message = "`references` must point at fields that identify one row of `User`",
            help = "add `@unique` to `email`",
        ),
        Case(
            name = "foreign key of a different type",
            schema = "model User {\n  id Int @id\n  posts Post[]\n}\n\nmodel Post {\n  id Int @id\n" +
                "  author User @relation(fields: [authorId], references: [id])\n  authorId String\n}\n",
            code = SemanticCode.RELATION_TYPE_MISMATCH,
            message = "`Post.authorId` and `User.id` have different types",
        ),
        Case(
            name = "foreign key declared on the list side",
            schema = "model User {\n  id Int @id\n  posts Post[] @relation(fields: [id], references: [id])\n}\n\n" +
                "model Post {\n  id Int @id\n  author User\n}\n",
            code = SemanticCode.RELATION_INVALID_SIDE,
            message = "the foreign key belongs on the side that holds one row",
        ),
        Case(
            name = "one-to-one with a foreign key that is not unique",
            schema = "model User {\n  id Int @id\n  profile Profile?\n}\n\nmodel Profile {\n  id Int @id\n" +
                "  user User @relation(fields: [userId], references: [id])\n  userId Int\n}\n",
            code = SemanticCode.RELATION_FOREIGN_KEY_NOT_UNIQUE,
            message = "the foreign key of a one-to-one relation must be unique",
        ),
        Case(
            name = "many-to-many with a foreign key",
            schema = "model Post {\n  id Int @id\n  tags Tag[] @relation(\"PT\", fields: [id], references: [id])\n}\n\n" +
                "model Tag {\n  id Int @id\n  posts Post[] @relation(\"PT\")\n}\n",
            code = SemanticCode.RELATION_INVALID_SIDE,
            message = "a many-to-many relation has no foreign key to place",
        ),
        Case(
            name = "SetNull on a foreign key that cannot be null",
            schema = "model User {\n  id Int @id\n  posts Post[]\n}\n\nmodel Post {\n  id Int @id\n" +
                "  author User @relation(fields: [authorId], references: [id], onDelete: SetNull)\n  authorId Int\n}\n",
            code = SemanticCode.INVALID_REFERENTIAL_ACTION,
            message = "`onDelete: SetNull` needs a foreign key that can be null",
        ),
        Case(
            name = "unknown referential action",
            schema = "model User {\n  id Int @id\n  posts Post[]\n}\n\nmodel Post {\n  id Int @id\n" +
                "  author User @relation(fields: [authorId], references: [id], onDelete: Cascde)\n  authorId Int\n}\n",
            code = SemanticCode.INVALID_REFERENTIAL_ACTION,
            message = "`Cascde` is not a referential action",
            help = "did you mean `Cascade`?",
        ),
        Case(
            name = "a model that requires itself",
            schema = "model Node {\n  id Int @id\n" +
                "  parent Node @relation(\"Tree\", fields: [parentId], references: [id])\n  parentId Int\n" +
                "  children Node[] @relation(\"Tree\")\n}\n",
            code = SemanticCode.UNSATISFIABLE_REQUIRED_RELATION,
            message = "`Node.parent` requires a `Node` that must already exist",
        ),
        Case(
            name = "two models mapped to one table",
            schema = "model User {\n  id Int @id\n\n  @@map(\"people\")\n}\n\n" +
                "model Person {\n  id Int @id\n\n  @@map(\"people\")\n}\n",
            code = SemanticCode.DUPLICATE_MAPPED_NAME,
            message = "two models map to the table `people`",
        ),
        Case(
            name = "two fields mapped to one column",
            schema = "model User {\n  id Int @id\n  email String @map(\"contact\")\n  phone String @map(\"contact\")\n}\n",
            code = SemanticCode.DUPLICATE_MAPPED_NAME,
            message = "two fields map to the column `contact`",
        ),
    )

    /** Mistakes in the smaller print: argument kinds, block properties, enum attributes, relation arguments. */
    private val detailCases = listOf(
        Case(
            name = "map given something that is not a string",
            schema = "model User {\n  id Int @id\n  email String @map(1)\n}\n",
            code = SemanticCode.INVALID_OPTION_VALUE,
            message = "`@map` expects a quoted string here",
        ),
        Case(
            name = "map with no name",
            schema = "model User {\n  id Int @id\n  email String @map\n}\n",
            code = SemanticCode.MISSING_ATTRIBUTE_ARGUMENT,
            message = "`@map` needs the name to use in the database",
        ),
        Case(
            name = "index given a string instead of a list",
            schema = "model User {\n  id Int @id\n  email String\n\n  @@index(\"email\")\n}\n",
            code = SemanticCode.INVALID_OPTION_VALUE,
            message = "expects a list of field names here",
        ),
        Case(
            name = "index listing quoted field names",
            schema = "model User {\n  id Int @id\n  email String\n\n  @@index([\"email\"])\n}\n",
            code = SemanticCode.INVALID_OPTION_VALUE,
            message = "expects field names, written without quotes",
        ),
        Case(
            name = "index over no fields",
            schema = "model User {\n  id Int @id\n\n  @@index([])\n}\n",
            code = SemanticCode.EMPTY_FIELD_LIST,
            message = "`@@index` was given no fields",
        ),
        Case(
            name = "index with no arguments",
            schema = "model User {\n  id Int @id\n\n  @@index\n}\n",
            code = SemanticCode.MISSING_ATTRIBUTE_ARGUMENT,
            message = "`@@index` needs the fields it applies to",
        ),
        Case(
            name = "composite id over an unknown field",
            schema = "model User {\n  id Int\n\n  @@id([id, missing])\n}\n",
            code = SemanticCode.UNKNOWN_FIELD_REFERENCE,
            message = "`@@id` refers to `missing`, which is not a field of this model",
        ),
        Case(
            name = "composite id over an optional field",
            schema = "model User {\n  a Int\n  b Int?\n\n  @@id([a, b])\n}\n",
            code = SemanticCode.INVALID_ID_FIELD,
            message = "`b` cannot be part of the primary key",
        ),
        Case(
            name = "id on a list field",
            schema = "model User {\n  tags String[] @id\n}\n",
            code = SemanticCode.INVALID_ID_FIELD,
            message = "`tags` cannot be the primary key",
            help = "remove the `[]`",
        ),
        Case(
            name = "id given an unnamed argument",
            schema = "model User {\n  id Int @id(\"pk\")\n}\n",
            code = SemanticCode.UNKNOWN_ATTRIBUTE_ARGUMENT,
            message = "`@id` takes 0 unnamed arguments",
        ),
        Case(
            name = "native type applied twice",
            schema = "model User {\n  id Int @id\n  name String @db.VarChar(10) @db.Text\n}\n",
            code = SemanticCode.DUPLICATE_ATTRIBUTE,
            message = "is applied twice",
        ),
        Case(
            name = "default with no value",
            schema = "model User {\n  id Int @id\n  name String @default\n}\n",
            code = SemanticCode.MISSING_ATTRIBUTE_ARGUMENT,
            message = "`@default` needs a value",
        ),
        Case(
            name = "generated default given arguments",
            schema = "model User {\n  id Int @id\n  createdAt DateTime @default(now(1))\n}\n",
            code = SemanticCode.UNKNOWN_ATTRIBUTE_ARGUMENT,
            message = "`now()` takes no arguments",
        ),
        Case(
            name = "dbgenerated given something other than an expression",
            schema = "model User {\n  id Int @id\n  token String @default(dbgenerated(1))\n}\n",
            code = SemanticCode.INVALID_DEFAULT,
            message = "`dbgenerated()` takes at most one quoted expression",
        ),
        Case(
            name = "fractional default on an integer",
            schema = "model User {\n  id Int @id\n  count Int @default(1.5)\n}\n",
            code = SemanticCode.INVALID_DEFAULT,
            message = "`1.5` is not a whole number",
        ),
        Case(
            name = "list default on a single field",
            schema = "model User {\n  id Int @id\n  name String @default([a])\n}\n",
            code = SemanticCode.INVALID_DEFAULT,
            message = "a list cannot be the default of a `String` field",
        ),
        Case(
            name = "bare name default on a scalar field",
            schema = "model User {\n  id Int @id\n  name String @default(FOO)\n}\n",
            code = SemanticCode.INVALID_DEFAULT,
            message = "the name `FOO` cannot be the default of a `String` field",
        ),
        Case(
            name = "url that is neither a string nor a call",
            schema = "datasource db {\n  provider = \"sqlite\"\n  url = 42\n}\n\nmodel User {\n  id Int @id\n}\n",
            code = SemanticCode.INVALID_OPTION_VALUE,
            message = "`url` must be a connection string or `env(\"NAME\")`",
            withDatasource = false,
        ),
        Case(
            name = "url built by an unknown function",
            schema = "datasource db {\n  provider = \"sqlite\"\n  url = secret(\"DB\")\n}\n\nmodel User {\n  id Int @id\n}\n",
            code = SemanticCode.INVALID_OPTION_VALUE,
            message = "`secret()` cannot produce a connection URL",
            withDatasource = false,
        ),
        Case(
            name = "env with no variable name",
            schema = "datasource db {\n  provider = \"sqlite\"\n  url = env()\n}\n\nmodel User {\n  id Int @id\n}\n",
            code = SemanticCode.INVALID_OPTION_VALUE,
            message = "`env()` takes the name of one environment variable",
            withDatasource = false,
        ),
        Case(
            name = "provider that is not a string",
            schema = "datasource db {\n  provider = 1\n  url = env(\"URL\")\n}\n\nmodel User {\n  id Int @id\n}\n",
            code = SemanticCode.INVALID_OPTION_VALUE,
            message = "`provider` must be a quoted string",
            withDatasource = false,
        ),
        Case(
            name = "property set twice in one block",
            schema = "datasource db {\n  provider = \"sqlite\"\n  provider = \"h2\"\n  url = env(\"URL\")\n}\n\n" +
                "model User {\n  id Int @id\n}\n",
            code = SemanticCode.DUPLICATE_MEMBER,
            message = "`provider` is set twice in this `datasource` block",
            withDatasource = false,
        ),
        Case(
            name = "javaFriendly that is not a boolean",
            schema = "generator client {\n  provider = \"volan-kotlin\"\n  package = \"com.example\"\n  javaFriendly = \"yes\"\n}\n\n" +
                "model User {\n  id Int @id\n}\n",
            code = SemanticCode.INVALID_OPTION_VALUE,
            message = "`javaFriendly` must be `true` or `false`",
        ),
        Case(
            name = "unknown generator property",
            schema = "generator client {\n  provider = \"volan-kotlin\"\n  package = \"com.example\"\n  binaryTargets = \"native\"\n}\n\n" +
                "model User {\n  id Int @id\n}\n",
            code = SemanticCode.UNKNOWN_OPTION,
            message = "a `generator` block has no property `binaryTargets`",
        ),
        Case(
            name = "enum with an attribute it does not accept",
            schema = "enum Role {\n  USER\n\n  @@index([USER])\n}\n\nmodel User {\n  id Int @id\n  role Role\n}\n",
            code = SemanticCode.UNKNOWN_ATTRIBUTE,
            message = "`@@index` cannot be used on `Role`",
        ),
        Case(
            name = "enum value with an attribute it does not accept",
            schema = "enum Role {\n  USER @unique\n}\n\nmodel User {\n  id Int @id\n  role Role\n}\n",
            code = SemanticCode.UNKNOWN_ATTRIBUTE,
            message = "`@unique` cannot be used on `Role.USER`",
        ),
        Case(
            name = "two enum values mapped to one database value",
            schema = "enum Role {\n  USER @map(\"x\")\n  ADMIN @map(\"x\")\n}\n\nmodel User {\n  id Int @id\n  role Role\n}\n",
            code = SemanticCode.DUPLICATE_MAPPED_NAME,
            message = "two values map to `x` in the database",
        ),
        Case(
            name = "referential action given as a string",
            schema = "model User {\n  id Int @id\n  posts Post[]\n}\n\nmodel Post {\n  id Int @id\n" +
                "  author User @relation(fields: [authorId], references: [id], onDelete: \"Cascade\")\n  authorId Int\n}\n",
            code = SemanticCode.INVALID_OPTION_VALUE,
            message = "`@relation` expects a name here",
        ),
        Case(
            name = "relation with fields but no references",
            schema = "model User {\n  id Int @id\n  posts Post[]\n}\n\nmodel Post {\n  id Int @id\n" +
                "  author User @relation(fields: [authorId])\n  authorId Int\n}\n",
            code = SemanticCode.MISSING_ATTRIBUTE_ARGUMENT,
            message = "`@relation` is missing `references`",
        ),
        Case(
            name = "relation naming a foreign key field that does not exist",
            schema = "model User {\n  id Int @id\n  posts Post[]\n}\n\nmodel Post {\n  id Int @id\n" +
                "  author User @relation(fields: [writerId], references: [id])\n  authorId Int\n}\n",
            code = SemanticCode.UNKNOWN_FIELD_REFERENCE,
            message = "`Post` has no field `writerId`",
        ),
        Case(
            name = "named relation whose ends point at different models",
            schema = "model A {\n  id Int @id\n  b B @relation(\"X\", fields: [bId], references: [id])\n  bId Int @unique\n}\n\n" +
                "model B {\n  id Int @id\n}\n\n" +
                "model C {\n  id Int @id\n  a A @relation(\"X\", fields: [aId], references: [id])\n  aId Int @unique\n}\n",
            code = SemanticCode.RELATION_MISSING_OPPOSITE,
            message = "the two ends of relation `X` do not point at each other",
        ),
        Case(
            name = "one-to-one where neither side holds the key",
            schema = "model User {\n  id Int @id\n  profile Profile?\n}\n\nmodel Profile {\n  id Int @id\n  user User?\n}\n",
            code = SemanticCode.RELATION_INVALID_SIDE,
            message = "neither side of this relation says where the foreign key lives",
        ),
    )

    private val allCases = cases + detailCases

    @TestFactory
    fun `every invalid model is reported with its code, message and help`(): List<DynamicTest> = allCases.map { case ->
        DynamicTest.dynamicTest(case.name) {
            val result = analyze(case)
            withClue(result.render().ifEmpty { "no diagnostics were reported" }) {
                result.diagnostics.map { it.code } shouldContain case.code
                result.render() shouldContain case.message
                case.help?.let { result.render() shouldContain it }
                result.schema shouldBe null
            }
        }
    }

    @Test
    fun `the negative corpus covers at least thirty distinct mistakes`() {
        allCases.map { it.name }.distinct().size shouldBe allCases.size
        allCases.size shouldBeGreaterThanOrEqual MINIMUM_NEGATIVE_CASES
    }

    @Test
    fun `a connection url written into the schema is a warning, not an error`() {
        val result = SchemaLoader.load(
            "schema.volan",
            "datasource db {\n  provider = \"sqlite\"\n  url = \"file:./dev.db\"\n}\n\nmodel User {\n  id Int @id\n}\n",
        )
        result.hasErrors shouldBe false
        result.warnings.map { it.code } shouldContain SemanticCode.CONNECTION_URL_IN_SCHEMA
        result.render() shouldContain "read it from the environment instead"
        result.schema shouldBe result.schemaOrThrow()
    }

    @Test
    fun `a cascade that loops back on itself is a warning, not an error`() {
        val result = analyze(
            "model A {\n  id Int @id\n" +
                "  b B? @relation(\"AB\", fields: [bId], references: [id], onDelete: Cascade)\n  bId Int? @unique\n" +
                "  fromB B? @relation(\"BA\")\n}\n\n" +
                "model B {\n  id Int @id\n  a A? @relation(\"AB\")\n" +
                "  toA A? @relation(\"BA\", fields: [aId], references: [id], onDelete: Cascade)\n  aId Int? @unique\n}\n",
        )
        withClue(result.render()) {
            result.hasErrors shouldBe false
            result.warnings.map { it.code } shouldContain SemanticCode.CASCADE_CYCLE
            result.render() shouldContain "the cascade goes"
        }
    }

    @Test
    fun `syntax errors stop analysis rather than producing guesses on top of them`() {
        val result = SchemaLoader.load("schema.volan", "model User {\n  email @unique\n}\n")
        result.schema shouldBe null
        result.diagnostics.map { it.code.id } shouldContain "E0104"
        result.diagnostics.none { it.code.id.startsWith("E02") } shouldBe true
    }

    private fun analyze(case: Case) = analyze(case.schema, case.withDatasource)

    private fun analyze(schema: String, withDatasource: Boolean = true) = SchemaLoader.load(
        "schema.volan",
        if (withDatasource) DATASOURCE + schema else schema,
    )

    private companion object {
        private const val MINIMUM_NEGATIVE_CASES = 30
        private val DATASOURCE = """
            datasource db {
              provider = "postgresql"
              url      = env("DATABASE_URL")
            }

        """.trimIndent() + "\n"
    }
}
