# Independent Maven consumer

This is a separate Gradle build: it has no project dependencies or composite-build substitution.
It loads a schema with the published generator, compiles the generated Kotlin client, then runs
Java tests for migrations, nullable fields, asynchronous queries and transaction rollback on a
real PostgreSQL container. Docker is mandatory; missing Docker fails instead of skipping the test.
From alpha.2, a second Java test discovers SQLite from its published JAR and exercises CRUD,
DDL, pull/push, constraints, pooling and rollback without Docker. It creates tables from the schema
through published migration APIs. Alpha.1 verification omits that dialect.
From alpha.3, an H2 Java test checks published provider discovery, initial DDL, CRUD, async reads
and transaction rollback, plus schema introspection and export round trips. H2 push and versioned
migrations are implemented on main with durable progress and manual recovery across implicit DDL
commits; see [migration guarantees](../docs/migrations.md). Alpha.3 libraries are not yet on Central.

From the repository root:

```shell
./gradlew -p release-smoke clean test "-PvolanVersion=0.1.0-alpha.3" "-PvolanRepository=/absolute/path/to/volan/build/release-repository"
```

Omit `volanRepository` to consume Maven Central. When a local repository is supplied, all Volan
artifacts must resolve there; missing publications cannot silently fall back to Central.
