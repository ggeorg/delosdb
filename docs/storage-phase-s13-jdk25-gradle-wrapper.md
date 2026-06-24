# Storage Phase S13 — JDK 25 Gradle Wrapper Migration

This phase is platform cleanup after the DelosDB Unified Storage I/O Abstraction sequence.

It updates the Gradle wrapper distribution from Gradle 8.14.2 to Gradle 9.5.1 so the build can run when the active daemon JVM is JDK 25.

Scope:

- Update `gradle/wrapper/gradle-wrapper.properties` only.
- Do not change source compatibility.
- Do not introduce MemorySegment usage.
- Do not change DelosPageVolume, page format, storage behavior, heap behavior, or provider dispatch.

Reason:

- The prior S11 run failed before compiling project code with `Unsupported class file major version 69`.
- Class-file major version 69 is Java 25.
- Gradle 8.14.2 is not the right daemon runtime for JDK 25.
- Gradle 9.1.0 introduced Java 25 daemon support; Gradle 9.5.1 is the current stable Gradle line at the time of this overlay.

Validation:

Run the normal storage I/O and O5/C7 smokes under JDK 25 after applying this overlay.
