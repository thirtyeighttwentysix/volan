# Ktor

HTTP routes backed by the generated client. JDBC runs through suspendQuery; ApplicationStopped closes the pool.

## Run

Requires JDK 17+ and the Gradle wrapper from this checkout. The example is a separate Gradle build:
it resolves Volan artifacts through the published plugin, without project dependencies or a generator program.
It targets the unreleased `1.0.0-rc.1` candidate. Stage that candidate from the repository root first:

```shell
./gradlew publishAllPublicationsToReleaseTestRepository -Pversion=1.0.0-rc.1 -PvolanUnsignedLocalPublication --no-configuration-cache
```

Then change to `examples/ktor` and run:

```shell
../../gradlew test run -PvolanVersion=1.0.0-rc.1 -PvolanRepository=../../build/release-repository
```

PowerShell equivalents:

```powershell
# From the repository root:
.\gradlew.bat publishAllPublicationsToReleaseTestRepository '-Pversion=1.0.0-rc.1' -PvolanUnsignedLocalPublication --no-configuration-cache
Set-Location examples/ktor
..\..\gradlew.bat test run '-PvolanVersion=1.0.0-rc.1' '-PvolanRepository=../../build/release-repository'
```

Once a compatible release is on Maven Central, omit `volanRepository` and pass its version through
`volanVersion`. Alpha.2 cannot run this example: the H2 dialect and build plugin were added later.
The Java example still uses the Kotlin compiler to compile the generated sources; all application code is Java.

## What runs

`schema.volan` generates `example.generated.VolanClient` during compilation. The committed migration
creates the table before the client starts. The sample uses an H2 memory database with `DB_CLOSE_DELAY=-1`
so the database survives between the migration connection and the client pool; all data disappears at JVM exit.
No database service, environment variables or globally installed CLI are needed.

For deployment, keep reviewed migration files and apply them once before starting request handling.
Use a configured persistent JDBC URL and credentials; the embedded URL here is for a runnable example.
Volan transactions use `database.transaction`; these examples do not claim integration with Spring's `@Transactional`.

The server listens on `127.0.0.1:8080`. In another terminal:

```shell
curl -i -X POST http://127.0.0.1:8080/users/Ada
curl http://127.0.0.1:8080/users/1
```

The POST returns `201`, the generated ID and a `Location` header. GET returns `Ada`;
an unknown ID returns `404`, and a non-integer ID returns `400`. Stop the server with Ctrl+C.
The test exercises these HTTP requests against Ktor's test host with a real H2 database.

Framework version: [Ktor 3.6.0](https://ktor.io/docs/whats-new-360.html). See [application events](https://ktor.io/docs/server-events.html) and [server testing](https://ktor.io/docs/server-testing.html).

CI runs this project from a copy outside the main build through `scripts/verify-examples.py`, using staged artifacts;
tag releases repeat the check using Maven Central downloads.
