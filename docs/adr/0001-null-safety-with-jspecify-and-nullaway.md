# 1. Null safety with JSpecify and NullAway

- **Status:** Proposed
- **Date:** 2026-11-27
- **Scope:** server (all Gradle modules)

## Context

The first classes of `server/core` (grid, room, commands and events) guard every reference parameter with `Objects.requireNonNull`. Each record has a compact constructor that exists only for these checks, for example:

```java
record Leave(String playerId) implements Command {
    public Leave {
        Objects.requireNonNull(playerId, "playerId");
    }
}
```

These checks have three problems:

- **They hide the code that matters.** In a project meant to be read, a third of some files is null checks.
- **They fail late.** A `null` is found when the code runs, maybe in a tick, instead of when it is written.
- **They are easy to forget.** Nothing tells a contributor that a new record lacks them.

Java has no null-safe types, but the ecosystem has a standard way to add them:

- **JSpecify** is a small set of annotations (`@NullMarked`, `@Nullable`) agreed by Google, JetBrains, Oracle, Uber, the Spring team and others. Spring Framework 7 and Spring Boot 4 use it for their own APIs, and the Spring Boot BOM already manages it (`org.jspecify:jspecify` 1.0.1).
- **NullAway** is an Error Prone plugin that reads those annotations and fails the compilation when a value that may be `null` reaches a place that does not accept it.

Inside a package annotated with `@NullMarked`, every type is non-null unless it says `@Nullable`. NullAway then checks this rule in every method call, field and return.

## Decision

Check nulls at compile time with JSpecify and NullAway, and drop the runtime null checks that the compiler makes redundant.

1. **Packages opt in.** Each package with handwritten code gets a `package-info.java` with `@NullMarked`. NullAway runs with `OnlyNullMarked`, so it checks only those packages. Today they are `io.openisoworld.core.grid` and `io.openisoworld.core.room`; `net`, `persistence` and `app` add theirs when they get handwritten code.
2. **One configuration for all modules**, in the root `server/build.gradle.kts`. A module without `@NullMarked` packages is not affected.
3. **Only NullAway is enabled.** Error Prone runs with all its other checks disabled, so this decision is about nulls only. Enabling more Error Prone checks would be a separate decision.
4. **JSpecify mode on** (`NullAway:JSpecifyMode`), so generics and arrays follow the JSpecify rules.
5. **Test sources are not checked.** Tests may pass `null` on purpose, and JUnit's APIs are not annotated.
6. **Runtime checks stay at the boundaries.** The compiler only sees code that it compiles with NullAway, so:
   - the generated protocol records keep their `Objects.requireNonNull`: they are filled by Jackson from untrusted JSON. The generated package is not `@NullMarked` and the generator does not change;
   - checks on values, not on nulls, stay where they are, for example a spawn tile that must be walkable.
7. **Versions** of the tools that the BOM does not manage go in `server/gradle/libs.versions.toml`: Error Prone 2.53.0, NullAway 0.14.4 and the `net.ltgt.errorprone` Gradle plugin 5.2.0.

The same record then reads:

```java
record Leave(String playerId) implements Command {}
```

and `new Leave(null)` does not compile.

## Consequences

### Benefits

- Records and constructors in `core` are shorter and show only the rules of the game.
- A missing value is a compile error in every module that opts in, not an exception in a tick.
- Where `null` is allowed, the type says so with `@Nullable`, so the reader does not need to guess.
- The project follows the same convention as Spring Boot 4, which it is built on.

### Costs

- **Three build-time dependencies and one Gradle plugin.** Error Prone and NullAway run only inside `javac`: nothing is added to the application at run time. JSpecify adds one small jar of annotations, with no dependencies of its own.
- **`core` is no longer "free of dependencies"** in the strict sense. JSpecify contains only annotations, with no behaviour, no Spring, no JPA and no I/O, so the rule in `CLAUDE.md` ("`core` has no Spring, JPA or I/O") still holds. The comment in `server/core/build.gradle.kts` is updated to say so.
- **Slower compilation**, by a few seconds for the whole server.
- **A new tool to learn.** Contributors meet NullAway errors, which are usually clear but are not plain `javac` errors.
- **No protection from code outside the check.** A `null` from a library, from reflection or from a package that is not `@NullMarked` is not caught. This is why the boundaries keep their runtime checks (decision 6).
- **Error Prone runs inside `javac`**, so a new JDK release can need a newer Error Prone. The versions above were tried with Gradle 9.9.2 and JDK 21, where `:core:test` passes; JDK 25 must be confirmed on a machine that has it.

### Alternatives considered

- **Keep `Objects.requireNonNull` everywhere.** No new tools, but the problems in "Context" remain.
- **Check only at the boundaries, without a tool.** Shorter code, but nothing catches a `null` passed by mistake inside the server.
- **Lombok `@NonNull`.** It generates the same runtime checks, so it fails late as well, and it changes how Java is compiled in a way that readers of an example project should not have to learn.
- **The Checker Framework.** Stronger than NullAway, but slower and with many more annotations to write.

## Implementation

After this ADR is accepted, as a separate commit on the branch where it is accepted:

| File | Change |
|------|--------|
| `server/gradle/libs.versions.toml` | Error Prone, NullAway and the Error Prone Gradle plugin |
| `server/build.gradle.kts` | Plugin, `jspecify` and the NullAway configuration for every module; NullAway off for test sources |
| `server/core/build.gradle.kts` | Comment: no dependencies except the JSpecify annotations |
| `server/core/src/main/java/io/openisoworld/core/grid/package-info.java` | `@NullMarked` |
| `server/core/src/main/java/io/openisoworld/core/room/package-info.java` | `@NullMarked` |
| `server/core/src/main/java/io/openisoworld/core/**` | Remove `Objects.requireNonNull` and the compact constructors that exist only for it. Keep `List.copyOf` |
| `server/core/src/main/java/io/openisoworld/core/grid/Pathfinder.java` | NullAway reports one real case: the result of `Map.get` is unboxed without a check. Handle the missing entry explicitly |
| `CLAUDE.md`, `docs/Home.md` | Add JSpecify and NullAway to the tech stack; the ADR list is no longer empty |

The status of this ADR then becomes **Accepted**.
