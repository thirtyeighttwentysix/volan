# Build-plugin consumers

Independent examples for the upcoming alpha.3 Gradle and Maven plugins. They use published Maven
coordinates, not project substitutions. Each ordinary `test` build generates and compiles the
client before exercising H2 CRUD. No database URL is needed during generation.

After alpha.3 is published, from the repository root:

```bash
./gradlew -p plugin-smoke/gradle test
mvn -f plugin-smoke/maven/pom.xml test
```

For staged artifacts use `scripts/verify-plugins.py`, described in
[CLI and build plugins](../docs/build-plugins.md). The script also checks configuration-cache reuse,
schema changes and model removal in disposable copies of these projects.
