## What and why

<!-- What does this change, and what problem does it solve? The diff already says what; explain why. -->

Closes #

## How it was verified

<!-- Which tests cover this? Which databases did you run against? -->

- [ ] `./gradlew build test detekt ktlintCheck apiCheck` passes locally

## Checklist

- [ ] Tests ship in this pull request (a bug fix starts with a failing test)
- [ ] No `TODO`/`FIXME`, no `TODO()`, no commented-out code, no placeholder implementations
- [ ] Public declarations have KDoc
- [ ] Public API changes are reflected in a regenerated `api/*.api` dump and explained above
- [ ] User-visible changes are documented, and `CHANGELOG.md` is updated
- [ ] Anything that constrains future work has an ADR in `docs/adr/`
