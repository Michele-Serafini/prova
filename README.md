# Runtime Object to JSON — 1.4.0

One IntelliJ editor command, **Export Object to JSON**, exports real Java values from a suspended JVM to formatted files in `<project>/JSON/`. The command also recognizes Spring Boot beans automatically. Building the plugin requires JDK 21; the minimum IDE build is IntelliJ IDEA 2025.1 (251).

## One command

1. Start the Java application in Debug mode and stop after the object is initialized.
2. Select a Java variable or field, or place the caret on its identifier.
3. Right-click → **Export Object to JSON**.

The same command handles a method annotated `@Bean` and a Spring component class. There is no separate Spring action or mode selection.

Resolution follows the selected source symbol, rather than just its text:

- Local variables use the current debugger frame.
- Instance, inherited/private, hidden and static fields are read using their declaring class. A local variable cannot accidentally replace a selected `this.field`.
- Simple field chains such as `order.customer` use the actual receiver. The plugin does not execute method calls to obtain a receiver.
- An actual `null` becomes JSON `null`, even for an injected dependency.
- An `@Bean` method resolves its registered name, including constant annotation names and aliases. A declared FactoryBean return type automatically resolves the factory itself. A component class resolves an already-created bean by its runtime type.
- When an injected field/parameter is unavailable in the current frame, registered `@Qualifier`/`@Resource` names or an unambiguous compatible singleton type can be resolved from Spring.
- Ambiguous contexts, types, classloaders and unsupported receiver expressions produce an explanatory message. The plugin does not choose an arbitrary instance.

## Java values

The exporter reads fields and arrays through JDI. Primitive values, arrays, enums, records and ordinary domain objects are supported. Known standard JDK scalars, dates, numbers, collections and maps have dedicated field-layout readers so that their JSON contains data rather than implementation details.

See **[COPERTURA.md](COPERTURA.md)** for the supported categories, regression cases and explicit limits. This is a debugger inspection snapshot, not a universal Java/Jackson serializer. Implementing `Serializable`, `Map`, `Collection` or a Spring interface alone does not guarantee that an arbitrary custom implementation can be converted.

Shared objects and cycles use JSON Pointer `$ref` markers. Unsupported layouts, unavailable targets, size limits and individual read errors use `$skipped`, `$truncated` or `$error` markers instead of invented values. Sensitive field/map-key names are redacted as `[REDACTED]` using a conservative name filter.

## Spring

Spring detection is automatic. Already available local/field values are read directly; registered singleton instances and common Spring proxies select the appropriate export mode. The plugin discovers active application contexts through their actual class hierarchy, including custom context classes, and ignores closed contexts.

Bean lookup understands names/aliases, singleton FactoryBean products already in the cache, and assignable types. Spring JDK/CGLIB AOP proxies are handled recursively when their target can be read safely. ApplicationContext, BeanFactory and Environment infrastructure is not traversed, including when nested inside an ordinary local object.

No getters, `toString()`, Jackson serializers, `getBean()`, `getObject()` or target-source methods run in the suspended application. Lazy/prototype/scoped objects are never created for export. A dynamic or unavailable proxy target is explicitly marked. Arbitrary custom qualifier metadata is not interpreted when reconstructing a dependency from the context; an already-injected reference remains readable directly.

Spring libraries are **test-only dependencies**. No Spring library or paid IntelliJ Spring plugin is bundled or required by the plugin itself.

## Build and validation

From `ExportJson/`, with **an installed JDK 21**. `gradle/gradle-daemon-jvm.properties` pins the Gradle daemon to Java 21. It can discover a locally installed JDK; automatic JDK downloading is not configured.

If Gradle fails before compilation with `IllegalArgumentException: 27.0.1` in `org.jetbrains.kotlin...JavaVersion.parse`, it was evaluating Kotlin build scripts with Java 27. This wrapper uses Gradle 8.14.3 and its embedded Kotlin; use JDK 21 for the build launcher as well as the daemon. `sourceCompatibility = 21` alone does not choose the JVM that runs Gradle.

In **PowerShell**, replace the example below with your actual JDK 21 installation directory (the directory containing `bin/java.exe`, not `bin` itself):

```powershell
$env:JAVA_HOME = 'C:\percorso\del\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --stop
.\gradlew.bat --version
.\gradlew.bat clean build --stacktrace
```

Check that the launcher and daemon report Java 21. When building from IntelliJ, set **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JVM** to JDK 21. Gradle's daemon criteria take precedence when present. The plugin's build JDK is separate from the JDK used by the application being debugged.

Standard commands:

```bat
.\gradlew.bat clean build
.\gradlew.bat buildPlugin
.\gradlew.bat verifyPluginProjectConfiguration
.\gradlew.bat verifyPluginStructure
.\gradlew.bat verifyPlugin
```

To start an interactive sandbox:

```bat
.\gradlew.bat runIde
```

Distribution: `ExportJson/build/distributions/PluginJson-1.4.0.zip`.

Tests launch real separate JVMs, suspend them at JDI breakpoints and assert the exported data and resolution behavior. Spring tests use a real Spring Boot application. **[VERIFICA.md](VERIFICA.md)** records the checks actually performed, IDE/JDK versions and results; compatibility with every future IDE/Java/Spring version is not implied.

Italian instructions: **[GUIDA-SPRING.md](GUIDA-SPRING.md)**. License: **[LICENSE](LICENSE)**.
