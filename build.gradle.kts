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
    // Exposed JDBC DSL, used by the simple CRUD adapters; raw SQL stays for
    // pg_trgm/pgvector and the SKIP LOCKED queue.
    implementation("org.jetbrains.exposed:exposed-core:1.6.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.6.1")
    implementation("org.jetbrains.exposed:exposed-java-time:1.6.1")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
}

java {
    toolchain {
        // JDK 25: matches the Mandrel 25 builder image used for native builds.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// The OpenAPI contract defines the HTTP DTOs. Keep a generated copy in the
// source tree so reviewers can read the DTOs before running Gradle.
val generatedApiSources = layout.buildDirectory.dir("generated/src/main/kotlin")
val checkedInApiSources = layout.projectDirectory.dir("src/generated/kotlin")

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
    globalProperties.set(mapOf("models" to ""))
    cleanupOutput.set(true)
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
}

val syncApiModels by tasks.registering(Sync::class) {
    group = "build"
    description = "Update the HTTP DTOs in src/generated/kotlin from OpenAPI."
    dependsOn(tasks.openApiGenerate)
    from(generatedApiSources)
    into(checkedInApiSources)
}

val checkApiModels by tasks.registering {
    group = "verification"
    description = "Check that the HTTP DTOs in src/generated/kotlin match OpenAPI."
    dependsOn(tasks.openApiGenerate)
    mustRunAfter(syncApiModels)
    inputs.dir(generatedApiSources)
    inputs.dir(checkedInApiSources)
    doLast {
        val generated = generatedApiSources.get().asFile
        val checkedIn = checkedInApiSources.asFile

        fun pathsUnder(root: File) =
            root
                .walkTopDown()
                .filter(File::isFile)
                .map { it.relativeTo(root).path }
                .toSet()

        val paths = pathsUnder(generated)
        check(
            paths == pathsUnder(checkedIn) &&
                paths.all { generated.resolve(it).readBytes().contentEquals(checkedIn.resolve(it).readBytes()) },
        ) {
            "HTTP DTOs are out of date. Run ./gradlew syncApiModels and include the updated sources."
        }
    }
}

tasks.check {
    dependsOn(checkApiModels)
}

sourceSets {
    main {
        kotlin.srcDir(checkedInApiSources)
    }
}

tasks.compileKotlin {
    mustRunAfter(syncApiModels)
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

tasks.matching { it.name.startsWith("runKtlint") }.configureEach {
    mustRunAfter(syncApiModels)
}

allOpen {
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.inject.Singleton")
}
