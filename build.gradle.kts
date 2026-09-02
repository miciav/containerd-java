plugins {
    `java-library`
    application
    id("com.google.protobuf") version "0.9.5"
}

group = "io.nanofaas"
version = "0.1.0-SNAPSHOT"

val containerdApiVersion = "v2.2.1" // pinned containerd API; bump together with vendored protos
val grpcVersion = "1.73.0"
val protobufVersion = "4.35.0"
val nettyVersion = "4.1.121.Final" // MUST match grpc-netty's resolved netty; see Step 5
val slf4jVersion = "2.0.17"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

dependencies {
    api("io.grpc:grpc-netty:$grpcVersion")
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("io.grpc:grpc-stub:$grpcVersion")
    // gRPC codegen emits @javax.annotation.Generated (JSR-250), absent from JDK 9+; compile-time only
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
    api("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("com.google.protobuf:protobuf-java-util:$protobufVersion")
    implementation("com.github.jnr:jnr-posix:3.1.20")
    api("org.slf4j:slf4j-api:$slf4jVersion")

    // Epoll native libs: both classifiers coexist on the runtime classpath; Netty's native
    // loader picks the one matching the host arch (x86_64 CI vs aarch64 dev host).
    runtimeOnly("io.netty:netty-transport-native-epoll:$nettyVersion:linux-x86_64")
    runtimeOnly("io.netty:netty-transport-native-epoll:$nettyVersion:linux-aarch_64")
    // io.netty.channel.epoll Java classes (Epoll, EpollDomainSocketChannel, EpollEventLoopGroup)
    // are not in the classifier jars (those carry only the .so); needed at compile time for
    // GrpcChannelFactory. Runtime provides them transitively via the native epoll artifacts.
    compileOnly("io.netty:netty-transport-classes-epoll:$nettyVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("io.grpc:grpc-inprocess:$grpcVersion")
    testRuntimeOnly("org.slf4j:slf4j-simple:$slf4jVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
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
