plugins {
    `java-library`
    application
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
    api("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("com.google.protobuf:protobuf-java-util:$protobufVersion")
    implementation("com.github.jnr:jnr-posix:3.1.20")
    api("org.slf4j:slf4j-api:$slf4jVersion")

    runtimeOnly("io.netty:netty-transport-native-epoll:$nettyVersion:linux-x86_64")

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
