import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.errorprone) apply false
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "net.ltgt.errorprone")

    group = "io.openisoworld"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(25)
    }

    dependencies {
        // Every module takes its versions from the Spring Boot BOM.
        "implementation"(platform(rootProject.libs.spring.boot.bom))
        "testImplementation"(platform(rootProject.libs.spring.boot.bom))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")

        // Null safety (ADR 0001): JSpecify annotations, checked at compile time by NullAway.
        "implementation"("org.jspecify:jspecify")
        "errorprone"(rootProject.libs.errorprone.core)
        "errorprone"(rootProject.libs.nullaway)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.errorprone {
            // Only NullAway, and only in packages annotated with @NullMarked.
            disableAllChecks = true
            check("NullAway", CheckSeverity.ERROR)
            option("NullAway:OnlyNullMarked", "true")
            option("NullAway:JSpecifyMode", "true")
        }
    }

    // Tests may pass null on purpose, and JUnit is not annotated.
    tasks.named<JavaCompile>("compileTestJava") {
        options.errorprone.enabled = false
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
