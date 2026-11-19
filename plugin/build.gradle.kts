plugins {
    java
}

description = "Forensic PvP telemetry collection plugin (report-only, never mutates gameplay)"

val paperApiVersion = "1.21.11-R0.1-SNAPSHOT"

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    // compileOnly does not propagate to tests, and MockBukkit declares its
    // paper-api non-transitively: without this, tests compile against nothing
    // and run against nothing. testImplementation covers both, pinned to the
    // production version by the resolution strategy below.
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Live-server tests without a live server. Only the observer needs it;
    // ring/encoder/uploader are pure-JVM and stay server-free.
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.116.3")
}

// Main and test code compile against OUR Paper version, never MockBukkit's
// transitive one: ancient stable methods behave identically, but a silent
// downgrade would let new-API usage slip through compile and die on the real
// server.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.papermc.paper" && requested.name == "paper-api") {
            useVersion(paperApiVersion)
            because("observer code must compile against the production Paper version")
        }
    }
}

// Pure-JVM logic (encoding, ring, framing) must stay testable without a server.
tasks.test {
    useJUnitPlatform()
}

// Hand-rolled benchmarks (no JMH on the plugin classpath): prints ns/op tables
// for sizing judgment. Timing is reported, never gated; allocation IS gated,
// in BenchTest with deterministic bounds.
tasks.register<JavaExec>("dumpFixtures") {
    group = "verification"
    description = "Encodes cross-language fixture bytes with the real encoder."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.forensic.telemetry.fixtures.FixtureMain"
}

tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Runs observer/ring/encoder benchmarks and prints ns/op."
    // Main runtimeClasspath excludes compileOnly paper-api (correct for the shipped
    // jar); benchmarks reference Bukkit types in signatures, so they run with the
    // test runtime classpath instead. Production code paths are unaffected.
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.forensic.telemetry.bench.BenchMain"
}

tasks.jar {
    archiveBaseName = "forensic-telemetry"
}