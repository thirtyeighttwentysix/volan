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
