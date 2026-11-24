plugins {
    java
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.allopen") version "2.4.21"
    id("io.quarkus") version "3.40.1.2"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("org.openapi.generator") version "7.27.0"
}

group = "com.neviswealth"
version = "1.0.0"

repositories {
    mavenCentral()
}

val quarkusPlatformGroup = "io.quarkus.platform"
val quarkusPlatformVersion = "3.40.1.2"

dependencies {
    implementation(enforcedPlatform("$quarkusPlatformGroup:quarkus-bom:$quarkusPlatformVersion"))
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-hibernate-validator")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-agroal")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-scheduler")
    implementation("io.quarkus:quarkus-smallrye-openapi")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-rest-client-jackson")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
}

java {
    toolchain {
        // JDK 25: matches the Mandrel 25 builder image used for native builds.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// Spec-first: src/main/resources/META-INF/openapi.yaml is the single source of
// truth for the HTTP contract. Only the DTOs are generated (routes, domain and
// SQL stay handwritten); the generated sources live in build/generated and are
// compiled together with the handwritten Kotlin code.
openApiGenerate {
    generatorName.set("kotlin")
    library.set("jvm-microprofile")
    inputSpec.set("$projectDir/src/main/resources/META-INF/openapi.yaml")
    outputDir.set(layout.buildDirectory.dir("generated"))
    modelPackage.set("com.neviswealth.search.generated.model")
    configOptions.set(
        mapOf(
            "serializationLibrary" to "jackson",
            "useJakartaEe" to "true",
        ),
    )
    // Empty lists mean "all models, no API interfaces".
    globalProperties.set(
        mapOf(
            "apis" to "",
            "models" to "",
        ),
    )
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
}

tasks.openApiGenerate {
    doLast {
        // Only the HTTP DTOs are consumed; the generated client/API scaffolding
        // under org/openapitools is not part of the service.
        project.delete(file(layout.buildDirectory.dir("generated/src/main/kotlin/org")))
    }
}

sourceSets {
    main {
        kotlin {
            srcDir(layout.buildDirectory.dir("generated/src/main/kotlin"))
        }
    }
}

tasks.compileKotlin {
    dependsOn(tasks.openApiGenerate)
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}

ktlint {
    // Generated DTOs are not ours to style: they come from the spec.
    filter {
        exclude { element -> element.file.path.contains("generated") }
    }
}

// Fails on generated Kotlin changes the ktlint task's inputs while Gradle is
// still producing them: run generation first, and lint the handwritten code.
tasks.matching { it.name.lowercase().contains("ktlint") }.configureEach {
    dependsOn(tasks.openApiGenerate)
}

allOpen {
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.inject.Singleton")
}
