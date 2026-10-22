import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "dev.micheleserafini"
version = "1.4.0"

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
    // Editor actions only: disable the entire searchable-options pipeline.
    buildSearchableOptions.set(false)
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
            One Export Object to JSON action with automatic Spring detection,
            source-aware runtime field resolution, qualifiers and type lookup.
            Expanded Java type coverage and strict unsupported-layout markers.
            Added real JDI regression tests for Java values and Spring Boot.
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
