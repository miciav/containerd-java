plugins {
    `java-library`
    application
    jacoco
    id("com.google.protobuf") version "0.9.5"
    // Analysis runs through this rather than the sonar-scanner-cli image: that image is published
    // for amd64 only, so it cannot run on an arm64 host. The plugin also knows the source sets,
    // the compiled classes and the test reports, which the CLI has to be told about by hand.
    id("org.sonarqube") version "7.5.0.8588"
}

group = "io.nanofaas"
version = "0.2.0"

val containerdApiVersion = "v2.2.1" // pinned containerd API; bump together with vendored protos
val grpcVersion = "1.73.0"
val protobufVersion = "4.35.0"
val nettyVersion = "4.1.121.Final" // MUST match grpc-netty's resolved netty; see Step 5
val slf4jVersion = "2.0.17"
val junitVersion = "5.11.4"
val junitPlatformVersion = "1.11.4"
val assertjVersion = "3.27.3"
val jnrPosixVersion = "3.1.20"
val javaxAnnotationVersion = "1.3.2"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
    withJavadocJar()
}

// Javadoc documents the published API only: io.nanofaas.containerd and its spi package.
//
// The generated protobuf/gRPC stubs are excluded because the spec keeps generated types out of
// the public API, and they would bury the output under thousands of pages. The internal package
// and the runnable example are excluded because neither is API — internals carry their reasoning
// in ordinary comments, which is where it belongs for code nobody compiles against.
//
// What remains is checked strictly: Xdoclint:all fails the build on an undocumented public
// member, a missing @param or @return, a broken link or malformed HTML. Since withJavadocJar()
// puts this task on the assemble path, CI enforces it on every push.
tasks.javadoc {
    exclude("containerd/**", "runtimeoptions/**", "io/nanofaas/containerd/internal/**",
            "io/nanofaas/containerd/example/**")
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:all", "-quiet")
        addStringOption("Xmaxwarns", "10000")
        // Without this the build passes on an undocumented public member: doclint reports it as
        // a warning, and javadoc warnings are not failures. -Werror is what makes it a gate.
        addBooleanOption("Werror", true)
        links("https://docs.oracle.com/en/java/javase/21/docs/api/")
    }
}

// Grouped by subject rather than by configuration: each dependency sits with the comment
// explaining why it has the scope it has, which regrouping would separate it from.
dependencies {
    api("io.grpc:grpc-netty:$grpcVersion")
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("io.grpc:grpc-stub:$grpcVersion")
    // gRPC codegen emits @javax.annotation.Generated (JSR-250), absent from JDK 9+; compile-time only
    compileOnly("javax.annotation:javax.annotation-api:$javaxAnnotationVersion")
    api("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("com.google.protobuf:protobuf-java-util:$protobufVersion")
    implementation("com.github.jnr:jnr-posix:$jnrPosixVersion")
    api("org.slf4j:slf4j-api:$slf4jVersion")

    // Epoll native libs: both classifiers coexist on the runtime classpath; Netty's native
    // loader picks the one matching the host arch (x86_64 CI vs aarch64 dev host).
    runtimeOnly("io.netty:netty-transport-native-epoll:$nettyVersion:linux-x86_64")
    runtimeOnly("io.netty:netty-transport-native-epoll:$nettyVersion:linux-aarch_64")
    // io.netty.channel.epoll Java classes (Epoll, EpollDomainSocketChannel, EpollEventLoopGroup)
    // are not in the classifier jars (those carry only the .so); needed at compile time for
    // GrpcChannelFactory. Runtime provides them transitively via the native epoll artifacts.
    compileOnly("io.netty:netty-transport-classes-epoll:$nettyVersion")

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testImplementation("io.grpc:grpc-inprocess:$grpcVersion")
    // wiring test starts a real Netty epoll UDS server; epoll classes are compileOnly for main
    testImplementation("io.netty:netty-transport-classes-epoll:$nettyVersion")
    testRuntimeOnly("org.slf4j:slf4j-simple:$slf4jVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitPlatformVersion")
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

// Coverage exists only if something measures it: without this SonarQube reports 0% however many
// tests run, and its quality gate fails on new code that is in fact covered.
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)   // what SonarQube reads
        html.required.set(true)  // what a human reads
    }
    classDirectories.setFrom(files(classDirectories.files.map {
        // The generated protobuf and gRPC stubs are not this project's code to cover.
        fileTree(it) { exclude("containerd/**", "runtimeoptions/**") }
    }))
}

// ---- integration tests (require a real containerd; not part of `check`) ----
val integrationTest = sourceSets.create("integrationTest")

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
// main output is not on custom source set classpaths automatically (only the default `test`
// source set gets that wiring) — without this, integration tests cannot see the library classes
integrationTest.compileClasspath += files(sourceSets.main.get().output)
integrationTest.runtimeClasspath += files(sourceSets.main.get().output)

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests against a real containerd on /run/containerd/containerd.sock"
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    systemProperty("io.nanofaas.containerd.socket", System.getProperty("io.nanofaas.containerd.socket", "/run/containerd/containerd.sock"))
    testLogging { events("failed", "skipped") }
}

application {
    mainClass.set("io.nanofaas.containerd.example.Example")
}

// What to analyse. The server URL and the token come from the orchestrator at invocation time.
sonar {
    properties {
        property("sonar.projectKey", "io.nanofaas:containerd-java")
        property("sonar.projectName", "containerd-java")
        // Vendored containerd protos and everything generated from them are not this project's code.
        property("sonar.exclusions", "**/build/generated/**,**/src/main/proto/**")
        property("sonar.junit.reportPaths", "build/test-results/test")
        // Kotlin has no @SuppressWarnings for Sonar rules and the analyzer does not honour
        // NOSONAR here, so this rule is turned off for the build file by name. The dependency
        // block is grouped by subject, with each scope sitting next to the comment justifying it;
        // regrouping by configuration would separate every dependency from its reasoning.
        property("sonar.issue.ignore.multicriteria", "buildFileGrouping")
        property("sonar.issue.ignore.multicriteria.buildFileGrouping.ruleKey", "kotlin:S6629")
        property("sonar.issue.ignore.multicriteria.buildFileGrouping.resourceKey", "**/build.gradle.kts")
        property("sonar.coverage.jacoco.xmlReportPaths",
            layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile.path)
    }
}

// ---- SonarQube analysis driven by this library (not part of the published artifact) ----
// Its own source set so the orchestrator never reaches a consumer's classpath, the same reason
// the example's SLF4J backend is kept off it.
val sonar = sourceSets.create("sonar")

// A custom source set inherits none of main's dependencies, so give it main's own runtime
// classpath. Without it the epoll natives are missing and the channel factory fails with
// NoClassDefFoundError on io.netty.channel.epoll.Epoll.
sonar.compileClasspath += sourceSets.main.get().output + configurations["runtimeClasspath"]
sonar.runtimeClasspath += sourceSets.main.get().output + configurations["runtimeClasspath"]

dependencies {
    "sonarRuntimeOnly"("org.slf4j:slf4j-simple:$slf4jVersion")
}

tasks.register<JavaExec>("sonarAnalysis") {
    description = "Runs SonarQube in containers driven by this library, and analyses this project"
    group = "verification"
    mainClass.set("io.nanofaas.containerd.sonar.SonarAnalysis")
    classpath = sonar.runtimeClasspath
    // The scanner reads compiled classes and test results, so make sure they are there.
    dependsOn(tasks.named("build"))
    systemProperty("io.nanofaas.containerd.socket",
        System.getProperty("io.nanofaas.containerd.socket", "/run/containerd/containerd.sock"))
    args = (findProperty("sonarArgs") as String? ?: "").split(" ").filter { it.isNotBlank() }
}

// The runnable example gets an SLF4J backend on the `run` classpath only. Using `runtimeOnly`
// (as the plan suggested) would publish slf4j-simple to every consumer of this library, which
// the spec forbids ("slf4j-simple is test/example scope only") and which would collide with a
// consumer's own SLF4J binding. A dedicated configuration keeps it off the published classpath.
val exampleLogging = configurations.create("exampleLogging")
dependencies {
    exampleLogging("org.slf4j:slf4j-simple:$slf4jVersion")
}
tasks.named<JavaExec>("run") {
    classpath += exampleLogging
}

// ---- protobuf / gRPC stub generation ----
// Vendored protos under src/main/proto mirror the containerd API pinned above by
// $containerdApiVersion; the Task 2 download step read this same pin from this file.
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc") {}
            }
        }
    }
}
