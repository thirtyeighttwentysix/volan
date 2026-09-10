# Independent Maven consumer

This is a separate Gradle build: it has no project dependencies or composite-build substitution.
It loads a schema with the published generator, compiles the generated Kotlin client, then runs
Java tests for migrations, nullable fields, asynchronous queries and transaction rollback on a
real PostgreSQL container. Docker is mandatory; missing Docker fails instead of skipping the test.

From the repository root:

```shell
./gradlew -p release-smoke clean test "-PvolanVersion=0.1.0-alpha.1" "-PvolanRepository=/absolute/path/to/volan/build/release-repository"
```

Omit `volanRepository` to consume Maven Central. When a local repository is supplied, all Volan
artifacts must resolve there; missing publications cannot silently fall back to Central.
