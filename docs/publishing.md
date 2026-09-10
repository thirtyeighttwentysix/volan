# Publishing Volan

The first release candidate is `0.1.0-alpha.1`, under `io.github.thirtyeighttwentysix`.
Until its tag workflow succeeds, it is a prepared release rather than an available Central version.

## Published artifacts

Eight implemented library modules and `volan-bom` are published. Libraries contain JVM classes,
sources and Dokka HTML documentation. POMs contain license, SCM and developer metadata. The BOM
constrains only modules that are actually published. Placeholder modules, tests and benchmarks
are excluded; the CLI is currently built from source.

## One-time credentials

The Central Portal account must own the verified namespace `io.github.thirtyeighttwentysix`.
Store these repository Actions secrets:

| Secret | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Username portion of a Central Portal User Token |
| `MAVEN_CENTRAL_PASSWORD` | Password portion of that token, not the account password |
| `MAVEN_SIGNING_KEY` | Full ASCII-armored private OpenPGP signing key |
| `MAVEN_SIGNING_PASSWORD` | Passphrase protecting the signing key |

Publish the corresponding **public** key to a supported public keyserver and commit it as
`docs/release-signing-key.asc`. Keep an encrypted backup of the private key and passphrase outside
the checkout. Never add either to Git or workflow logs. The workflow exposes each secret only to
steps that need it.

See the official [Central signing requirements](https://central.sonatype.org/publish/requirements/gpg/)
and [Gradle publishing plugin documentation](https://vanniktech.github.io/gradle-maven-publish-plugin/central/).

## Rehearse without publishing

Run the **Release** workflow manually on `main`. A branch run performs the full build, ABI and
migration coverage checks, stages unsigned artifacts and runs an independent PostgreSQL consumer.
It does not need release secrets and does not upload to Central.

For a local rehearsal (Docker required):

```shell
./gradlew publishAllPublicationsToReleaseTestRepository -PvolanUnsignedLocalPublication --no-configuration-cache
python scripts/verify-release.py --version 0.1.0-alpha.1
./gradlew -p release-smoke clean test "-PvolanVersion=0.1.0-alpha.1" "-PvolanRepository=/absolute/path/to/volan/build/release-repository"
```

`volanUnsignedLocalPublication` also disables registration of the Central publishing tasks.
Signed staging omits this flag and supplies the signing environment variables documented by the
plugin; verification then adds `--public-key docs/release-signing-key.asc`.

## Publish a release

1. Set `version` in `gradle.properties` and add `docs/releases/<version>.md`.
2. Commit and push. Wait for both CI and the manual Release rehearsal to succeed.
3. Push an annotated tag matching the version, for example `v0.1.0-alpha.1`.

A tag starts the signed release workflow. It builds and tests, validates all staged artifacts and
signatures, and runs the independent consumer before calling `publishAndReleaseToMavenCentral`.
It then waits up to 30 minutes for Central downloads, verifies them and reruns the consumer using
Maven Central. Only after those checks does it create a GitHub release; prerelease versions are
marked as prereleases.

Central release versions are immutable. If a run fails **after upload**, inspect the deployment in
Central Portal and artifact URLs before retrying: a published version cannot be overwritten.
For an already published release, run the verifier with `--central` and the independent consumer
without `volanRepository`; repair the GitHub release separately instead of uploading again.
