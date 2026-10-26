plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

group = "com.manjul.kbapi"
version = "0.1.0"

val ktorVersion = "3.6.0"

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.manjul.kbapi.MainKt")
}

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    implementation("com.anthropic:anthropic-java:3.3.0")
    implementation("com.google.firebase:firebase-admin:9.11.0")
    implementation("com.google.cloud:google-cloud-firestore:3.49.0")
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    // 3.0.0 is built for Java 21 (class file 65); the toolchain is Java 17, so stay on the 2.x line.
    testImplementation("com.atlassian.oai:swagger-request-validator-core:2.46.1")
    testImplementation(platform("org.junit:junit-bom:5.13.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The prompt files live in the repo's prompts/ directory (TRD §4.4.3) and ship on the classpath under /prompts.
// A missing directory would be skipped silently and the service would fail at startup, so it fails the build.
tasks.processResources {
    val promptsDir = layout.projectDirectory.dir("prompts").asFile
    doFirst {
        check(promptsDir.isDirectory && !promptsDir.listFiles().isNullOrEmpty()) { "prompts/ is missing or empty" }
    }
    from(promptsDir) { into("prompts") }
}

tasks.test {
    useJUnitPlatform()
    // Tests walk the repo tree (SecretScanTest) from the project directory.
    workingDir = projectDir
}

// Offline eval harness (backend/evals, TRD §7.1). Calls the real API: needs ANTHROPIC_API_KEY and --yes.
// ANTHROPIC_API_KEY=… ./gradlew evalRun --args="--prompt rewrite@1 --model claude-haiku-5-5 --judge claude-opus-5-5 --yes"
tasks.register<JavaExec>("evalRun") {
    group = "verification"
    description = "Runs the eval cases for one prompt against one model and writes a report to evals/results/."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.manjul.kbapi.evals.EvalRunnerKt")
    workingDir = projectDir
}
