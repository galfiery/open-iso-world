plugins {
    alias(libs.plugins.spring.boot) apply false
}

subprojects {
    apply(plugin = "java-library")

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
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
