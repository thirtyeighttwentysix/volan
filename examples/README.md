# Examples

Each folder is an independent Gradle project with its own schema, migrations, application, tests and setup instructions.

| Example | Shows |
|---|---|
| [Kotlin](kotlin-basic/README.md) | Generated Kotlin client, migration, transaction and rollback |
| [Java](java-basic/README.md) | Generated Java-facing API and try-with-resources |
| [Spring Boot](spring-boot/README.md) | Client ownership and constructor injection in a console application |
| [Ktor](ktor/README.md) | HTTP routes, suspendQuery and application shutdown |

These examples use 1.0.0 from Maven Central. Follow an individual README to run the project; local staging is not required.
CI consumes copies through published build plugins rather than including these projects in the main multi-module build.
