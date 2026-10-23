import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "dev.micheleserafini"
version = "1.5.0"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // IntelliJ's automatically registered JUnit 5 listener references JUnit 4.
    testRuntimeOnly("junit:junit:4.13.2")
    testImplementation("org.springframework.boot:spring-boot:3.4.5")
    testImplementation("org.springframework:spring-context:6.2.6")

    intellijPlatform {
        create("IC", "2025.1.4.1")

        bundledPlugin("com.intellij.java")

        // Tests use real JDI JVMs and JUnit Jupiter, not IntelliJ test fixtures.
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
            Faster debugger snapshots with batched JDI reads and export-local metadata caches.
            Configurable object, Spring and lookup limits in Settings / Tools / Runtime Object to JSON.
            Streaming JSON output, asynchronous file refresh and expanded regression checks.
        """.trimIndent()
    }

    pluginVerification {
        ides {
            create(
                IntelliJPlatformType.IntellijIdeaCommunity,
                "2025.1.4.1"
            )
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // Keep IntelliJ's test listener preferences isolated and usable in CI.
    val preferences = layout.buildDirectory.dir("tmp/test-preferences")
    systemProperty("java.util.prefs.userRoot", preferences.get().asFile.absolutePath)
    doFirst { preferences.get().asFile.mkdirs() }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        )
    }
}
