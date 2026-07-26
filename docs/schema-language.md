# The `schema.volan` language

A Volan schema is one file describing your whole data model. It is the input to code generation and
the input to migrations, so everything the database needs to know lives here.

This page is the syntax reference. Which attributes mean what, and which combinations are valid, is
decided by semantic analysis and documented separately.

## A complete example

```prisma
datasource db {
  provider = "postgresql"
  url      = env("DATABASE_URL")
}

generator client {
  provider     = "volan-kotlin"
  package      = "com.example.db"
  output       = "build/generated/volan"
  javaFriendly = true
}

enum Role {
  USER
  ADMIN
}

model User {
  id        Int      @id @default(autoincrement())
  email     String   @unique
  name      String?
  role      Role     @default(USER)
  posts     Post[]
  createdAt DateTime @default(now())

  @@index([email, createdAt])
  @@map("users")
}
```

## Blocks

A schema is a sequence of four kinds of block, in any order:

| Block | Purpose |
|---|---|
| `datasource <name> { … }` | Which database to talk to |
| `generator <name> { … }` | What to generate, and where |
| `model <name> { … }` | One table |
| `enum <name> { … }` | One enumerated type |

`datasource` and `generator` bodies are `name = value` properties. `model` and `enum` bodies are
fields or values, plus `@@` attributes that apply to the whole block.

None of these words are reserved: a field may be called `model`, and a model may be called `Enum`.
The parser recognises the four keywords by position, not by forbidding them elsewhere.

## Fields

```
<name> <Type>[?|[]] <attributes…>
```

A type is either a scalar, the name of an `enum`, or the name of another `model` — which makes the
field a relation. The marker after the type sets how many values the field holds:

| Written | Meaning |
|---|---|
| `String` | exactly one value, never null |
| `String?` | zero or one value |
| `Post[]` | any number of values |

`Post[]?` is rejected: an empty list already expresses "no values". `Post[][]` is rejected too.

The scalar types are `String`, `Int`, `Long`, `Float`, `Double`, `Decimal`, `Boolean`, `DateTime`,
`Date`, `Time`, `Json`, `Bytes` and `Uuid`.

## Attributes

`@` attributes apply to the field or enum value on their line. `@@` attributes apply to the whole
model or enum and are written on their own line.

```prisma
model Post {
  title    String @db.VarChar(200)
  author   User   @relation(fields: [authorId], references: [id], onDelete: Cascade)
  authorId Int

  @@index([authorId])
}
```

An attribute name may be namespaced — `@db.VarChar(200)` names a native database type. Arguments are
positional or named, and an attribute with no arguments needs no parentheses: `@id`, not `@id()`.

**Attributes bind to their line.** Everything else in the language ignores line breaks, but an
attribute written on the line *below* a field would otherwise be indistinguishable from one belonging
to that field. This is why a `@@index` on the next line is understood as belonging to the model, and
why a lone `@index` on its own line is reported as a mistake with a suggestion to write `@@index`.

## Values

| Kind | Example |
|---|---|
| String | `"postgresql"` |
| Number | `200`, `-1`, `2.5` |
| Boolean | `true`, `false` |
| Name | `USER`, `Cascade`, `authorId` |
| List | `[email, createdAt]` |
| Call | `now()`, `autoincrement()`, `env("DATABASE_URL")` |

String escapes are `\n`, `\r`, `\t`, `\"`, `\\`, `\/` and `\uXXXX`. Strings do not span lines.

`env("NAME")` reads an environment variable at the moment the schema is used, which is how a database
URL stays out of the repository.

## Comments

```prisma
// an ordinary comment
/// documentation, kept and passed through to the generated code
model User {
  id Int @id // trailing comments are allowed too
}
```

Both forms survive `volan format`, in the position the author put them, as do the blank lines used to
group fields. C-style block comments are not supported.

## Errors

Every problem is reported with the exact text it concerns, an explanation and, where the fix is
obvious, a suggestion:

```text
error[E0104]: expected a field type
 ┌─ schema.volan:2:9
 │
2│   email @unique
 │         ^ found `@`
 │
 = help: a field is written as `name Type`, for example `email String`
```

The parser recovers after each error and keeps going, so one run reports every mistake in the file
rather than only the first.

### Diagnostic codes

| Code | Meaning |
|---|---|
| `E0001` | Unterminated string literal |
| `E0002` | Invalid escape sequence |
| `E0003` | Unexpected character |
| `E0004` | Block comments are not supported |
| `E0005` | Malformed number literal |
| `E0100` | Unknown top-level declaration |
| `E0101` | Expected a name |
| `E0102` | Expected `{` |
| `E0103` | Unclosed block |
| `E0104` | Expected a field type |
| `E0105` | Expected `=` |
| `E0106` | Expected a value |
| `E0107` | Unclosed argument list |
| `E0108` | Unclosed list |
| `E0109` | A list type cannot be optional |
| `E0110` | Nested list types are not supported |
| `E0111` | Repeated `?` marker |
| `E0112` | `@` used where `@@` belongs, or the reverse |
| `E0113` | Expected an attribute name |
| `E0114` | Expected a name after `.` |
| `E0115` | Named argument without a value |
| `E0116` | Unexpected token at the top level |
| `E0117` | Model without fields |
| `E0118` | Enum without values |
| `E0119` | Configuration property without a value |
| `E0120` | Attribute in a `datasource` or `generator` block |
| `E0121` | Unexpected token inside a block |

## Validation

Parsing only decides that a document is well formed. Everything below is checked afterwards, once the
whole schema is known — which is why these problems are reported with their own `E02xx` codes.

- **Names.** Models and enums share one namespace. Field names are unique within a model, value names
  within an enum, and no two models may map to the same table or two fields to the same column.
- **Types.** Every field's type must be a scalar, an enum or a model in this schema. A misspelling is
  reported with the closest match.
- **Keys.** Every model needs a primary key, written as `@id` on one field or `@@id([a, b])` for
  several — not both, and not twice. Key fields are required and hold a single value. A model marked
  `@@ignore` is exempt: Volan generates no client for it, and it may describe a table that has no key.
- **Defaults.** A default has to fit its field: `autoincrement()` on `Int` or `Long`, `now()` on a
  temporal type, `uuid()` on `Uuid` or `String`, an enum value that the enum actually has, and only
  `[]` on a list.
- **Relations.** Both ends must exist and name each other. Exactly one side carries
  `fields`/`references`, and it is the side holding a single row; the two lists pair up one to one, the
  types match, and the referenced fields identify a row — a primary key or a unique constraint. A
  one-to-one relation additionally needs a unique foreign key. Many-to-many relations carry no foreign
  key at all; Volan keeps the pairs in a join table named after the relation.
- **Referential actions.** `onDelete: SetNull` requires a foreign key that can hold null.
- **Warnings.** Two things are reported without failing the build: a connection URL written into the
  schema rather than read with `env()`, and a chain of `onDelete: Cascade` that loops back to where it
  started.

### Validation codes

| Code | Meaning |
|---|---|
| `E0200` | Two declarations share a name |
| `E0201` | Unknown field type |
| `E0202` | Duplicate field, enum value or property |
| `E0203` | No `datasource` block |
| `E0204` | More than one `datasource` block |
| `E0205` | Unknown database provider |
| `E0206` | Required block property missing |
| `E0207` | Unknown block property |
| `E0208` | Property or argument of the wrong kind |
| `E0209` | Model without a primary key |
| `E0210` | Primary key declared twice |
| `E0211` | Field cannot be part of a primary key |
| `E0212` | Unknown attribute |
| `E0213` | Unknown attribute argument |
| `E0214` | Missing attribute argument |
| `E0215` | Attribute not allowed on this target |
| `E0216` | Attribute applied twice |
| `E0217` | `@default` does not fit the field |
| `E0218` | `@updatedAt` on a field it cannot maintain |
| `E0219` | Attribute refers to an unknown field |
| `E0220` | Relation has no matching field on the other model |
| `E0221` | Several relation fields could pair up |
| `E0222` | `fields` and `references` do not line up |
| `E0223` | Foreign key declared on the wrong side, on both, or on neither |
| `E0224` | `references` does not identify a row |
| `E0225` | Foreign key and referenced field have different types |
| `E0226` | Referential action unknown or impossible |
| `E0227` | Two models or fields map to one database name |
| `E0228` | Cascade loops back on itself (warning) |
| `E0229` | `@unique` on a field that cannot carry it |
| `E0230` | Constraint declared over no fields |
| `E0231` | Unknown attribute namespace |
| `E0232` | A model requires a row of itself |
| `E0233` | One-to-one foreign key is not unique |
| `E0234` | Connection URL written into the schema (warning) |

## Grammar

```ebnf
schema        = { declaration } ;
declaration   = datasource | generator | model | enum ;

datasource    = "datasource" name "{" { configEntry } "}" ;
generator     = "generator"  name "{" { configEntry } "}" ;
configEntry   = name "=" value ;

model         = "model" name "{" { field | blockAttribute } "}" ;
field         = name typeRef { attribute } ;
typeRef       = name [ "[" "]" | "?" ] ;

enum          = "enum" name "{" { enumValue | blockAttribute } "}" ;
enumValue     = name { attribute } ;

attribute     = "@"  attrName [ "(" arguments ")" ] ;
blockAttribute= "@@" attrName [ "(" arguments ")" ] ;
attrName      = name [ "." name ] ;

arguments     = [ argument { "," argument } [ "," ] ] ;
argument      = [ name ":" ] value ;

value         = string | number | "true" | "false" | name | list | call ;
list          = "[" [ value { "," value } ] "]" ;
call          = name "(" arguments ")" ;

name          = ( letter | "_" ) { letter | digit | "_" } ;
```
