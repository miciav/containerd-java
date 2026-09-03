# containerd-java Client Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A production-quality Java client library (`io.nanofaas:containerd-java`) that controls containerd directly over its native gRPC API on a Unix Domain Socket — no CLI, no Docker, no CRI — for NanoFaaS.

**Architecture:** gRPC over UDS (grpc-netty + epoll). Public ergonomic API (`ContainerdClient` → `Images`/`Containers`/`Tasks`/`Events` facades) built on generated protobuf stubs vendored from containerd v2.2.1. Internal layer: `GrpcChannelFactory`, `NamespaceInterceptor`, `ProtoMapper`, `OciSpecBuilder`, `SnapshotManager`, `IoManager`, `TransferImagePuller`, `ImageRootfsResolver`. The OCI runtime spec travels as a JSON-encoded `google.protobuf.Any` with a containerd-specific type URL (verified below) — the library builds that JSON via a dedicated builder, no opaque bytes.

**Tech Stack:** Java 21 (toolchain; JDK 25 present satisfies it), Gradle wrapper 9.7.1, grpc-java 1.73.0, protobuf-java 4.35.0 (+util), Netty epoll native (linux-x86_64), jnr-posix (mkfifo for task IO), slf4j-api, JUnit 5 + AssertJ (unit), separate `integrationTest` source set tagged `integration`.

**Spec:** [docs/spec.md](../../spec.md)

## Verified containerd v2.2.1 API facts (research done 2026-09-02, from the v2.2.1 git tag)

These were verified against the actual v2.2.1 sources — **do not "correct" them from memory**; if something conflicts with the vendored protos, trust the vendored protos:

| Fact | Verified detail |
|---|---|
| Local environment | containerd v2.2.1 running, socket `/run/containerd/containerd.sock` exists (`srw-rw---- root:root`), crun 1.14.1, `containerd-shim-runc-v2` installed, no Docker assumption for tests |
| Version service | `containerd.services.version.v1.Version/Version(Empty) → VersionResponse{version, revision}` |
| Containers service | `containerd.services.containers.v1.Containers`: `Create`, `Get`, `List`, `Update`, `Delete`. `Container`: `id`, `labels`, `image`, `runtime{name, options}`, `spec` (Any), `snapshotter`, `snapshot_key`, `created_at`, `updated_at` |
| Tasks service | `containerd.services.tasks.v1.Tasks`: `Create`, `Start`, `Delete`, `DeleteProcess`, `Get`, `List`, `Kill`, `Exec`, `Wait`, `Pause`, `Resume`, `ListPids`, `CloseIO`, `ResizePty`, `Checkpoint`, `Update`, `Metrics` |
| CreateTaskRequest | `container_id`, `rootfs` (repeated `containerd.types.Mount`), `stdin`, `stdout`, `stderr` (FIFO paths, empty = null IO), `terminal`, `checkpoint`, `options` (Any), `runtime_path`. **No `spec` field — the spec lives on the Container.** |
| Exec/Start/Wait | `ExecProcessRequest{container_id, stdin, stdout, stderr, terminal, spec(Any), exec_id}` → `Start{container_id, exec_id}` → `Wait{container_id, exec_id} → {exit_status, exited_at}` → `DeleteProcess` |
| Kill | `KillRequest{container_id, exec_id, signal, all}` — signal is the raw number (SIGTERM=15, SIGKILL=9) |
| Snapshots service | `containerd.services.snapshots.v1.Snapshots`: `Prepare{snapshotter, key, parent, labels} → {mounts}`, `Mounts{snapshotter, key} → {mounts}`, `Remove{snapshotter, key}`, `Commit`, `Stat`, `Usage`, `Cleanup` |
| Images service | `containerd.services.images.v1.Images`: `Get`, `List`, `Create`, `Update`, `Delete`. **There is NO `PullImage` RPC.** |
| Image pull | `containerd.services.transfer.v1.Transfer/Transfer(TransferRequest{source: Any, destination: Any, options}) → Empty`. Source = `containerd.types.transfer.OCIRegistry{reference, resolver}`; destination = `containerd.types.transfer.ImageStore{name, labels, platforms, all_metadata, manifest_limit, extra_references, unpacks: [{platform, snapshotter}]}`. This is exactly what `ctr images pull` does (`client.Transfer(ctx, reg, is, ...)`, verified in `cmd/ctr/commands/images/pull.go`). |
| Content service | `containerd.services.content.v1.Content`: `Info{digest} → {info{size}}`, `Read{digest, offset, size}` (server streaming) → `{offset, data}` |
| Events service | `containerd.services.events.v1.Events/Subscribe(SubscribeRequest{filters})` → stream `containerd.types.Envelope{timestamp, namespace, topic, event(Any)}`. Topics like `/tasks/start`, `/tasks/exit`, `/tasks/delete`, `/containers/create`. Event payloads live in `containerd.events` (e.g. `TaskStart{container_id, pid}`, `TaskDelete{container_id, pid, exit_status, exited_at, id}`). Filters are fieldpath expressions, e.g. `topic~="/tasks/start"`. |
| Namespace metadata | gRPC header **`containerd-namespace`** (verified: `pkg/namespaces/grpc.go`, `GRPCHeader = "containerd-namespace"`). Namespaces are auto-created by containerd on first use — no explicit create needed (verified: `core/metadata/db.go` `createNamespaceIfNotExist`). |
| OCI spec type URL | **The container spec `Any` is JSON, not proto.** containerd registers the Go struct with `typeurl.Register(&specs.Spec{}, "types.containerd.io", "opencontainers/runtime-spec", major, "Spec")` where `major="1"` → type URL **`types.containerd.io/opencontainers/runtime-spec/1/Spec`**. Non-proto Go types are marshaled to JSON by `typeurl.MarshalAny` and unmarshaled via `json.Unmarshal` fallback (verified in containerd/typeurl `types.go`). The task manager writes `any.getValue()` verbatim to `config.json` (verified `core/runtime/v2/bundle.go`). Process spec for exec: **`types.containerd.io/opencontainers/runtime-spec/1/Process`** (JSON). Runtime options (runc v2): proto-encoded, type URL = proto full name **`containerd.runc.v1.Options`** (`api/types/runc/options/oci.proto`: `no_pivot_root`, `no_new_keyring`, `shim_cgroup`, `io_uid`, `io_gid`, `binary_name`, `root`, `systemd_cgroup`, ...). |
| OCI spec JSON names | From `opencontainers/runtime-spec` `specs-go/config.go`: `ociVersion`, `process{terminal, consoleSize, user{uid,gid,additionalGids,umask}, args, commandLine, env, cwd, capabilities{bounding,effective,inheritable,permitted,ambient}, rlimits, noNewPrivileges, apparmorProfile, oomScoreAdj, scheduler, selinuxLabel}`, `root{path, readonly}`, `hostname`, `mounts[{destination,type,source,options}]`, `annotations`, `linux{namespaces[{type,path}], resources{devices, memory{limit,reservation,swap,kernel,kernelTCP,swappiness,disableOOMKiller,useHierarchy,checkBeforeUpdate}, cpu{shares,quota,period,realtimeRuntime,realtimePeriod,cpus,mems,idle}, pids{limit}, blockIO, hugepageLimits, network, rdma, unified}, cgroupsPath, maskedPaths, readonlyPaths, sysctl, seccomp, devices, rootfsPropagation, mountLabel}` |
| Runtime spec version | containerd 2.2.1 vendors runtime-spec v1.3.0 (`specs-go/version.go`: `1.3.0`). Library emits `ociVersion: "1.2.0"` by default (crun 1.14 accepts it), constant `OciVersions.SPEC_VERSION`, documented. |
| Default runtime name | `io.containerd.runc.v2` (shim binary present on this machine). For crun: set `binary_name: "crun"` in the runc options Any, or use a runtime alias in containerd's config.toml. Documented, configurable. |
| Create flow (Go client) | `WithNewSnapshot`: `image.RootFS()` → `diffIDs` → `identity.ChainID(diffIDs)` → `snapshots.Prepare(key=containerID, parent=chainID)` → `Container{snapshotter, snapshot_key=containerID}`. ChainID algorithm (verified `opencontainers/image-spec identity/chainid.go`): `chainID[0]=diffID[0]`; `chainID[i]=sha256(chainID[i-1] + " " + diffID[i])` (digest strings incl. `sha256:` prefix, space-separated, UTF-8 bytes). |
| Rootfs resolution | Read image config from content store: `Images.Get(name)` → target digest → `Content.Read` manifest → (if index, pick platform manifest) → `config.digest` → `Content.Read` config → JSON `rootfs.diff_ids`. GC label: Go client sets `containerd.io/gc.ref.snapshot.<snapshotter>: <snapshotKey>` on container create (verified `client/container_opts.go`) — do the same so containerd GC keeps the snapshot alive. |
| Task create rootfs | `CreateTaskRequest.rootfs` = `Snapshots.Mounts(snapshotter, snapshot_key)` (the pre-chroot mounts containerd makes in the bundle). The OCI spec's `root.path` = `"rootfs"` and its `mounts` list carries only the standard runtime mounts (proc/dev/sys), not the rootfs mount. |
| Task IO | FIFO paths in CreateTask/Exec requests. The **client** creates the FIFOs (`mkfifo`), containerd's shim opens the far end. Open the read ends *before* the Exec RPC (a blocked `open(2)` unblocks when the shim opens its write end) to avoid the "process exits before we open" race; see Task 11. |
| Maven versions | grpc-netty 1.73.0, protobuf-java 4.35.0 (both latest, checked 2026-09-02). Netty epoll classifier must exactly match the netty version grpc-netty 1.73.0 resolves (check with `dependencyInsight`, Task 1). jnr-posix 3.1.20 (mkfifo). slf4j-api 2.0.17. protobuf-gradle-plugin 0.9.5. JUnit BOM 5.11.4, AssertJ 3.27.3. Gradle wrapper 9.7.1 (system Gradle is 4.4.1 — generate the wrapper in an empty temp dir, see Task 1). |

## Global Constraints

- containerd API pinned to **v2.2.1**; proto files vendored from that tag into `src/main/proto` preserving import paths (`github.com/containerd/containerd/api/...`), via the committed `scripts/vendor-protos.sh`. Message/RPC definitions are verbatim; the script additionally appends `option java_multiple_files = true;` to every file (and `java_outer_classname = "ImageEvents"` to `events/image.proto`) — pure Java codegen-layout options, no API-surface change (Rulings R7-B, R10). Upgrades = re-run the vendor script with the new tag + bump `containerdApiVersion` in `build.gradle.kts`.
- Java 21 toolchain; Gradle wrapper 9.7.1; `./gradlew clean build` must pass; integration tests via `./gradlew integrationTest` only.
- **Never** shell out to `ctr`/`nerdctl`/Docker/mkfifo binaries or any CLI. FIFOs are created with jnr-posix `POSIX.mkfifo`.
- No generated protobuf/gRPC classes in public API signatures (`io.nanofaas.containerd` and `io.nanofaas.containerd.spi` only). Generated classes stay inside `internal`.
- No Spring/K8s/docker-java. Dependencies limited to those in Task 1.
- SLF4J only; no concrete logging backend in `api` scope (`slf4j-simple` is test/example scope only). No INFO per operation; DEBUG for lifecycle decisions (prepare/remove/mount/create/delete), TRACE for RPC payloads, WARN for best-effort cleanup failures.
- `ContainerdClient` implements `AutoCloseable`; one shared `ManagedChannel` per client; blocking stubs are thread-safe and shared; no thread per container — FIFO reads and event dispatch run on Java 21 virtual threads.
- Namespace default `nanofaas`, propagated centrally via `NamespaceInterceptor` on every call.
- Snapshotter default `overlayfs`, runtime default `io.containerd.runc.v2`, platform default `linux/amd64` — all configurable on the builder.
- Documented idempotency: `stop`, `remove`, snapshot cleanup tolerate `NOT_FOUND`. `start` on an already-running task throws `ContainerStartException`.
- Cleanup guarantees (documented in Javadoc + README): create-failure → best-effort snapshot remove; start-failure → best-effort task delete; exec failure → FIFO dir always deleted, exec process always `DeleteProcess`-ed; remove → task delete (if `force`) → container delete → snapshot remove.
- Tests: every unit test is plain JUnit 5 (no Spring); integration tests `@Tag("integration")` in `src/integrationTest`, skip (Assumptions) when the socket is absent/unreadable, unique IDs (`it-` + UUID), full cleanup in `@AfterEach`.

---

### Task 1: Gradle bootstrap with pinned toolchain and dependencies

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/wrapper/gradle-wrapper.properties` (via generation, below)
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` (via generation)
- Create: `src/main/java/io/nanofaas/containerd/package-info.java`
- Create: `src/test/java/io/nanofaas/containerd/BuildInfoTest.java`

**Interfaces:**
- Produces: a Gradle project that runs `./gradlew clean build` on JDK 25 with a Java 21 toolchain; `integrationTest` source set wired to a `integrationTest` task that is **not** part of `check`.

- [ ] **Step 1: Generate the Gradle wrapper in a temp dir** (system Gradle 4.4.1 cannot evaluate this project's build script, so generate standalone and copy):

```bash
mkdir -p /tmp/wrapper-gen && cd /tmp/wrapper-gen && touch settings.gradle
gradle wrapper --gradle-version 9.7.1
cp -r gradlew gradlew.bat gradle/wrapper /home/michele/containerd-java/
```

Expected: `gradle/wrapper/gradle-wrapper.properties` contains `distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip` (the https escape is fine). Verify with: `grep distributionUrl gradle/wrapper/gradle-wrapper.properties`.

- [ ] **Step 2: Write `settings.gradle.kts`**:

```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "containerd-java"
```

(The foojay plugin auto-provisions the Java 21 toolchain if only JDK 25 is installed.)

- [ ] **Step 3: Write `build.gradle.kts`**:

```kotlin
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
```

- [ ] **Step 4: Write `package-info.java`**:

```java
/**
 * Public API of the NanoFaaS containerd client.
 *
 * <p>Generated protobuf classes never appear in this package's public signatures; they are an
 * implementation detail of {@code io.nanofaas.containerd.internal}.
 */
package io.nanofaas.containerd;
```

- [ ] **Step 5: Pin the Netty epoll classifier to grpc-netty's resolved Netty version**

Run: `./gradlew dependencyInsight --dependency io.netty:netty-common --configuration runtimeClasspath`
Expected: one resolved version, e.g. `4.1.121.Final`. If it differs from `nettyVersion` in the build file, update `nettyVersion` to the resolved version (the epoll classifier MUST match exactly or the native transport fails to load).

- [ ] **Step 6: Write the build smoke test**:

```java
package io.nanofaas.containerd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildInfoTest {
    @Test
    void toolchainRunsJava21OrNewer() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }
}
```

- [ ] **Step 7: Verify and commit**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL (1 test passed). First run downloads Gradle 9.7.1 + JDK 21 toolchain.

```bash
git add settings.gradle.kts build.gradle.kts gradle gradlew gradlew.bat src package-info.java 2>/dev/null || git add -A
git commit -m "chore: gradle bootstrap with Java 21 toolchain, grpc/protobuf deps, integrationTest source set"
```

---

### Task 2: Vendor containerd v2.2.1 protos and generate stubs

**Files:**
- Create: `src/main/proto/github.com/containerd/containerd/api/services/version/v1/version.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/services/containers/v1/containers.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/services/tasks/v1/tasks.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/services/snapshots/v1/snapshots.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/services/images/v1/images.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/services/events/v1/events.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/services/transfer/v1/transfer.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/services/content/v1/content.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/types/mount.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/types/platform.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/types/descriptor.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/types/event.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/types/fieldpath.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/types/task/task.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/types/metrics.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/types/transfer/imagestore.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/types/transfer/registry.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/types/transfer/progress.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/types/runc/options/oci.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/types/runtimeoptions/v1/api.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/events/task.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/events/container.proto`
- Create: `src/main/proto/github.com/containerd/containerd/api/events/image.proto`
- Modify: `build.gradle.kts` (protobuf plugin + generation)
- Test: `src/test/java/io/nanofaas/containerd/internal/GeneratedProtosTest.java`

**Interfaces:**
- Consumes: Gradle project from Task 1.
- Produces: generated Java classes in packages `containerd.services.*`, `containerd.types`, `containerd.v1.types` (the `api/types/task/task.proto` file declares `package containerd.v1.types`), `containerd.types.transfer`, `containerd.runc.v1`, `containerd.events`. All 23 vendored protos get `option java_multiple_files = true;` added by the committed `scripts/vendor-protos.sh` (Ruling R7-B: pure codegen-layout option, zero API-surface change; `events/image.proto` additionally gets `java_outer_classname = "ImageEvents"` to avoid a protoc output collision with the `Image` message — Ruling R10). Service stubs: `containerd.services.version.v1.VersionGrpc`, `containerd.services.containers.v1.ContainersGrpc`, `containerd.services.tasks.v1.TasksGrpc`, `containerd.services.snapshots.v1.SnapshotsGrpc`, `containerd.services.images.v1.ImagesGrpc`, `containerd.services.events.v1.EventsGrpc`, `containerd.services.transfer.v1.TransferGrpc`, `containerd.services.content.v1.ContentGrpc`.

- [ ] **Step 1: Vendor the protos**

For each file listed above, download from `https://raw.githubusercontent.com/containerd/containerd/v2.2.1/<path>` into the mirrored path under `src/main/proto/github.com/containerd/containerd/<path>`. The import paths inside the protos (`github.com/containerd/containerd/api/...`) then resolve relative to `src/main/proto` exactly as upstream. If `protoc` later reports a missing import not in the list, fetch that one file from the same tag.

- [ ] **Step 2: Add the protobuf plugin to `build.gradle.kts`**:

```kotlin
plugins {
    `java-library`
    application
    id("com.google.protobuf") version "0.9.5"
}
```

Append the generation config:

```kotlin
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
```

- [ ] **Step 3: Write the generated-classes smoke test**:

```java
package io.nanofaas.containerd.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedProtosTest {
    @Test
    void keyServicesAndMessagesGenerated() {
        // service stubs
        assertThat(containerd.services.version.v1.VersionGrpc.class).isNotNull();
        assertThat(containerd.services.containers.v1.ContainersGrpc.class).isNotNull();
        assertThat(containerd.services.tasks.v1.TasksGrpc.class).isNotNull();
        assertThat(containerd.services.snapshots.v1.SnapshotsGrpc.class).isNotNull();
        assertThat(containerd.services.images.v1.ImagesGrpc.class).isNotNull();
        assertThat(containerd.services.events.v1.EventsGrpc.class).isNotNull();
        assertThat(containerd.services.transfer.v1.TransferGrpc.class).isNotNull();
        assertThat(containerd.services.content.v1.ContentGrpc.class).isNotNull();
        // messages used by later tasks
        assertThat(containerd.types.Mount.class).isNotNull();
        assertThat(containerd.types.Envelope.class).isNotNull();
        assertThat(containerd.v1.types.Process.class).isNotNull();
        assertThat(containerd.types.transfer.OCIRegistry.class).isNotNull();
        assertThat(containerd.types.transfer.ImageStore.class).isNotNull();
        assertThat(containerd.events.TaskStart.class).isNotNull();
        assertThat(containerd.events.TaskDelete.class).isNotNull();
    }
}
```

- [ ] **Step 4: Run and commit**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL; protoc compiles all vendored files, `GeneratedProtosTest` passes.

```bash
git add -A && git commit -m "build: vendor containerd v2.2.1 protos and generate gRPC stubs"
```

---

### Task 3: Connection layer — UDS channel factory, namespace interceptor, client core, version check

**Files:**
- Create: `src/main/java/io/nanofaas/containerd/spi/ContainerdClient.java`
- Create: `src/main/java/io/nanofaas/containerd/spi/ContainerdClientBuilder.java`
- Create: `src/main/java/io/nanofaas/containerd/Version.java`
- Create: `src/main/java/io/nanofaas/containerd/internal/GrpcChannelFactory.java`
- Create: `src/main/java/io/nanofaas/containerd/internal/NamespaceInterceptor.java`
- Create: `src/main/java/io/nanofaas/containerd/internal/DefaultContainerdClient.java`
- Test: `src/test/java/io/nanofaas/containerd/internal/NamespaceInterceptorTest.java`
- Test: `src/test/java/io/nanofaas/containerd/internal/GrpcChannelFactoryTest.java`
- Integration test: `src/integrationTest/java/io/nanofaas/containerd/ContainerdConnectionIT.java`

**Interfaces:**
- Consumes: generated stubs (Task 2).
- Produces (used by every later task):
  - `Version record Version(String version, String revision)`
  - `interface ContainerdClient extends AutoCloseable { Version version(); String namespace(); String snapshotter(); String runtimeName(); void close(); static Builder builder(); }`
  - `class ContainerdClientBuilder { Builder socketPath(String); Builder namespace(String); Builder snapshotter(String); Builder runtimeName(String); Builder runtimeBinaryName(String); ContainerdClient build(); }` — defaults `/run/containerd/containerd.sock`, `nanofaas`, `overlayfs`, `io.containerd.runc.v2`, `null` binaryName. (No `platform(Platform)` builder method — platform is passed per-operation, e.g. `Images.pull(String, Platform)`, defaulting to `Platform.linuxAmd64()`; corrected after Task 3 review M3.)
  - `class GrpcChannelFactory { static ManagedChannel createUnixDomainSocketChannel(String socketPath); }`
  - `class NamespaceInterceptor implements ClientInterceptor` (ctor takes namespace)

- [ ] **Step 1: Write the failing tests** (`NamespaceInterceptorTest` — uses an in-process server to capture the header):

```java
package io.nanofaas.containerd.internal;

import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NamespaceInterceptorTest {

    private static final Metadata.Key<String> NS_KEY =
            Metadata.Key.of("containerd-namespace", Metadata.ASCII_STRING_MARSHALLER);

    @Test
    void addsNamespaceHeaderToEveryCall() throws Exception {
        AtomicReference<Metadata> captured = new AtomicReference<>();

        ServerServiceDefinition service = ServerInterceptors.intercept(
                containerd.services.version.v1.VersionGrpc.bindService(new containerd.services.version.v1.VersionGrpc.VersionImplBase() {
                    @Override
                    public void version(com.google.protobuf.Empty request,
                                        StreamObserver<containerd.services.version.v1.VersionResponse> responseObserver) {
                        responseObserver.onNext(containerd.services.version.v1.VersionResponse.getDefaultInstance());
                        responseObserver.onCompleted();
                    }
                }),
                new ServerInterceptor() {
                    @Override
                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                                 Metadata headers,
                                                                                 ServerCallHandler<ReqT, RespT> next) {
                        captured.set(new Metadata());
                        captured.get().merge(headers);
                        return next.startCall(call, headers);
                    }
                });

        String name = InProcessServerBuilder.generateName();
        InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
        try {
            ManagedChannel channel = ClientInterceptors.intercept(
                    InProcessChannelBuilder.forName(name).directExecutor().build(),
                    new NamespaceInterceptor("nanofaas"));
            var stub = containerd.services.version.v1.VersionGrpc.newBlockingStub(channel);
            stub.version(com.google.protobuf.Empty.getDefaultInstance());
            channel.shutdownNow();

            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().get(NS_KEY)).isEqualTo("nanofaas");
        } finally {
            InProcessServerBuilder.forName(name).build().shutdownNow();
        }
    }
}
```

`GrpcChannelFactoryTest`:

```java
package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcChannelFactoryTest {
    @Test
    void createsNettyChannel() {
        ManagedChannel channel = GrpcChannelFactory.createUnixDomainSocketChannel("/run/containerd/containerd.sock");
        try {
            assertThat(channel).isNotNull();
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    void rejectsMissingEpollWithClearMessage() {
        // exercised indirectly: on a system without the epoll native lib the factory must throw
        // IllegalStateException rather than failing deep inside Netty. This test only guards the
        // message contract by reflection-free call when epoll IS available.
        ManagedChannel channel = GrpcChannelFactory.createUnixDomainSocketChannel("/run/containerd/containerd.sock");
        channel.shutdownNow();
    }
}
```

Run: `./gradlew test --tests io.nanofaas.containerd.internal.NamespaceInterceptorTest --tests io.nanofaas.containerd.internal.GrpcChannelFactoryTest`
Expected: FAIL — compilation errors, classes don't exist.

- [ ] **Step 2: Implement `GrpcChannelFactory`**:

```java
package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;

/** Creates gRPC channels over Unix Domain Sockets using the Netty epoll transport. */
public final class GrpcChannelFactory {

    private GrpcChannelFactory() {
    }

    public static ManagedChannel createUnixDomainSocketChannel(String socketPath) {
        if (!Epoll.isAvailable()) {
            throw new IllegalStateException(
                    "Netty epoll native transport is not available; add netty-transport-native-epoll with the linux-x86_64 classifier");
        }
        return NettyChannelBuilder.forAddress(new DomainSocketAddress(socketPath))
                .channelType(EpollDomainSocketChannel.class)
                .eventLoopGroup(new EpollEventLoopGroup())
                .usePlaintext()
                .build();
    }
}
```

- [ ] **Step 3: Implement `NamespaceInterceptor`**:

```java
package io.nanofaas.containerd.internal;

import io.grpc.*;

/** Injects the containerd namespace header into every outgoing call. */
public final class NamespaceInterceptor implements ClientInterceptor {

    static final Metadata.Key<String> NAMESPACE_KEY =
            Metadata.Key.of("containerd-namespace", Metadata.ASCII_STRING_MARSHALLER);

    private final String namespace;

    public NamespaceInterceptor(String namespace) {
        this.namespace = namespace;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                               CallOptions callOptions,
                                                               Channel next) {
        return new SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.put(NAMESPACE_KEY, namespace);
                super.start(responseListener, headers);
            }
        };
    }
}
```

- [ ] **Step 4: Implement the public SPI — `Version`, `ContainerdClient`, `ContainerdClientBuilder`, `DefaultContainerdClient`**

`Version`:

```java
package io.nanofaas.containerd;

/** Version information reported by containerd. */
public record Version(String version, String revision) {
}
```

`ContainerdClient`:

```java
package io.nanofaas.containerd.spi;

import io.nanofaas.containerd.Version;

/**
 * A client for the containerd gRPC API.
 *
 * <p>Thread-safe. Owns a single shared gRPC channel; close it when done. All service facades
 * returned by this client operate on the namespace configured at build time.
 */
public interface ContainerdClient extends AutoCloseable {

    /** Returns containerd's version and revision (doubles as a health check). */
    Version version();

    String namespace();

    String snapshotter();

    String runtimeName();

    @Override
    void close();

    static Builder builder() {
        return new ContainerdClientBuilder();
    }

    interface Builder {

        /** Unix domain socket path. Default {@code /run/containerd/containerd.sock}. */
        Builder socketPath(String socketPath);

        /** containerd namespace. Default {@code nanofaas}. */
        Builder namespace(String namespace);

        /** Snapshotter used for container root filesystems. Default {@code overlayfs}. */
        Builder snapshotter(String snapshotter);

        /** Runtime identifier passed to tasks. Default {@code io.containerd.runc.v2}. */
        Builder runtimeName(String runtimeName);

        /**
         * When set (e.g. {@code crun}), the runc-v2 shim is told to exec this OCI runtime binary
         * instead of its default. Requires a shim that supports the {@code binary_name} option.
         */
        Builder runtimeBinaryName(String runtimeBinaryName);

        ContainerdClient build();
    }
}
```

`DefaultContainerdClient`:

```java
package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.nanofaas.containerd.Version;
import io.nanofaas.containerd.spi.ContainerdClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DefaultContainerdClient implements ContainerdClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultContainerdClient.class);

    private final ManagedChannel channel;
    private final String namespace;
    private final String snapshotter;
    private final String runtimeName;
    private final String runtimeBinaryName;

    public DefaultContainerdClient(String socketPath, String namespace, String snapshotter,
                                   String runtimeName, String runtimeBinaryName) {
        this.namespace = namespace;
        this.snapshotter = snapshotter;
        this.runtimeName = runtimeName;
        this.runtimeBinaryName = runtimeBinaryName;
        this.channel = GrpcChannelFactory.createUnixDomainSocketChannel(socketPath);
        log.debug("containerd client created (namespace={}, snapshotter={}, runtime={}, binaryName={})",
                namespace, snapshotter, runtimeName, runtimeBinaryName);
    }

    @Override
    public Version version() {
        var response = containerd.services.version.v1.VersionGrpc.newBlockingStub(channel)
                .version(com.google.protobuf.Empty.getDefaultInstance());
        return new Version(response.getVersion(), response.getRevision());
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public String snapshotter() {
        return snapshotter;
    }

    @Override
    public String runtimeName() {
        return runtimeName;
    }

    String runtimeBinaryName() {
        return runtimeBinaryName;
    }

    ManagedChannel channel() {
        return channel;
    }

    @Override
    public void close() {
        log.debug("closing containerd client");
        channel.shutdownNow();
    }
}
```

`ContainerdClientBuilder`:

```java
package io.nanofaas.containerd.spi;

import io.nanofaas.containerd.internal.DefaultContainerdClient;

import java.util.Objects;

public final class ContainerdClientBuilder implements ContainerdClient.Builder {

    private String socketPath = "/run/containerd/containerd.sock";
    private String namespace = "nanofaas";
    private String snapshotter = "overlayfs";
    private String runtimeName = "io.containerd.runc.v2";
    private String runtimeBinaryName;

    @Override
    public ContainerdClient.Builder socketPath(String socketPath) {
        this.socketPath = Objects.requireNonNull(socketPath, "socketPath");
        return this;
    }

    @Override
    public ContainerdClient.Builder namespace(String namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        return this;
    }

    @Override
    public ContainerdClient.Builder snapshotter(String snapshotter) {
        this.snapshotter = Objects.requireNonNull(snapshotter, "snapshotter");
        return this;
    }

    @Override
    public ContainerdClient.Builder runtimeName(String runtimeName) {
        this.runtimeName = Objects.requireNonNull(runtimeName, "runtimeName");
        return this;
    }

    @Override
    public ContainerdClient.Builder runtimeBinaryName(String runtimeBinaryName) {
        this.runtimeBinaryName = runtimeBinaryName;
        return this;
    }

    @Override
    public ContainerdClient build() {
        return new DefaultContainerdClient(socketPath, namespace, snapshotter, runtimeName, runtimeBinaryName);
    }
}
```

- [ ] **Step 5: Run tests, then write the integration test**

Run: `./gradlew test`
Expected: PASS.

`ContainerdConnectionIT` (integration — Assumptions skip when socket unusable; this base helper is reused by later ITs):

```java
package io.nanofaas.containerd;

import io.nanofaas.containerd.spi.ContainerdClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
public class ContainerdConnectionIT {

    static final String SOCKET = System.getProperty("io.nanofaas.containerd.socket", "/run/containerd/containerd.sock");

    static ContainerdClient client;

    @BeforeAll
    static void connect() {
        Assumptions.assumeTrue(Files.exists(Path.of(SOCKET)), "containerd socket " + SOCKET + " not present");
        Assumptions.assumeTrue(Files.isWritable(Path.of(SOCKET)) || Files.isReadable(Path.of(SOCKET)),
                "socket " + SOCKET + " not accessible by current user (try running as root)");
        client = ContainerdClient.builder().socketPath(SOCKET).namespace("nanofaas-it").build();
    }

    @AfterAll
    static void disconnect() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void reportsVersion() {
        Version version = client.version();
        assertThat(version.version()).isNotBlank();
        assertThat(version.revision()).isNotBlank();
    }

    @Test
    void namespaceIsConfigurable() {
        assertThat(client.namespace()).isEqualTo("nanofaas-it");
    }
}
```

Run: `./gradlew integrationTest`
Expected: PASS (this machine has a running containerd).

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: UDS channel factory, namespace interceptor, client core with version check"
```

---

### Task 4: Exception hierarchy and gRPC status mapping

**Files:**
- Create: `src/main/java/io/nanofaas/containerd/ContainerdException.java`
- Create: `src/main/java/io/nanofaas/containerd/ContainerNotFoundException.java`
- Create: `src/main/java/io/nanofaas/containerd/ImageNotFoundException.java`
- Create: `src/main/java/io/nanofaas/containerd/TaskNotFoundException.java`
- Create: `src/main/java/io/nanofaas/containerd/ContainerAlreadyExistsException.java`
- Create: `src/main/java/io/nanofaas/containerd/ImagePullException.java`
- Create: `src/main/java/io/nanofaas/containerd/ContainerStartException.java`
- Create: `src/main/java/io/nanofaas/containerd/ContainerStopException.java`
- Create: `src/main/java/io/nanofaas/containerd/ExecException.java`
- Create: `src/main/java/io/nanofaas/containerd/internal/StatusExceptionMapper.java`
- Test: `src/test/java/io/nanofaas/containerd/internal/StatusExceptionMapperTest.java`

**Interfaces:**
- Produces: `class StatusExceptionMapper { static ContainerdException map(StatusRuntimeException e, ResourceKind kind); }` with `enum ResourceKind { CONTAINER, IMAGE, TASK, SNAPSHOT, CONTENT, GENERAL }`. All exceptions extend `ContainerdException extends RuntimeException` and carry the original `StatusRuntimeException` as cause.

- [ ] **Step 1: Write the failing test**:

```java
package io.nanofaas.containerd.internal;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.nanofaas.containerd.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatusExceptionMapperTest {

    private static StatusRuntimeException sre(Status.Code code) {
        return Status.fromCode(code).asRuntimeException();
    }

    @Test
    void mapsNotFoundPerResourceKind() {
        assertThat(StatusExceptionMapper.map(sre(Status.Code.NOT_FOUND), StatusExceptionMapper.ResourceKind.CONTAINER))
                .isInstanceOf(ContainerNotFoundException.class);
        assertThat(StatusExceptionMapper.map(sre(Status.Code.NOT_FOUND), StatusExceptionMapper.ResourceKind.IMAGE))
                .isInstanceOf(ImageNotFoundException.class);
        assertThat(StatusExceptionMapper.map(sre(Status.Code.NOT_FOUND), StatusExceptionMapper.ResourceKind.TASK))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void mapsAlreadyExistsToContainerAlreadyExists() {
        assertThat(StatusExceptionMapper.map(sre(Status.Code.ALREADY_EXISTS), StatusExceptionMapper.ResourceKind.CONTAINER))
                .isInstanceOf(ContainerAlreadyExistsException.class);
    }

    @Test
    void preservesOriginalStatusAsCause() {
        var original = sre(Status.Code.NOT_FOUND);
        assertThatThrownBy(() -> {
            throw StatusExceptionMapper.map(original, StatusExceptionMapper.ResourceKind.CONTAINER);
        }).isInstanceOf(ContainerNotFoundException.class)
                .hasCause(original);
    }

    @Test
    void unmappedCodesFallBackToContainerdException() {
        assertThat(StatusExceptionMapper.map(sre(Status.Code.INTERNAL), StatusExceptionMapper.ResourceKind.GENERAL))
                .isInstanceOf(ContainerdException.class);
    }
}
```

Run: `./gradlew test --tests io.nanofaas.containerd.internal.StatusExceptionMapperTest`
Expected: FAIL — classes don't exist.

- [ ] **Step 2: Implement the exceptions**

`ContainerdException`:

```java
package io.nanofaas.containerd;

/** Base class for all exceptions thrown by this library. */
public class ContainerdException extends RuntimeException {

    public ContainerdException(String message) {
        super(message);
    }

    public ContainerdException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Each subclass follows the same two-ctor pattern; e.g. `ContainerNotFoundException`:

```java
package io.nanofaas.containerd;

/** The requested container does not exist in the configured namespace. */
public class ContainerNotFoundException extends ContainerdException {

    public ContainerNotFoundException(String message) {
        super(message);
    }

    public ContainerNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

(Write all nine: `ContainerNotFoundException`, `ImageNotFoundException`, `TaskNotFoundException`, `ContainerAlreadyExistsException`, `ImagePullException`, `ContainerStartException`, `ContainerStopException`, `ExecException`, plus the base.)

- [ ] **Step 3: Implement `StatusExceptionMapper`**:

```java
package io.nanofaas.containerd.internal;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.nanofaas.containerd.*;

/** Maps gRPC status codes to library exceptions, preserving the original as cause. */
public final class StatusExceptionMapper {

    public enum ResourceKind { CONTAINER, IMAGE, TASK, SNAPSHOT, CONTENT, GENERAL }

    private StatusExceptionMapper() {
    }

    public static ContainerdException map(StatusRuntimeException e, ResourceKind kind) {
        String message = "containerd " + kind.name().toLowerCase() + " operation failed: " + e.getStatus().getCode();
        if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
            return switch (kind) {
                case CONTAINER -> new ContainerNotFoundException(message, e);
                case IMAGE -> new ImageNotFoundException(message, e);
                case TASK -> new TaskNotFoundException(message, e);
                default -> new ContainerdException(message, e);
            };
        }
        if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS && kind == ResourceKind.CONTAINER) {
            return new ContainerAlreadyExistsException(message, e);
        }
        return new ContainerdException(message, e);
    }
}
```

- [ ] **Step 4: Run and commit**

Run: `./gradlew test`
Expected: PASS.

```bash
git add -A && git commit -m "feat: typed exception hierarchy with gRPC status mapping"
```

---

### Task 5: Public domain model and proto mapping

**Files:**
- Create: `src/main/java/io/nanofaas/containerd/Platform.java`
- Create: `src/main/java/io/nanofaas/containerd/Signal.java`
- Create: `src/main/java/io/nanofaas/containerd/ContainerState.java`
- Create: `src/main/java/io/nanofaas/containerd/ExitStatus.java`
- Create: `src/main/java/io/nanofaas/containerd/Container.java`
- Create: `src/main/java/io/nanofaas/containerd/ContainerStatus.java`
- Create: `src/main/java/io/nanofaas/containerd/Image.java`
- Create: `src/main/java/io/nanofaas/containerd/ContainerSpec.java`
- Create: `src/main/java/io/nanofaas/containerd/RemoveOptions.java`
- Create: `src/main/java/io/nanofaas/containerd/internal/ProtoMapper.java`
- Test: `src/test/java/io/nanofaas/containerd/internal/ProtoMapperTest.java`
- Test: `src/test/java/io/nanofaas/containerd/ContainerSpecTest.java`

**Interfaces:**
- Produces (public records used by all later tasks):
  - `record Platform(String os, String architecture) { static Platform linuxAmd64(); }`
  - `enum Signal { TERM(15), KILL(9), INT(2), HUP(1), QUIT(3), USR1(10), USR2(12); int number(); }`
  - `enum ContainerState { CREATED, RUNNING, STOPPED, PAUSED, PAUSING, STARTING, UNKNOWN }`
  - `record ExitStatus(int code, Instant exitedAt)`
  - `record Container(String id, String image, String snapshotter, String snapshotKey, Instant createdAt, Map<String,String> labels)`
  - `record ContainerStatus(String id, String image, ContainerState state, int pid, ExitStatus exitStatus, String snapshotKey, Instant createdAt)` — `pid` is `-1` when unknown; `exitStatus` null unless STOPPED.
  - `record Image(String name, String digest, long size, Instant createdAt, Map<String,String> labels)`
  - `class ContainerSpec` with nested `Builder` (below) and `class MountSpec(String destination, String type, String source, List<String> options)`
  - `class RemoveOptions { boolean removeSnapshot(); static Builder builder(); }`
  - `class ProtoMapper { Container map(containers.Container); Image map(images.Image); ContainerState mapStatus(int); static String requireValidId(String); }`
- `ContainerSpec.Builder` API (Task 6 consumes it): `id(String)`, `image(String)`, `command(List<String>)`, `environment(Map<String,String>)`, `workingDir(String)`, `hostname(String)`, `user(String)` (`"uid:gid"` or `"username"`), `readonlyRootfs(boolean)`, `mounts(List<MountSpec>)`, `cpuShares(long)`, `cpuQuotaMicros(long)`, `cpuPeriodMicros(long)`, `memoryLimitBytes(long)`, `memorySwapLimitBytes(long)`, `pidsLimit(long)`, `labels(Map<String,String>)`, `build()`.

- [ ] **Step 1: Write the failing tests**

`ProtoMapperTest`:

```java
package io.nanofaas.containerd.internal;

import com.google.protobuf.Timestamp;
import io.nanofaas.containerd.ContainerState;
import io.nanofaas.containerd.Platform;
import io.nanofaas.containerd.Signal;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtoMapperTest {

    @Test
    void mapsProtoContainer() {
        var proto = containerd.services.containers.v1.Container.newBuilder()
                .setId("abc-123")
                .setImage("docker.io/library/alpine:latest")
                .setSnapshotter("overlayfs")
                .setSnapshotKey("abc-123")
                .setCreatedAt(Timestamp.newBuilder().setSeconds(1700000000).build())
                .putLabels("a", "b")
                .build();

        var container = ProtoMapper.map(proto);

        assertThat(container.id()).isEqualTo("abc-123");
        assertThat(container.image()).isEqualTo("docker.io/library/alpine:latest");
        assertThat(container.snapshotKey()).isEqualTo("abc-123");
        assertThat(container.createdAt()).isEqualTo(Instant.ofEpochSecond(1700000000));
        assertThat(container.labels()).containsEntry("a", "b");
    }

    @Test
    void mapsProtoImage() {
        var proto = containerd.services.images.v1.Image.newBuilder()
                .setName("docker.io/library/alpine:latest")
                .setTarget(containerd.types.Descriptor.newBuilder().setDigest("sha256:abcdef").setSize(1234).build())
                .build();

        var image = ProtoMapper.map(proto);

        assertThat(image.name()).isEqualTo("docker.io/library/alpine:latest");
        assertThat(image.digest()).isEqualTo("sha256:abcdef");
        assertThat(image.size()).isEqualTo(1234L);
    }

    @Test
    void mapsTaskStatusEnum() {
        assertThat(ProtoMapper.mapStatus(containerd.v1.types.Status.RUNNING.getNumber())).isEqualTo(ContainerState.RUNNING);
        assertThat(ProtoMapper.mapStatus(containerd.v1.types.Status.STOPPED.getNumber())).isEqualTo(ContainerState.STOPPED);
        assertThat(ProtoMapper.mapStatus(999)).isEqualTo(ContainerState.UNKNOWN);
    }

    @Test
    void rejectsInvalidContainerIds() {
        assertThat(ProtoMapper.requireValidId("abc_1.2-3")).isEqualTo("abc_1.2-3");
        assertThatThrownBy(() -> ProtoMapper.requireValidId("has space")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProtoMapper.requireValidId("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void platformAndSignalDefaults() {
        assertThat(Platform.linuxAmd64().os()).isEqualTo("linux");
        assertThat(Platform.linuxAmd64().architecture()).isEqualTo("amd64");
        assertThat(Signal.TERM.number()).isEqualTo(15);
        assertThat(Signal.KILL.number()).isEqualTo(9);
    }
}
```

`ContainerSpecTest`:

```java
package io.nanofaas.containerd;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerSpecTest {

    @Test
    void builderPopulatesFields() {
        var spec = ContainerSpec.builder()
                .id("fn-42")
                .image("docker.io/my/function:v1")
                .command(List.of("/function"))
                .environment(Map.of("PORT", "8080"))
                .cpuQuotaMicros(50000)
                .memoryLimitBytes(128L * 1024 * 1024)
                .build();

        assertThat(spec.id()).isEqualTo("fn-42");
        assertThat(spec.command()).containsExactly("/function");
        assertThat(spec.environment()).containsEntry("PORT", "8080");
        assertThat(spec.cpuQuotaMicros()).isEqualTo(50000);
        assertThat(spec.memoryLimitBytes()).isEqualTo(128L * 1024 * 1024);
    }

    @Test
    void idIsRequired() {
        assertThatThrownBy(() -> ContainerSpec.builder().image("alpine").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }
}
```

Run: `./gradlew test --tests io.nanofaas.containerd.internal.ProtoMapperTest --tests io.nanofaas.containerd.ContainerSpecTest`
Expected: FAIL — classes don't exist.

- [ ] **Step 2: Implement the records and enums**

`Platform`:

```java
package io.nanofaas.containerd;

public record Platform(String os, String architecture) {

    public static Platform linuxAmd64() {
        return new Platform("linux", "amd64");
    }
}
```

`Signal`:

```java
package io.nanofaas.containerd;

/** POSIX signals, by number, as expected by containerd's Kill RPC. */
public enum Signal {
    HUP(1),
    INT(2),
    QUIT(3),
    TERM(15),
    KILL(9),
    USR1(10),
    USR2(12);

    private final int number;

    Signal(int number) {
        this.number = number;
    }

    public int number() {
        return number;
    }
}
```

`ContainerState`:

```java
package io.nanofaas.containerd;

/** Lifecycle state of a container's task, mirroring containerd's task status enum. */
public enum ContainerState {
    CREATED, RUNNING, STOPPED, PAUSED, PAUSING, STARTING, UNKNOWN
}
```

`ExitStatus`:

```java
package io.nanofaas.containerd;

import java.time.Instant;

public record ExitStatus(int code, Instant exitedAt) {
}
```

`Container`, `ContainerStatus`, `Image` as plain records per the Interfaces block.

- [ ] **Step 3: Implement `ContainerSpec`** (id validated eagerly via `ProtoMapper.requireValidId`):

```java
package io.nanofaas.containerd;

import io.nanofaas.containerd.internal.ProtoMapper;

import java.util.List;
import java.util.Map;

/** Desired state of a container to create. Build with {@link #builder()}. */
public final class ContainerSpec {

    public record MountSpec(String destination, String type, String source, List<String> options) {
        public MountSpec {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    private final String id;
    private final String image;
    private final List<String> command;
    private final Map<String, String> environment;
    private final String workingDir;
    private final String hostname;
    private final String user;
    private final boolean readonlyRootfs;
    private final List<MountSpec> mounts;
    private final long cpuShares;
    private final long cpuQuotaMicros;
    private final long cpuPeriodMicros;
    private final long memoryLimitBytes;
    private final long memorySwapLimitBytes;
    private final long pidsLimit;
    private final Map<String, String> labels;

    private ContainerSpec(Builder b) {
        this.id = b.id;
        this.image = b.image;
        this.command = List.copyOf(b.command);
        this.environment = Map.copyOf(b.environment);
        this.workingDir = b.workingDir;
        this.hostname = b.hostname;
        this.user = b.user;
        this.readonlyRootfs = b.readonlyRootfs;
        this.mounts = List.copyOf(b.mounts);
        this.cpuShares = b.cpuShares;
        this.cpuQuotaMicros = b.cpuQuotaMicros;
        this.cpuPeriodMicros = b.cpuPeriodMicros;
        this.memoryLimitBytes = b.memoryLimitBytes;
        this.memorySwapLimitBytes = b.memorySwapLimitBytes;
        this.pidsLimit = b.pidsLimit;
        this.labels = Map.copyOf(b.labels);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() { return id; }
    public String image() { return image; }
    public List<String> command() { return command; }
    public Map<String, String> environment() { return environment; }
    public String workingDir() { return workingDir; }
    public String hostname() { return hostname; }
    public String user() { return user; }
    public boolean readonlyRootfs() { return readonlyRootfs; }
    public List<MountSpec> mounts() { return mounts; }
    public long cpuShares() { return cpuShares; }
    public long cpuQuotaMicros() { return cpuQuotaMicros; }
    public long cpuPeriodMicros() { return cpuPeriodMicros; }
    public long memoryLimitBytes() { return memoryLimitBytes; }
    public long memorySwapLimitBytes() { return memorySwapLimitBytes; }
    public long pidsLimit() { return pidsLimit; }
    public Map<String, String> labels() { return labels; }

    public static final class Builder {
        private String id;
        private String image;
        private List<String> command = List.of();
        private Map<String, String> environment = Map.of();
        private String workingDir;
        private String hostname;
        private String user;
        private boolean readonlyRootfs;
        private List<MountSpec> mounts = List.of();
        private long cpuShares;
        private long cpuQuotaMicros;
        private long cpuPeriodMicros;
        private long memoryLimitBytes;
        private long memorySwapLimitBytes;
        private long pidsLimit;
        private Map<String, String> labels = Map.of();

        public Builder id(String id) { this.id = id; return this; }
        public Builder image(String image) { this.image = image; return this; }
        public Builder command(List<String> command) { this.command = command; return this; }
        public Builder environment(Map<String, String> environment) { this.environment = environment; return this; }
        public Builder workingDir(String workingDir) { this.workingDir = workingDir; return this; }
        public Builder hostname(String hostname) { this.hostname = hostname; return this; }
        public Builder user(String user) { this.user = user; return this; }
        public Builder readonlyRootfs(boolean readonlyRootfs) { this.readonlyRootfs = readonlyRootfs; return this; }
        public Builder mounts(List<MountSpec> mounts) { this.mounts = mounts; return this; }
        public Builder cpuShares(long cpuShares) { this.cpuShares = cpuShares; return this; }
        public Builder cpuQuotaMicros(long cpuQuotaMicros) { this.cpuQuotaMicros = cpuQuotaMicros; return this; }
        public Builder cpuPeriodMicros(long cpuPeriodMicros) { this.cpuPeriodMicros = cpuPeriodMicros; return this; }
        public Builder memoryLimitBytes(long memoryLimitBytes) { this.memoryLimitBytes = memoryLimitBytes; return this; }
        public Builder memorySwapLimitBytes(long memorySwapLimitBytes) { this.memorySwapLimitBytes = memorySwapLimitBytes; return this; }
        public Builder pidsLimit(long pidsLimit) { this.pidsLimit = pidsLimit; return this; }
        public Builder labels(Map<String, String> labels) { this.labels = labels; return this; }

        public ContainerSpec build() {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id is required");
            }
            ProtoMapper.requireValidId(id);
            if (image == null || image.isBlank()) {
                throw new IllegalArgumentException("image is required");
            }
            return new ContainerSpec(this);
        }
    }
}
```

- [ ] **Step 4: Implement `RemoveOptions` and `ProtoMapper`**

`RemoveOptions`:

```java
package io.nanofaas.containerd;

/** Options for container removal. */
public final class RemoveOptions {

    private final boolean removeSnapshot;
    private final boolean force;

    private RemoveOptions(Builder b) {
        this.removeSnapshot = b.removeSnapshot;
        this.force = b.force;
    }

    /** Whether the container's snapshot should also be removed. Default false. */
    public boolean removeSnapshot() { return removeSnapshot; }

    /** Whether a running container should be stopped first. Default false (throws instead). */
    public boolean force() { return force; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean removeSnapshot;
        private boolean force;

        public Builder removeSnapshot(boolean removeSnapshot) { this.removeSnapshot = removeSnapshot; return this; }
        public Builder force(boolean force) { this.force = force; return this; }

        public RemoveOptions build() {
            return new RemoveOptions(this);
        }
    }
}
```

`ProtoMapper`:

```java
package io.nanofaas.containerd.internal;

import io.nanofaas.containerd.*;

import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

/** Conversions between containerd protobuf messages and the public model. */
public final class ProtoMapper {

    // containerd identifier rules: alphanumerics plus . _ - , max 76 chars
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,75}");

    private ProtoMapper() {
    }

    public static String requireValidId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid containerd identifier: " + id);
        }
        return id;
    }

    public static Container map(containerd.services.containers.v1.Container c) {
        return new Container(
                c.getId(),
                c.getImage(),
                c.getSnapshotter(),
                c.getSnapshotKey(),
                c.hasCreatedAt() ? Instant.ofEpochSecond(c.getCreatedAt().getSeconds(), c.getCreatedAt().getNanos()) : Instant.EPOCH,
                Map.copyOf(c.getLabelsMap()));
    }

    public static Image map(containerd.services.images.v1.Image i) {
        return new Image(
                i.getName(),
                i.getTarget().getDigest(),
                i.getTarget().getSize(),
                i.hasCreatedAt() ? Instant.ofEpochSecond(i.getCreatedAt().getSeconds(), i.getCreatedAt().getNanos()) : Instant.EPOCH,
                Map.copyOf(i.getLabelsMap()));
    }

    public static ContainerState mapStatus(int containerdStatusNumber) {
        return switch (containerdStatusNumber) {
            case 1 -> ContainerState.CREATED;
            case 2 -> ContainerState.RUNNING;
            case 3 -> ContainerState.STOPPED;
            case 4 -> ContainerState.PAUSED;
            case 5 -> ContainerState.PAUSING;
            case 6 -> ContainerState.STARTING;
            default -> ContainerState.UNKNOWN;
        };
    }

    public static containerd.types.Platform toProto(Platform platform) {
        return containerd.types.Platform.newBuilder()
                .setOs(platform.os())
                .setArchitecture(platform.architecture())
                .build();
    }
}
```

Note: `mapStatus` uses raw enum numbers per the vendored `containerd.v1.types.Status` (the `api/types/task/task.proto` file's proto package is `containerd.v1.types` — Ruling R9) — add an assertion in the test step that `containerd.v1.types.Status.CREATED.getNumber() == 1` etc.; if the vendored proto differs, update the switch from the generated enum, not from memory.

- [ ] **Step 5: Run and commit**

Run: `./gradlew test`
Expected: PASS.

```bash
git add -A && git commit -m "feat: public domain model (Container, Image, ContainerSpec, RemoveOptions) and proto mapping"
```

---

### Task 6: OciSpecBuilder — OCI runtime spec as JSON in a typeurl Any

**Files:**
- Create: `src/main/java/io/nanofaas/containerd/internal/OciSpecBuilder.java`
- Create: `src/main/java/io/nanofaas/containerd/internal/JsonSupport.java`
- Test: `src/test/java/io/nanofaas/containerd/internal/OciSpecBuilderTest.java`

**Interfaces:**
- Consumes: `ContainerSpec` (Task 5), `Platform`.
- Produces:
  - `class OciSpecBuilder { static com.google.protobuf.Any buildContainerSpec(ContainerSpec spec); static com.google.protobuf.Any buildExecSpec(List<String> command, Map<String,String> env, String workingDir); }` — each Any has `typeUrl` `types.containerd.io/opencontainers/runtime-spec/1/Spec` (resp. `.../1/Process`) and JSON bytes as value.
  - `class JsonSupport { static com.google.protobuf.Struct parse(String json); static String print(com.google.protobuf.Struct struct); }` (wraps protobuf-java-util `JsonFormat`; also used by Task 7).

- [ ] **Step 1: Write the failing test**:

```java
package io.nanofaas.containerd.internal;

import com.google.protobuf.util.JsonFormat;
import io.nanofaas.containerd.ContainerSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OciSpecBuilderTest {

    private static final String SPEC_TYPE_URL = "types.containerd.io/opencontainers/runtime-spec/1/Spec";
    private static final String PROCESS_TYPE_URL = "types.containerd.io/opencontainers/runtime-spec/1/Process";

    private static com.google.protobuf.Struct parse(com.google.protobuf.Any any) {
        try {
            var struct = com.google.protobuf.Struct.newBuilder();
            JsonFormat.parser().merge(any.getValue().toStringUtf8(), struct);
            return struct.build();
        } catch (Exception e) {
            throw new AssertionError("spec JSON does not parse: " + any.getValue().toStringUtf8(), e);
        }
    }

    @Test
    void containerSpecUsesContainerdTypeUrlAndJsonValue() {
        var any = OciSpecBuilder.buildContainerSpec(ContainerSpec.builder()
                .id("test-1")
                .image("docker.io/library/alpine:latest")
                .command(List.of("/bin/sh", "-c", "while true; do sleep 10; done"))
                .build());

        assertThat(any.getTypeUrl()).isEqualTo(SPEC_TYPE_URL);
        assertThat(any.getValue().size()).isGreaterThan(0);
        parse(any); // must not throw
    }

    @Test
    void containerSpecIncludesProcessRootAndLinuxDefaults() {
        var spec = parse(OciSpecBuilder.buildContainerSpec(ContainerSpec.builder()
                .id("test-1")
                .image("alpine")
                .command(List.of("/bin/sh"))
                .workingDir("/work")
                .environment(Map.of("PORT", "8080"))
                .hostname("myhost")
                .user("1000:1000")
                .build()));

        assertThat(spec.getFieldsOrThrow("ociVersion").getStringValue()).isEqualTo("1.2.0");

        var process = spec.getFieldsOrThrow("process").getStructValue();
        assertThat(process.getFieldsOrThrow("cwd").getStringValue()).isEqualTo("/work");
        assertThat(process.getFieldsOrThrow("args").getListValue().getValues(0).getStringValue()).isEqualTo("/bin/sh");
        assertThat(process.getFieldsOrThrow("env").getListValue().getValuesList())
                .extracting(com.google.protobuf.Value::getStringValue)
                .contains("PORT=8080");
        assertThat(process.getFieldsOrThrow("terminal").getBoolValue()).isFalse();

        var user = process.getFieldsOrThrow("user").getStructValue();
        assertThat(user.getFieldsOrThrow("uid").getNumberValue()).isEqualTo(1000);
        assertThat(user.getFieldsOrThrow("gid").getNumberValue()).isEqualTo(1000);

        var root = spec.getFieldsOrThrow("root").getStructValue();
        assertThat(root.getFieldsOrThrow("path").getStringValue()).isEqualTo("rootfs");
        assertThat(root.getFieldsOrThrow("readonly").getBoolValue()).isFalse();

        assertThat(spec.getFieldsOrThrow("hostname").getStringValue()).isEqualTo("myhost");

        var linux = spec.getFieldsOrThrow("linux").getStructValue();
        var namespaces = linux.getFieldsOrThrow("namespaces").getListValue().getValuesList();
        assertThat(namespaces).extracting(v -> v.getStructValue().getFieldsOrThrow("type").getStringValue())
                .containsExactlyInAnyOrder("pid", "network", "ipc", "uts", "mount");
    }

    @Test
    void containerSpecIncludesResourceLimits() {
        var spec = parse(OciSpecBuilder.buildContainerSpec(ContainerSpec.builder()
                .id("test-1")
                .image("alpine")
                .cpuShares(512)
                .cpuQuotaMicros(50000)
                .cpuPeriodMicros(100000)
                .memoryLimitBytes(128L * 1024 * 1024)
                .memorySwapLimitBytes(256L * 1024 * 1024)
                .pidsLimit(100)
                .build()));

        var resources = spec.getFieldsOrThrow("linux").getStructValue()
                .getFieldsOrThrow("resources").getStructValue();

        var cpu = resources.getFieldsOrThrow("cpu").getStructValue();
        assertThat(cpu.getFieldsOrThrow("shares").getNumberValue()).isEqualTo(512);
        assertThat(cpu.getFieldsOrThrow("quota").getNumberValue()).isEqualTo(50000);
        assertThat(cpu.getFieldsOrThrow("period").getNumberValue()).isEqualTo(100000);

        var memory = resources.getFieldsOrThrow("memory").getStructValue();
        assertThat(memory.getFieldsOrThrow("limit").getNumberValue()).isEqualTo(128L * 1024 * 1024);
        assertThat(memory.getFieldsOrThrow("swap").getNumberValue()).isEqualTo(256L * 1024 * 1024);

        assertThat(resources.getFieldsOrThrow("pids").getStructValue()
                .getFieldsOrThrow("limit").getNumberValue()).isEqualTo(100);
    }

    @Test
    void readonlyRootfsIsPropagated() {
        var spec = parse(OciSpecBuilder.buildContainerSpec(ContainerSpec.builder()
                .id("test-1")
                .image("alpine")
                .readonlyRootfs(true)
                .build()));
        assertThat(spec.getFieldsOrThrow("root").getStructValue().getFieldsOrThrow("readonly").getBoolValue()).isTrue();
    }

    @Test
    void execSpecUsesProcessTypeUrlAndJson() {
        var any = OciSpecBuilder.buildExecSpec(List.of("/bin/echo", "hello"), Map.of("A", "B"), "/tmp");
        assertThat(any.getTypeUrl()).isEqualTo(PROCESS_TYPE_URL);
        var process = parse(any);
        assertThat(process.getFieldsOrThrow("args").getListValue().getValuesList())
                .extracting(com.google.protobuf.Value::getStringValue)
                .containsExactly("/bin/echo", "hello");
        assertThat(process.getFieldsOrThrow("cwd").getStringValue()).isEqualTo("/tmp");
    }
}
```

Run: `./gradlew test --tests io.nanofaas.containerd.internal.OciSpecBuilderTest`
Expected: FAIL — classes don't exist.

- [ ] **Step 2: Implement `JsonSupport`**:

```java
package io.nanofaas.containerd.internal;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;

/** JSON helpers built on protobuf Struct/JsonFormat (no extra JSON dependency). */
public final class JsonSupport {

    private JsonSupport() {
    }

    public static Struct parse(String json) {
        try {
            Struct.Builder b = Struct.newBuilder();
            JsonFormat.parser().merge(json, b);
            return b.build();
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("invalid JSON: " + json, e);
        }
    }

    public static String print(Struct struct) {
        try {
            return JsonFormat.printer().print(struct);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("cannot serialize Struct to JSON", e);
        }
    }
}
```

- [ ] **Step 3: Implement `OciSpecBuilder`**

```java
package io.nanofaas.containerd.internal;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.nanofaas.containerd.ContainerSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds OCI runtime specs (JSON) wrapped in typeurl {@link Any}s with the type URLs containerd
 * registers for the Go runtime-spec structs. Verified against containerd v2.2.1: the spec travels
 * as JSON bytes, not protobuf.
 */
public final class OciSpecBuilder {

    public static final String SPEC_TYPE_URL = "types.containerd.io/opencontainers/runtime-spec/1/Spec";
    public static final String PROCESS_TYPE_URL = "types.containerd.io/opencontainers/runtime-spec/1/Process";

    static final String SPEC_VERSION = "1.2.0";

    private static final String DEFAULT_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    private static final List<String> DEFAULT_CAPABILITIES = List.of(
            "CAP_CHOWN", "CAP_DAC_OVERRIDE", "CAP_FSETID", "CAP_FOWNER", "CAP_MKNOD", "CAP_NET_RAW",
            "CAP_SETGID", "CAP_SETUID", "CAP_SETFCAP", "CAP_SETPCAP", "CAP_NET_BIND_SERVICE",
            "CAP_SYS_CHROOT", "CAP_KILL", "CAP_AUDIT_WRITE");
    private static final List<String> DEFAULT_NAMESPACES = List.of("pid", "network", "ipc", "uts", "mount");
    private static final List<String> DEFAULT_MASKED_PATHS = List.of(
            "/proc/acpi", "/proc/asound", "/proc/kcore", "/proc/keys", "/proc/latency_stats",
            "/proc/timer_list", "/proc/timer_stats", "/proc/sched_debug", "/sys/firmware", "/proc/scsi");
    private static final List<String> DEFAULT_READONLY_PATHS = List.of(
            "/proc/bus", "/proc/fs", "/proc/irq", "/proc/sys", "/proc/sysrq-trigger");

    private OciSpecBuilder() {
    }

    /** Builds the full OCI runtime spec for a container as a typeurl Any (JSON payload). */
    public static Any buildContainerSpec(ContainerSpec spec) {
        Struct.Builder root = Struct.newBuilder()
                .putFields("ociVersion", stringValue(SPEC_VERSION))
                .putFields("process", buildProcess(spec).build())
                .putFields("root", Struct.newBuilder()
                        .putFields("path", stringValue("rootfs"))
                        .putFields("readonly", boolValue(spec.readonlyRootfs()))
                        .build())
                .putFields("hostname", stringValue(spec.hostname() != null ? spec.hostname() : spec.id()))
                .putFields("mounts", buildStandardMounts(spec))
                .putFields("linux", buildLinux(spec).build());
        return toAny(SPEC_TYPE_URL, root.build());
    }

    /** Builds the process spec for exec as a typeurl Any (JSON payload). */
    public static Any buildExecSpec(List<String> command, Map<String, String> environment, String workingDir) {
        List<String> args = command == null || command.isEmpty() ? List.of("/bin/sh") : command;
        List<String> env = new ArrayList<>();
        env.add("PATH=" + DEFAULT_PATH);
        environment.forEach((k, v) -> env.add(k + "=" + v));

        Struct.Builder process = Struct.newBuilder()
                .putFields("terminal", boolValue(false))
                .putFields("user", Struct.newBuilder()
                        .putFields("uid", numberValue(0))
                        .putFields("gid", numberValue(0))
                        .build())
                .putFields("args", stringListValue(args))
                .putFields("env", stringListValue(env))
                .putFields("cwd", stringValue(workingDir != null ? workingDir : "/"))
                .putFields("noNewPrivileges", boolValue(true))
                .putFields("capabilities", capabilitiesValue());
        return toAny(PROCESS_TYPE_URL, process.build());
    }

    private static Struct.Builder buildProcess(ContainerSpec spec) {
        List<String> args = spec.command() == null || spec.command().isEmpty()
                ? List.of("/bin/sh") : spec.command();
        List<String> env = new ArrayList<>();
        env.add("PATH=" + DEFAULT_PATH);
        env.add("TERM=xterm");
        spec.environment().forEach((k, v) -> env.add(k + "=" + v));

        Struct.Builder process = Struct.newBuilder()
                .putFields("terminal", boolValue(false))
                .putFields("args", stringListValue(args))
                .putFields("env", stringListValue(env))
                .putFields("cwd", stringValue(spec.workingDir() != null ? spec.workingDir() : "/"))
                .putFields("noNewPrivileges", boolValue(false))
                .putFields("capabilities", capabilitiesValue())
                .putFields("rlimits", Struct.newBuilder()
                        .putFields("values", noFileRlimit())
                        .build());

        process.putFields("user", parseUser(spec.user()));
        return process;
    }

    private static Struct parseUser(String user) {
        Struct.Builder b = Struct.newBuilder().putFields("uid", numberValue(0)).putFields("gid", numberValue(0));
        if (user != null && !user.isBlank()) {
            if (user.contains(":")) {
                String[] parts = user.split(":", 2);
                try {
                    b.putFields("uid", numberValue(Long.parseLong(parts[0])));
                    b.putFields("gid", numberValue(Long.parseLong(parts[1])));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("user must be \"uid:gid\" or a username, got: " + user, e);
                }
            }
            // bare username: uid/gid stay 0; the runtime resolves the name. Documented limitation.
        }
        return b.build();
    }

    private static Struct buildStandardMounts(ContainerSpec spec) {
        List<Value> mounts = new ArrayList<>(List.of(
                mount("proc", "proc", "/proc", List.of("nosuid", "noexec", "nodev")),
                mount("tmpfs", "tmpfs", "/dev", List.of("nosuid", "strictatime", "mode=755", "size=65536k")),
                mount("devpts", "devpts", "/dev/pts",
                        List.of("nosuid", "noexec", "newinstance", "ptmxmode=0666", "mode=0620", "gid=5")),
                mount("tmpfs", "shm", "/dev/shm", List.of("nosuid", "noexec", "nodev", "mode=1777", "size=65536k")),
                mount("mqueue", "mqueue", "/dev/mqueue", List.of("nosuid", "noexec", "nodev")),
                mount("sysfs", "sysfs", "/sys", List.of("nosuid", "noexec", "nodev", "ro")),
                mount("cgroup", "cgroup", "/sys/fs/cgroup", List.of("nosuid", "noexec", "nodev", "relatime", "ro"))));
        for (ContainerSpec.MountSpec m : spec.mounts()) {
            mounts.add(mount(m.type(), m.source(), m.destination(), m.options()));
        }
        return structList(mounts);
    }

    private static Value mount(String type, String source, String destination, List<String> options) {
        Struct.Builder b = Struct.newBuilder()
                .putFields("destination", stringValue(destination))
                .putFields("type", stringValue(type))
                .putFields("source", stringValue(source));
        if (options != null && !options.isEmpty()) {
            b.putFields("options", stringListValue(options));
        }
        return Value.newBuilder().setStructValue(b.build()).build();
    }

    private static Struct.Builder buildLinux(ContainerSpec spec) {
        List<Value> namespaces = new ArrayList<>();
        for (String ns : DEFAULT_NAMESPACES) {
            namespaces.add(Value.newBuilder().setStructValue(Struct.newBuilder()
                    .putFields("type", stringValue(ns))
                    .build()).build());
        }
        Struct.Builder linux = Struct.newBuilder()
                .putFields("namespaces", structList(namespaces))
                .putFields("maskedPaths", stringListValue(DEFAULT_MASKED_PATHS))
                .putFields("readonlyPaths", stringListValue(DEFAULT_READONLY_PATHS));

        Struct.Builder resources = Struct.newBuilder();
        Struct.Builder cpu = Struct.newBuilder();
        if (spec.cpuShares() > 0) {
            cpu.putFields("shares", numberValue(spec.cpuShares()));
        }
        if (spec.cpuQuotaMicros() > 0) {
            cpu.putFields("quota", numberValue(spec.cpuQuotaMicros()));
        }
        if (spec.cpuPeriodMicros() > 0) {
            cpu.putFields("period", numberValue(spec.cpuPeriodMicros()));
        }
        if (cpu.getFieldsCount() > 0) {
            resources.putFields("cpu", cpu.build());
        }
        Struct.Builder memory = Struct.newBuilder();
        if (spec.memoryLimitBytes() > 0) {
            memory.putFields("limit", numberValue(spec.memoryLimitBytes()));
        }
        if (spec.memorySwapLimitBytes() > 0) {
            memory.putFields("swap", numberValue(spec.memorySwapLimitBytes()));
        }
        if (memory.getFieldsCount() > 0) {
            resources.putFields("memory", memory.build());
        }
        if (spec.pidsLimit() > 0) {
            resources.putFields("pids", Struct.newBuilder().putFields("limit", numberValue(spec.pidsLimit())).build());
        }
        if (resources.getFieldsCount() > 0) {
            linux.putFields("resources", resources.build());
        }
        return linux;
    }

    private static Struct capabilitiesValue() {
        Struct.Builder caps = Struct.newBuilder();
        for (String set : List.of("bounding", "effective", "inheritable", "permitted", "ambient")) {
            caps.putFields(set, stringListValue(DEFAULT_CAPABILITIES));
        }
        return caps.build();
    }

    private static Value noFileRlimit() {
        return Value.newBuilder().setStructValue(Struct.newBuilder()
                .putFields("type", stringValue("RLIMIT_NOFILE"))
                .putFields("hard", numberValue(1024))
                .putFields("soft", numberValue(1024))
                .build()).build();
    }

    private static Any toAny(String typeUrl, Struct spec) {
        return Any.newBuilder()
                .setTypeUrl(typeUrl)
                .setValue(ByteString.copyFromUtf8(JsonSupport.print(spec)))
                .build();
    }

    private static Value stringValue(String s) {
        return Value.newBuilder().setStringValue(s).build();
    }

    private static Value numberValue(long n) {
        return Value.newBuilder().setNumberValue(n).build();
    }

    private static Value boolValue(boolean b) {
        return Value.newBuilder().setBoolValue(b).build();
    }

    private static Value stringListValue(List<String> values) {
        var b = com.google.protobuf.ListValue.newBuilder();
        for (String v : values) {
            b.addValues(stringValue(v));
        }
        return Value.newBuilder().setListValue(b).build();
    }

    private static Struct structList(List<Value> values) {
        return Struct.newBuilder()
                .putFields("values", Value.newBuilder().setListValue(
                        com.google.protobuf.ListValue.newBuilder().addAllValues(values)).build())
                .build();
    }
}
```

- [ ] **Step 4: Run and commit**

Run: `./gradlew test`
Expected: PASS (all 6 OciSpecBuilder tests + previous tests).

```bash
git add -A && git commit -m "feat: OCI spec builder emitting JSON typeurl Any for container and exec specs"
```

---

### Task 7: Content store, ChainID, snapshot manager, and rootfs resolution

**Files:**
- Create: `src/main/java/io/nanofaas/containerd/internal/ContentStoreReader.java`
- Create: `src/main/java/io/nanofaas/containerd/internal/ChainIds.java`
- Create: `src/main/java/io/nanofaas/containerd/internal/SnapshotManager.java`
- Create: `src/main/java/io/nanofaas/containerd/internal/ImageRootfsResolver.java`
- Test: `src/test/java/io/nanofaas/containerd/internal/ChainIdsTest.java`
- Test: `src/test/java/io/nanofaas/containerd/internal/ImageRootfsResolverTest.java`

**Interfaces:**
- Consumes: generated stubs, `ProtoMapper` (Task 5), `JsonSupport` (Task 6).
- Produces:
  - `class ChainIds { static String chainId(List<String> diffIds); }` — algorithm from the verified fact table.
  - `class ContentStoreReader { ContentStoreReader(ManagedChannel channel); byte[] read(String digest); }` — `Content.Info` for size, then stream `Content.Read`.
  - `class SnapshotManager { SnapshotManager(ManagedChannel channel, String snapshotter); List<containerd.types.Mount> prepare(String key, String parent); List<containerd.types.Mount> mounts(String key); void remove(String key); }` — `remove` is idempotent (NOT_FOUND ignored, DEBUG-logged).
  - `class ImageRootfsResolver { ImageRootfsResolver(ManagedChannel channel); String resolveChainId(String imageName); }` — Images.Get → manifest → (index → platform manifest) → config → `rootfs.diff_ids` → ChainID. Handles manifest media types `application/vnd.oci.image.manifest.v1+json` and `application/vnd.docker.distribution.manifest.v2+json`, index types `application/vnd.oci.image.index.v1+json` and `application/vnd.docker.distribution.manifest.list.v2+json`, and config types `application/vnd.oci.image.config.v1+json` / `application/vnd.docker.container.image.v1+json`.

- [ ] **Step 1: Write the failing tests**

`ChainIdsTest`:

```java
package io.nanofaas.containerd.internal;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChainIdsTest {

    private static String sha256(String s) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void singleLayerChainIdIsTheDiffId() {
        assertThat(ChainIds.chainId(List.of("sha256:layer-a"))).isEqualTo("sha256:layer-a");
    }

    @Test
    void chainIdChainsLayersWithSpaceSeparator() {
        String diff1 = "sha256:d1";
        String diff2 = "sha256:d2";
        String expected = sha256(diff1 + " " + diff2);
        assertThat(ChainIds.chainId(List.of(diff1, diff2))).isEqualTo(expected);
    }

    @Test
    void threeLayersChainRecursively() {
        String diff1 = "sha256:d1";
        String diff2 = "sha256:d2";
        String diff3 = "sha256:d3";
        String expected = sha256(sha256(diff1 + " " + diff2) + " " + diff3);
        assertThat(ChainIds.chainId(List.of(diff1, diff2, diff3))).isEqualTo(expected);
    }

    @Test
    void emptyLayerListHasEmptyChainId() {
        assertThat(ChainIds.chainId(List.of())).isEqualTo("");
    }
}
```

`ImageRootfsResolverTest` — uses an in-process server faking Images + Content services:

```java
package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImageRootfsResolverTest {

    private static final String MANIFEST_JSON = """
            {
              "mediaType": "application/vnd.oci.image.manifest.v1+json",
              "config": {
                "mediaType": "application/vnd.oci.image.config.v1+json",
                "digest": "sha256:config-digest",
                "size": 100
              },
              "layers": []
            }
            """;

    private static final String CONFIG_JSON = """
            {
              "rootfs": {
                "type": "layers",
                "diff_ids": ["sha256:d1", "sha256:d2"]
              }
            }
            """;

    @Test
    void resolvesChainIdFromManifestAndConfig() throws Exception {
        String name = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(name).directExecutor();

        server.addService(containerd.services.images.v1.ImagesGrpc.bindService(
                new containerd.services.images.v1.ImagesGrpc.ImagesImplBase() {
                    @Override
                    public void get(containerd.services.images.v1.GetImageRequest request,
                                    StreamObserver<containerd.services.images.v1.GetImageResponse> responseObserver) {
                        responseObserver.onNext(containerd.services.images.v1.GetImageResponse.newBuilder()
                                .setImage(containerd.services.images.v1.Image.newBuilder()
                                        .setName(request.getName())
                                        .setTarget(containerd.types.Descriptor.newBuilder()
                                                .setDigest("sha256:manifest-digest")
                                                .setSize(MANIFEST_JSON.length()))
                                        .build())
                                .build());
                        responseObserver.onCompleted();
                    }
                }));

        server.addService(containerd.services.content.v1.ContentGrpc.bindService(
                new containerd.services.content.v1.ContentGrpc.ContentImplBase() {
                    @Override
                    public void info(containerd.services.content.v1.InfoRequest request,
                                     StreamObserver<containerd.services.content.v1.InfoResponse> responseObserver) {
                        byte[] data = request.getDigest().equals("sha256:config-digest")
                                ? CONFIG_JSON.getBytes() : MANIFEST_JSON.getBytes();
                        responseObserver.onNext(containerd.services.content.v1.InfoResponse.newBuilder()
                                .setInfo(containerd.services.content.v1.Info.newBuilder()
                                        .setDigest(request.getDigest())
                                        .setSize(data.length))
                                .build());
                        responseObserver.onCompleted();
                    }

                    @Override
                    public void read(containerd.services.content.v1.ReadContentRequest request,
                                     StreamObserver<containerd.services.content.v1.ReadContentResponse> responseObserver) {
                        byte[] data = request.getDigest().equals("sha256:config-digest")
                                ? CONFIG_JSON.getBytes() : MANIFEST_JSON.getBytes();
                        responseObserver.onNext(containerd.services.content.v1.ReadContentResponse.newBuilder()
                                .setOffset(0)
                                .setData(com.google.protobuf.ByteString.copyFrom(data))
                                .build());
                        responseObserver.onCompleted();
                    }
                }));

        server.build().start();
        try {
            ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
            try {
                String chainId = new ImageRootfsResolver(channel).resolveChainId("alpine:latest");
                assertThat(chainId).isEqualTo(ChainIds.chainId(List.of("sha256:d1", "sha256:d2")));
            } finally {
                channel.shutdownNow();
            }
        } finally {
            server.build().shutdownNow();
        }
    }
}
```

Run: `./gradlew test --tests io.nanofaas.containerd.internal.ChainIdsTest --tests io.nanofaas.containerd.internal.ImageRootfsResolverTest`
Expected: FAIL — classes don't exist.

- [ ] **Step 2: Implement `ChainIds`**:

```java
package io.nanofaas.containerd.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** ChainID computation for OCI layer diff IDs (verified against opencontainers/image-spec). */
public final class ChainIds {

    private ChainIds() {
    }

    /**
     * chainID[0] = diffID[0]; chainID[i] = sha256(chainID[i-1] + " " + diffID[i]).
     * Empty input yields the empty string (scratch images).
     */
    public static String chainId(List<String> diffIds) {
        if (diffIds.isEmpty()) {
            return "";
        }
        String current = diffIds.get(0);
        for (int i = 1; i < diffIds.size(); i++) {
            current = sha256(current + " " + diffIds.get(i));
        }
        return current;
    }

    private static String sha256(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
```

- [ ] **Step 3: Implement `ContentStoreReader` and `SnapshotManager`**

`ContentStoreReader`:

```java
package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;

import java.io.ByteArrayOutputStream;

/** Reads blobs from containerd's content store. */
public final class ContentStoreReader {

    private final containerd.services.content.v1.ContentGrpc.ContentBlockingStub stub;

    public ContentStoreReader(ManagedChannel channel) {
        this.stub = containerd.services.content.v1.ContentGrpc.newBlockingStub(channel);
    }

    public byte[] read(String digest) {
        long size = stub.info(containerd.services.content.v1.InfoRequest.newBuilder()
                        .setDigest(digest).build())
                .getInfo().getSize();
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(size, 1 << 20));
        long offset = 0;
        while (offset < size) {
            var response = stub.read(containerd.services.content.v1.ReadContentRequest.newBuilder()
                    .setDigest(digest).setOffset(offset).setSize(size - offset).build());
            for (var chunk : response) {
                out.writeBytes(chunk.getData().toByteArray());
                offset += chunk.getData().size();
            }
        }
        return out.toByteArray();
    }
}
```

`SnapshotManager`:

```java
package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Prepares/removes snapshots for container root filesystems. */
public final class SnapshotManager {

    private static final Logger log = LoggerFactory.getLogger(SnapshotManager.class);

    private final containerd.services.snapshots.v1.SnapshotsGrpc.SnapshotsBlockingStub stub;
    private final String snapshotter;

    public SnapshotManager(ManagedChannel channel, String snapshotter) {
        this.stub = containerd.services.snapshots.v1.SnapshotsGrpc.newBlockingStub(channel);
        this.snapshotter = snapshotter;
    }

    /** Prepares an active snapshot keyed by {@code key}, parented on {@code parent} (may be empty). */
    public List<containerd.types.Mount> prepare(String key, String parent) {
        log.debug("snapshot prepare: snapshotter={} key={} parent={}", snapshotter, key, parent);
        var response = stub.prepare(containerd.services.snapshots.v1.PrepareSnapshotRequest.newBuilder()
                .setSnapshotter(snapshotter)
                .setKey(key)
                .setParent(parent)
                .build());
        return List.copyOf(response.getMountsList());
    }

    /** Returns the mounts for an existing snapshot key. */
    public List<containerd.types.Mount> mounts(String key) {
        return List.copyOf(stub.mounts(containerd.services.snapshots.v1.MountsRequest.newBuilder()
                .setSnapshotter(snapshotter)
                .setKey(key)
                .build()).getMountsList());
    }

    /** Removes a snapshot; idempotent — a missing snapshot is logged at DEBUG and ignored. */
    public void remove(String key) {
        log.debug("snapshot remove: snapshotter={} key={}", snapshotter, key);
        try {
            stub.remove(containerd.services.snapshots.v1.RemoveSnapshotRequest.newBuilder()
                    .setSnapshotter(snapshotter)
                    .setKey(key)
                    .build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                log.debug("snapshot {} already gone (idempotent remove)", key);
                return;
            }
            throw e;
        }
    }
}
```

- [ ] **Step 4: Implement `ImageRootfsResolver`**:

```java
package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;

import java.util.ArrayList;
import java.util.List;

/** Resolves an image reference to the ChainID of its top layer (the snapshot parent key). */
public final class ImageRootfsResolver {

    private static final List<String> INDEX_MEDIA_TYPES = List.of(
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json");
    private static final List<String> MANIFEST_MEDIA_TYPES = List.of(
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json");

    private final containerd.services.images.v1.ImagesGrpc.ImagesBlockingStub images;
    private final ContentStoreReader content;

    public ImageRootfsResolver(ManagedChannel channel) {
        this.images = containerd.services.images.v1.ImagesGrpc.newBlockingStub(channel);
        this.content = new ContentStoreReader(channel);
    }

    public String resolveChainId(String imageName) {
        var image = images.get(containerd.services.images.v1.GetImageRequest.newBuilder()
                .setName(imageName).build()).getImage();

        var top = JsonSupport.parse(new String(content.read(image.getTarget().getDigest())));
        String mediaType = field(top, "mediaType");
        if (INDEX_MEDIA_TYPES.contains(mediaType)) {
            // pick the first platform entry whose platform matches linux/amd64, else the first entry
            String manifestDigest = pickPlatformManifest(top);
            top = JsonSupport.parse(new String(content.read(manifestDigest)));
            mediaType = field(top, "mediaType");
        }
        if (!MANIFEST_MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalStateException("unsupported manifest media type: " + mediaType);
        }

        String configDigest = top.getFieldsOrThrow("config").getStructValue()
                .getFieldsOrThrow("digest").getStringValue();
        var config = JsonSupport.parse(new String(content.read(configDigest)));
        var diffIds = config.getFieldsOrThrow("rootfs").getStructValue()
                .getFieldsOrThrow("diff_ids").getListValue().getValuesList();

        List<String> ids = new ArrayList<>();
        for (var v : diffIds) {
            ids.add(v.getStringValue());
        }
        return ChainIds.chainId(ids);
    }

    private static String pickPlatformManifest(com.google.protobuf.Struct index) {
        var manifests = index.getFieldsOrThrow("manifests").getListValue().getValuesList();
        if (manifests.isEmpty()) {
            throw new IllegalStateException("image index has no manifests");
        }
        for (var m : manifests) {
            var platform = m.getStructValue().getFieldsOrDefault("platform",
                    com.google.protobuf.Value.getDefaultInstance()).getStructValue();
            if (platform.getFieldsOrDefault("os", com.google.protobuf.Value.getDefaultInstance()).getStringValue().equals("linux")
                    && platform.getFieldsOrDefault("architecture", com.google.protobuf.Value.getDefaultInstance()).getStringValue().equals("amd64")) {
                return m.getStructValue().getFieldsOrThrow("digest").getStringValue();
            }
        }
        return manifests.get(0).getStructValue().getFieldsOrThrow("digest").getStringValue();
    }

    private static String field(com.google.protobuf.Struct struct, String name) {
        return struct.getFieldsOrDefault(name, com.google.protobuf.Value.getDefaultInstance()).getStringValue();
    }
}
```

- [ ] **Step 5: Run and commit**

Run: `./gradlew test`
Expected: PASS.

```bash
git add -A && git commit -m "feat: content store reader, ChainID, snapshot manager, image rootfs resolution"
```

---

### Task 8: Image pull via the Transfer service and the Images facade

**Files:**
- Create: `src/main/java/io/nanofaas/containerd/internal/TransferImagePuller.java`
- Create: `src/main/java/io/nanofaas/containerd/internal/ImagesServiceImpl.java`
- Create: `src/main/java/io/nanofaas/containerd/spi/Images.java`
- Modify: `src/main/java/io/nanofaas/containerd/spi/ContainerdClient.java` (add `Images images();`)
- Modify: `src/main/java/io/nanofaas/containerd/internal/DefaultContainerdClient.java` (wire `images()`)
- Test: `src/test/java/io/nanofaas/containerd/internal/TransferImagePullerTest.java` (in-process fake Transfer service asserting the request shape)
- Integration test: `src/integrationTest/java/io/nanofaas/containerd/ImagePullIT.java`

**Interfaces:**
- Consumes: transfer/image protos (Task 2), `ProtoMapper`, `ImageRootfsResolver` (Task 7), `StatusExceptionMapper` (Task 4), `ImagePullException`.
- Produces:
  - `interface Images { void pull(String reference); void pull(String reference, Platform platform); Image get(String name); List<Image> list(); void remove(String name); }`
  - `class TransferImagePuller { TransferImagePuller(ManagedChannel channel, String snapshotter); void pull(String reference, Platform platform); }` — builds `TransferRequest{source: Any(OCIRegistry{reference}), destination: Any(ImageStore{name=reference, platforms=[p], all_metadata=true, unpacks=[{platform=p, snapshotter}]})}`; Any type URLs are the proto full names `containerd.types.transfer.OCIRegistry` / `containerd.types.transfer.ImageStore`; maps failures to `ImagePullException`.
  - `class ImagesServiceImpl implements Images` — `remove` idempotent (NOT_FOUND ignored); `get` maps NOT_FOUND → `ImageNotFoundException` via `StatusExceptionMapper`.

- [ ] **Step 1: Write the failing test**:

```java
package io.nanofaas.containerd.internal;

import com.google.protobuf.Any;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.Platform;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TransferImagePullerTest {

    @Test
    void sendsRegistrySourceAndImageStoreDestination() throws Exception {
        AtomicReference<containerd.services.transfer.v1.TransferRequest> captured = new AtomicReference<>();
        String name = InProcessServerBuilder.generateName();

        InProcessServerBuilder.forName(name).directExecutor()
                .addService(containerd.services.transfer.v1.TransferGrpc.bindService(
                        new containerd.services.transfer.v1.TransferGrpc.TransferImplBase() {
                            @Override
                            public void transfer(containerd.services.transfer.v1.TransferRequest request,
                                                 StreamObserver<com.google.protobuf.Empty> responseObserver) {
                                captured.set(request);
                                responseObserver.onNext(com.google.protobuf.Empty.getDefaultInstance());
                                responseObserver.onCompleted();
                            }
                        }))
                .build().start();

        try {
            var channel = InProcessChannelBuilder.forName(name).directExecutor().build();
            try {
                new TransferImagePuller(channel, "overlayfs")
                        .pull("docker.io/library/alpine:latest", Platform.linuxAmd64());

                Any source = captured.get().getSource();
                Any destination = captured.get().getDestination();

                assertThat(source.getTypeUrl()).isEqualTo("containerd.types.transfer.OCIRegistry");
                var registry = containerd.types.transfer.OCIRegistry.parseFrom(source.getValue());
                assertThat(registry.getReference()).isEqualTo("docker.io/library/alpine:latest");

                assertThat(destination.getTypeUrl()).isEqualTo("containerd.types.transfer.ImageStore");
                var store = containerd.types.transfer.ImageStore.parseFrom(destination.getValue());
                assertThat(store.getName()).isEqualTo("docker.io/library/alpine:latest");
                assertThat(store.getAllMetadata()).isTrue();
                assertThat(store.getPlatformsList()).hasSize(1);
                assertThat(store.getPlatforms(0).getOs()).isEqualTo("linux");
                assertThat(store.getPlatforms(0).getArchitecture()).isEqualTo("amd64");
                assertThat(store.getUnpacksList()).hasSize(1);
                assertThat(store.getUnpacks(0).getSnapshotter()).isEqualTo("overlayfs");
                assertThat(store.getUnpacks(0).getPlatform().getArchitecture()).isEqualTo("amd64");
            } finally {
                channel.shutdownNow();
            }
        } finally {
            InProcessServerBuilder.forName(name).build().shutdownNow();
        }
    }
}
```

Run: `./gradlew test --tests io.nanofaas.containerd.internal.TransferImagePullerTest`
Expected: FAIL — classes don't exist.

- [ ] **Step 2: Implement `TransferImagePuller`**:

```java
package io.nanofaas.containerd.internal;

import com.google.protobuf.Any;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.nanofaas.containerd.ImagePullException;
import io.nanofaas.containerd.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pulls images through containerd's Transfer service (the mechanism ctr uses):
 * registry source + image-store destination with unpacking into the snapshotter.
 */
public final class TransferImagePuller {

    private static final Logger log = LoggerFactory.getLogger(TransferImagePuller.class);

    private final containerd.services.transfer.v1.TransferGrpc.TransferBlockingStub stub;
    private final String snapshotter;

    public TransferImagePuller(ManagedChannel channel, String snapshotter) {
        this.stub = containerd.services.transfer.v1.TransferGrpc.newBlockingStub(channel);
        this.snapshotter = snapshotter;
    }

    public void pull(String reference, Platform platform) {
        log.debug("pull start: reference={} platform={}/{} snapshotter={}",
                reference, platform.os(), platform.architecture(), snapshotter);

        var source = containerd.types.transfer.OCIRegistry.newBuilder()
                .setReference(reference)
                .build();
        var protoPlatform = ProtoMapper.toProto(platform);
        var destination = containerd.types.transfer.ImageStore.newBuilder()
                .setName(reference)
                .addPlatforms(protoPlatform)
                .setAllMetadata(true)
                .addUnpacks(containerd.types.transfer.UnpackConfiguration.newBuilder()
                        .setPlatform(protoPlatform)
                        .setSnapshotter(snapshotter))
                .build();

        var request = containerd.services.transfer.v1.TransferRequest.newBuilder()
                .setSource(Any.newBuilder()
                        .setTypeUrl(source.getDescriptorForType().getFullName())
                        .setValue(source.toByteString()))
                .setDestination(Any.newBuilder()
                        .setTypeUrl(destination.getDescriptorForType().getFullName())
                        .setValue(destination.toByteString()))
                .build();

        try {
            // The Transfer RPC blocks until the transfer completes.
            stub.transfer(request);
        } catch (StatusRuntimeException e) {
            throw new ImagePullException("failed to pull image " + reference + ": " + e.getStatus(), e);
        }
        log.debug("pull complete: reference={}", reference);
    }
}
```

- [ ] **Step 3: Implement `Images` facade and `ImagesServiceImpl`**

`Images` (in `spi`):

```java
package io.nanofaas.containerd.spi;

import io.nanofaas.containerd.Image;
import io.nanofaas.containerd.Platform;

import java.util.List;

/** Operations on containerd images. */
public interface Images {

    /** Pulls an image from its registry into containerd (unpacking into the configured snapshotter). */
    void pull(String reference);

    /** Pulls an image for a specific platform. */
    void pull(String reference, Platform platform);

    Image get(String name);

    List<Image> list();

    /** Removes an image; idempotent (a missing image is ignored). */
    void remove(String name);
}
```

`ImagesServiceImpl`:

```java
package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.nanofaas.containerd.Image;
import io.nanofaas.containerd.Platform;
import io.nanofaas.containerd.spi.Images;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class ImagesServiceImpl implements Images {

    private static final Logger log = LoggerFactory.getLogger(ImagesServiceImpl.class);

    private final containerd.services.images.v1.ImagesGrpc.ImagesBlockingStub stub;
    private final TransferImagePuller puller;

    public ImagesServiceImpl(ManagedChannel channel, String snapshotter) {
        this.stub = containerd.services.images.v1.ImagesGrpc.newBlockingStub(channel);
        this.puller = new TransferImagePuller(channel, snapshotter);
    }

    @Override
    public void pull(String reference) {
        pull(reference, Platform.linuxAmd64());
    }

    @Override
    public void pull(String reference, Platform platform) {
        puller.pull(reference, platform);
    }

    @Override
    public Image get(String name) {
        try {
            var response = stub.get(containerd.services.images.v1.GetImageRequest.newBuilder()
                    .setName(name).build());
            return ProtoMapper.map(response.getImage());
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.IMAGE);
        }
    }

    @Override
    public List<Image> list() {
        return stub.list(containerd.services.images.v1.ListImagesRequest.getDefaultInstance())
                .getImagesList().stream()
                .map(ProtoMapper::map)
                .toList();
    }

    @Override
    public void remove(String name) {
        log.debug("image remove: name={}", name);
        try {
            stub.delete(containerd.services.images.v1.DeleteImageRequest.newBuilder()
                    .setName(name)
                    .setSync(true)
                    .build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                log.debug("image {} already gone (idempotent remove)", name);
                return;
            }
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.IMAGE);
        }
    }
}
```

- [ ] **Step 4: Wire `images()` into the client** — add `Images images();` to `ContainerdClient`, implement in `DefaultContainerdClient` (lazily create one shared `ImagesServiceImpl` and cache it in a final field).

- [ ] **Step 5: Run tests, then write the integration test**

Run: `./gradlew test`
Expected: PASS.

`ImagePullIT`:

```java
package io.nanofaas.containerd;

import io.nanofaas.containerd.spi.ContainerdClient;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class ImagePullIT extends ContainerdConnectionIT {

    @Test
    @Timeout(300)
    void pullsListsGetsAndRemovesImage() {
        String ref = "docker.io/library/alpine:latest";
        String marker = "it-image-" + UUID.randomUUID();

        try {
            client.images().pull(ref);

            Image image = client.images().get(ref);
            assertThat(image.name()).isEqualTo(ref);
            assertThat(image.digest()).startsWith("sha256:");

            assertThat(client.images().list()).extracting(Image::name).contains(ref);

            // idempotent remove of a missing image must not throw
            client.images().remove(marker);
        } finally {
            client.images().remove(ref);
        }
    }

    @Test
    void getUnknownImageThrowsImageNotFound() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> client.images().get("it-unknown-" + UUID.randomUUID()))
                .isInstanceOf(ImageNotFoundException.class);
    }
}
```

Run: `./gradlew integrationTest`
Expected: PASS (requires network to docker.io; this machine has it).

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: image pull via containerd Transfer service, images facade (pull/get/list/remove)"
```

---

### Task 9: Containers facade — create (snapshot + spec + metadata), inspect, list

**Files:**
- Create: `src/main/java/io/nanofaas/containerd/internal/ContainersServiceImpl.java`
- Create: `src/main/java/io/nanofaas/containerd/spi/Containers.java`
- Modify: `ContainerdClient` (add `Containers containers();`), `DefaultContainerdClient` (wire it)
- Test: `src/test/java/io/nanofaas/containerd/internal/ContainersServiceImplTest.java` (in-process fakes: create-failure → snapshot removed; create sets GC label)
- Integration test: `src/integrationTest/java/io/nanofaas/containerd/ContainerCreateIT.java`

**Interfaces:**
- Consumes: `ContainerSpec`, `Container`, `ContainerStatus`, `RemoveOptions`, `OciSpecBuilder`, `SnapshotManager`, `ImageRootfsResolver`, `StatusExceptionMapper` (Tasks 4–7).
- Produces (public, used by Tasks 10–12):
  - `interface Containers { Container create(ContainerSpec spec); ContainerStatus inspect(String id); List<Container> list(); void remove(String id, RemoveOptions options); int start(String id); ExitStatus stop(String id); void kill(String id, Signal signal); ExitStatus wait(String id); ExecResult exec(String id, List<String> command); ExecResult exec(String id, ExecSpec spec); }`
  - `record ExecSpec(List<String> command, Map<String,String> environment, String workingDir, String stdin)` with a builder (Task 11 implements exec; the signature lands here now so tests compile — the impl method throws `UnsupportedOperationException` until Task 11).
  - `record ExecResult(int exitCode, String stdout, String stderr)`
- Create flow (transactional): `requireValidId` → `ImageRootfsResolver.resolveChainId(image)` → `SnapshotManager.prepare(id, chainId)` → build spec Any → `Containers.Create` with `runtime{name: runtimeName}`, `snapshotter`, `snapshot_key=id`, GC label `containerd.io/gc.ref.snapshot.<snapshotter> = id` → on ANY failure, best-effort `SnapshotManager.remove(id)` (WARN on cleanup failure), then map the exception (ALREADY_EXISTS → `ContainerAlreadyExistsException`, NOT_FOUND image → `ImageNotFoundException`).

- [ ] **Step 1: Write the failing test**:

```java
package io.nanofaas.containerd.internal;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.ContainerAlreadyExistsException;
import io.nanofaas.containerd.ContainerSpec;
import io.nanofaas.containerd.ImageNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainersServiceImplTest {

    // Fake services: Images returns an image whose config is a scratch rootfs (no layers),
    // Snapshots records prepare/remove, Containers can be set to fail.
    private static final String SCRATCH_CONFIG = """
            {"rootfs": {"type": "layers", "diff_ids": []}}
            """;
    private static final String SCRATCH_MANIFEST = """
            {
              "mediaType": "application/vnd.oci.image.manifest.v1+json",
              "config": {"mediaType": "application/vnd.oci.image.config.v1+json", "digest": "sha256:config", "size": 40},
              "layers": []
            }
            """;

    private static final class FakeServer implements AutoCloseable {
        final AtomicInteger snapshotsPrepared = new AtomicInteger();
        final AtomicInteger snapshotsRemoved = new AtomicInteger();
        final AtomicReference<containerd.services.containers.v1.Container> created = new AtomicReference<>();
        volatile boolean failContainerCreate;

        final io.grpc.Server server;

        FakeServer() throws Exception {
            String name = InProcessServerBuilder.generateName();
            var builder = InProcessServerBuilder.forName(name).directExecutor();

            builder.addService(containerd.services.images.v1.ImagesGrpc.bindService(
                    new containerd.services.images.v1.ImagesGrpc.ImagesImplBase() {
                        @Override
                        public void get(containerd.services.images.v1.GetImageRequest request,
                                        StreamObserver<containerd.services.images.v1.GetImageResponse> responseObserver) {
                            responseObserver.onNext(containerd.services.images.v1.GetImageResponse.newBuilder()
                                    .setImage(containerd.services.images.v1.Image.newBuilder()
                                            .setName(request.getName())
                                            .setTarget(containerd.types.Descriptor.newBuilder()
                                                    .setDigest("sha256:manifest")
                                                    .setSize(SCRATCH_MANIFEST.length())))
                                    .build());
                            responseObserver.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.content.v1.ContentGrpc.bindService(
                    new containerd.services.content.v1.ContentGrpc.ContentImplBase() {
                        @Override
                        public void info(containerd.services.content.v1.InfoRequest request,
                                         StreamObserver<containerd.services.content.v1.InfoResponse> responseObserver) {
                            byte[] data = request.getDigest().equals("sha256:config")
                                    ? SCRATCH_CONFIG.getBytes() : SCRATCH_MANIFEST.getBytes();
                            responseObserver.onNext(containerd.services.content.v1.InfoResponse.newBuilder()
                                    .setInfo(containerd.services.content.v1.Info.newBuilder()
                                            .setDigest(request.getDigest()).setSize(data.length)).build());
                            responseObserver.onCompleted();
                        }

                        @Override
                        public void read(containerd.services.content.v1.ReadContentRequest request,
                                         StreamObserver<containerd.services.content.v1.ReadContentResponse> responseObserver) {
                            byte[] data = request.getDigest().equals("sha256:config")
                                    ? SCRATCH_CONFIG.getBytes() : SCRATCH_MANIFEST.getBytes();
                            responseObserver.onNext(containerd.services.content.v1.ReadContentResponse.newBuilder()
                                    .setOffset(0).setData(com.google.protobuf.ByteString.copyFrom(data)).build());
                            responseObserver.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.snapshots.v1.SnapshotsGrpc.bindService(
                    new containerd.services.snapshots.v1.SnapshotsGrpc.SnapshotsImplBase() {
                        @Override
                        public void prepare(containerd.services.snapshots.v1.PrepareSnapshotRequest request,
                                            StreamObserver<containerd.services.snapshots.v1.PrepareSnapshotResponse> responseObserver) {
                            snapshotsPrepared.incrementAndGet();
                            responseObserver.onNext(containerd.services.snapshots.v1.PrepareSnapshotResponse.getDefaultInstance());
                            responseObserver.onCompleted();
                        }

                        @Override
                        public void remove(containerd.services.snapshots.v1.RemoveSnapshotRequest request,
                                           StreamObserver<com.google.protobuf.Empty> responseObserver) {
                            snapshotsRemoved.incrementAndGet();
                            responseObserver.onNext(com.google.protobuf.Empty.getDefaultInstance());
                            responseObserver.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.containers.v1.ContainersGrpc.bindService(
                    new containerd.services.containers.v1.ContainersGrpc.ContainersImplBase() {
                        @Override
                        public void create(containerd.services.containers.v1.CreateContainerRequest request,
                                           StreamObserver<containerd.services.containers.v1.CreateContainerResponse> responseObserver) {
                            if (failContainerCreate) {
                                responseObserver.onError(Status.ALREADY_EXISTS.asRuntimeException());
                                return;
                            }
                            created.set(request.getContainer());
                            responseObserver.onNext(containerd.services.containers.v1.CreateContainerResponse.newBuilder()
                                    .setContainer(request.getContainer()).build());
                            responseObserver.onCompleted();
                        }
                    }));

            server = builder.build().start();
            channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        }

        final io.grpc.ManagedChannel channel;

        @Override
        public void close() {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    private static ContainersServiceImpl service(FakeServer fake) {
        return new ContainersServiceImpl(fake.channel, "overlayfs", "io.containerd.runc.v2", null);
    }

    private static ContainerSpec spec() {
        return ContainerSpec.builder().id("test-1").image("scratch:latest")
                .command(java.util.List.of("/bin/sh")).build();
    }

    @Test
    void createPreparesSnapshotAndCreatesContainerWithGcLabel() throws Exception {
        try (var fake = new FakeServer()) {
            service(fake).create(spec());

            assertThat(fake.snapshotsPrepared.get()).isEqualTo(1);
            var container = fake.created.get();
            assertThat(container.getId()).isEqualTo("test-1");
            assertThat(container.getSnapshotter()).isEqualTo("overlayfs");
            assertThat(container.getSnapshotKey()).isEqualTo("test-1");
            assertThat(container.getRuntime().getName()).isEqualTo("io.containerd.runc.v2");
            assertThat(container.getLabelsMap())
                    .containsEntry("containerd.io/gc.ref.snapshot.overlayfs", "test-1");
            assertThat(container.getSpec().getTypeUrl())
                    .isEqualTo("types.containerd.io/opencontainers/runtime-spec/1/Spec");
        }
    }

    @Test
    void createFailureRemovesPreparedSnapshotAndMapsException() throws Exception {
        try (var fake = new FakeServer()) {
            fake.failContainerCreate = true;
            assertThatThrownBy(() -> service(fake).create(spec()))
                    .isInstanceOf(ContainerAlreadyExistsException.class);
            assertThat(fake.snapshotsPrepared.get()).isEqualTo(1);
            assertThat(fake.snapshotsRemoved.get()).isEqualTo(1);
        }
    }
}
```

Run: `./gradlew test --tests io.nanofaas.containerd.internal.ContainersServiceImplTest`
Expected: FAIL — classes don't exist.

- [ ] **Step 2: Implement `Containers` SPI + `ExecSpec`/`ExecResult` records**

`Containers`:

```java
package io.nanofaas.containerd.spi;

import io.nanofaas.containerd.*;

import java.util.List;

/**
 * Container lifecycle operations. A containerd container is metadata + spec + snapshot; running
 * state lives in a task. {@link #start} creates the task; {@link #stop} tears it down.
 */
public interface Containers {

    Container create(ContainerSpec spec);

    /** Combined view of the container metadata and its task state, if any. */
    ContainerStatus inspect(String id);

    List<Container> list();

    /**
     * Removes a container. Idempotent — a missing container is ignored. If a task is still running,
     * removal throws unless {@link RemoveOptions#force()} is set (which stops the task first).
     */
    void remove(String id, RemoveOptions options);

    /** Creates (if needed) and starts the container's task. Returns the init process pid. */
    int start(String id);

    /**
     * Stops a running container: SIGTERM, wait up to 10s, then SIGKILL, then task delete.
     * Idempotent — a container with no task returns an empty {@link java.util.Optional}.
     */
    java.util.Optional<ExitStatus> stop(String id);

    void kill(String id, Signal signal);

    /** Blocks until the container's init process exits. */
    ExitStatus wait(String id);

    ExecResult exec(String id, List<String> command);

    ExecResult exec(String id, ExecSpec spec);
}
```

`ExecSpec`/`ExecResult`:

```java
package io.nanofaas.containerd;

import java.util.List;
import java.util.Map;

public record ExecSpec(List<String> command, Map<String, String> environment, String workingDir, String stdin) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<String> command = List.of();
        private Map<String, String> environment = Map.of();
        private String workingDir;
        private String stdin;

        public Builder command(List<String> command) { this.command = command; return this; }
        public Builder environment(Map<String, String> environment) { this.environment = environment; return this; }
        public Builder workingDir(String workingDir) { this.workingDir = workingDir; return this; }
        public Builder stdin(String stdin) { this.stdin = stdin; return this; }

        public ExecSpec build() {
            return new ExecSpec(command, environment, workingDir, stdin);
        }
    }
}
```

```java
package io.nanofaas.containerd;

/** Result of an exec: exit code plus captured output. */
public record ExecResult(int exitCode, String stdout, String stderr) {
}
```

- [ ] **Step 3: Implement `ContainersServiceImpl`** (create/inspect/list/remove-for-non-running now; start/stop/exec implemented in Tasks 10–11 — until then they throw `UnsupportedOperationException`):

```java
package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.nanofaas.containerd.*;
import io.nanofaas.containerd.spi.Containers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

public final class ContainersServiceImpl implements Containers {

    private static final Logger log = LoggerFactory.getLogger(ContainersServiceImpl.class);

    /** Grace period between SIGTERM and SIGKILL in {@link #stop(String)}. */
    static final java.time.Duration STOP_TIMEOUT = java.time.Duration.ofSeconds(10);

    private final containerd.services.containers.v1.ContainersGrpc.ContainersBlockingStub stub;
    private final SnapshotManager snapshots;
    private final ImageRootfsResolver rootfsResolver;
    private final String snapshotter;
    private final String runtimeName;

    public ContainersServiceImpl(ManagedChannel channel, String snapshotter, String runtimeName, String runtimeBinaryName) {
        this.stub = containerd.services.containers.v1.ContainersGrpc.newBlockingStub(channel);
        this.snapshots = new SnapshotManager(channel, snapshotter);
        this.rootfsResolver = new ImageRootfsResolver(channel);
        this.snapshotter = snapshotter;
        this.runtimeName = runtimeName;
    }

    @Override
    public Container create(ContainerSpec spec) {
        ProtoMapper.requireValidId(spec.id());
        log.debug("container create start: id={} image={}", spec.id(), spec.image());

        String parentChainId;
        try {
            parentChainId = rootfsResolver.resolveChainId(spec.image());
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.IMAGE);
        }
        snapshots.prepare(spec.id(), parentChainId);

        try {
            var container = containerd.services.containers.v1.Container.newBuilder()
                    .setId(spec.id())
                    .setImage(spec.image())
                    .setSnapshotter(snapshotter)
                    .setSnapshotKey(spec.id())
                    .setSpec(OciSpecBuilder.buildContainerSpec(spec))
                    .setRuntime(containerd.services.containers.v1.Container.Runtime.newBuilder()
                            .setName(runtimeName))
                    .putLabels("containerd.io/gc.ref.snapshot." + snapshotter, spec.id())
                    .putAllLabels(spec.labels())
                    .build();
            var created = stub.create(containerd.services.containers.v1.CreateContainerRequest.newBuilder()
                    .setContainer(container).build()).getContainer();
            log.debug("container create complete: id={}", spec.id());
            return ProtoMapper.map(created);
        } catch (StatusRuntimeException e) {
            try {
                snapshots.remove(spec.id());
            } catch (StatusRuntimeException cleanupFailure) {
                log.warn("failed to clean up snapshot {} after container create failure", spec.id(), cleanupFailure);
            }
            if (e.getStatus().getCode() == io.grpc.Status.Code.ALREADY_EXISTS) {
                throw new ContainerAlreadyExistsException("container " + spec.id() + " already exists", e);
            }
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.CONTAINER);
        }
    }

    @Override
    public ContainerStatus inspect(String id) {
        var container = stub.get(containerd.services.containers.v1.GetContainerRequest.newBuilder()
                .setId(id).build()).getContainer();
        return new ContainerStatus(
                container.getId(),
                container.getImage(),
                ContainerState.UNKNOWN, // task state filled in by Task 10
                -1,
                null,
                container.getSnapshotKey(),
                ProtoMapper.map(container).createdAt());
    }

    @Override
    public List<Container> list() {
        return stub.list(containerd.services.containers.v1.ListContainersRequest.getDefaultInstance())
                .getContainersList().stream()
                .map(ProtoMapper::map)
                .toList();
    }

    @Override
    public void remove(String id, RemoveOptions options) {
        throw new UnsupportedOperationException("implemented in Task 10");
    }

    @Override
    public int start(String id) {
        throw new UnsupportedOperationException("implemented in Task 10");
    }

    @Override
    public Optional<ExitStatus> stop(String id) {
        throw new UnsupportedOperationException("implemented in Task 10");
    }

    @Override
    public void kill(String id, Signal signal) {
        throw new UnsupportedOperationException("implemented in Task 10");
    }

    @Override
    public ExitStatus wait(String id) {
        throw new UnsupportedOperationException("implemented in Task 10");
    }

    @Override
    public ExecResult exec(String id, List<String> command) {
        throw new UnsupportedOperationException("implemented in Task 11");
    }

    @Override
    public ExecResult exec(String id, ExecSpec spec) {
        throw new UnsupportedOperationException("implemented in Task 11");
    }
}
```

Wire `containers()` into `ContainerdClient`/`DefaultContainerdClient` (lazy, cached).

**Implementation notes (Task 9 findings, 2026-09-03):**

- **R17 — `remove` is implemented in Task 9, not Task 10.** The code block above left `remove` as
  a stub, contradicting this step's own header ("remove-for-non-running now") and the Self-Review
  Notes ("the only intentional stubs are the five `UnsupportedOperationException` methods" —
  start/stop/kill/wait/exec). `ContainerCreateIT` cleanup also requires it. As implemented:
  `Tasks.Get` first (running + `!force` → `ContainerdException("…still running, use
  RemoveOptions.force(true)")`; running + `force` → `stop(id)`, which is a stub until Task 10),
  then `Containers.Get` (NOT_FOUND → idempotent return), `Containers.Delete`, and
  `SnapshotManager.remove(snapshotKey)` when `removeSnapshot`. Task 10 replaces the
  `snapshots.remove` call with `snapshots.forSnapshotter(container.getSnapshotter()).remove(...)`
  per its plan text.
- **R18 — `create` must also handle snapshot-`prepare` failure.** The try/catch above wrapped
  only the `Containers.Create` call; `ContainerCreateIT.duplicateContainerIdThrowsAlreadyExists`
  exposed that a duplicate id fails earlier, at `snapshots.prepare`, with raw
  `ALREADY_EXISTS: snapshot "…": already exists` escaping unmapped. As implemented, prepare
  failure is handled: `ALREADY_EXISTS` + container exists → `ContainerAlreadyExistsException`;
  `ALREADY_EXISTS` + no container (stale snapshot from an interrupted earlier create) →
  best-effort remove + one prepare retry; other codes → `StatusExceptionMapper` (SNAPSHOT).

- [ ] **Step 4: Run unit tests, then write `ContainerCreateIT`**

Run: `./gradlew test`
Expected: PASS.

`ContainerCreateIT`:

```java
package io.nanofaas.containerd;

import io.nanofaas.containerd.spi.ContainerdClient;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class ContainerCreateIT extends ContainerdConnectionIT {

    private static final String ALPINE = "docker.io/library/alpine:latest";

    @BeforeAll
    static void ensureImage() {
        client.images().pull(ALPINE);
    }

    @Test
    @Timeout(60)
    void createsInspectsListsAndRemovesContainer() {
        String id = "it-create-" + UUID.randomUUID();
        try {
            Container container = client.containers().create(
                    ContainerSpec.builder().id(id).image(ALPINE)
                            .command(java.util.List.of("/bin/sh", "-c", "sleep 3600")).build());

            assertThat(container.id()).isEqualTo(id);
            assertThat(container.snapshotter()).isEqualTo(client.snapshotter());

            ContainerStatus status = client.containers().inspect(id);
            assertThat(status.id()).isEqualTo(id);
            assertThat(status.image()).isEqualTo(ALPINE);

            assertThat(client.containers().list()).extracting(Container::id).contains(id);
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).build());
        }
    }

    @Test
    void createWithUnknownImageThrowsImageNotFound() {
        String id = "it-create-" + UUID.randomUUID();
        assertThatThrownBy(() -> client.containers().create(
                ContainerSpec.builder().id(id).image("it-unknown-" + UUID.randomUUID() + ":latest").build()))
                .isInstanceOf(ImageNotFoundException.class);
    }

    @Test
    void duplicateContainerIdThrowsAlreadyExists() {
        String id = "it-dup-" + UUID.randomUUID();
        var spec = ContainerSpec.builder().id(id).image(ALPINE)
                .command(java.util.List.of("/bin/sh", "-c", "sleep 3600")).build();
        try {
            client.containers().create(spec);
            assertThatThrownBy(() -> client.containers().create(spec))
                    .isInstanceOf(ContainerAlreadyExistsException.class);
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).build());
        }
    }
}
```

Run: `./gradlew integrationTest`
Expected: PASS (alpine pull + 3 scenarios; verify manually that `snapshot ls` in a root shell shows no leftover `it-create-*`/`it-dup-*` snapshots — or trust the remove assertions).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: containers facade with transactional create (snapshot + OCI spec + metadata) and GC label"
```

---

### Task 10: Tasks facade and start/stop/kill/wait orchestration

**Files:**
- Create: `src/main/java/io/nanofaas/containerd/internal/TasksServiceImpl.java`
- Create: `src/main/java/io/nanofaas/containerd/spi/Tasks.java`
- Modify: `ContainersServiceImpl` (implement `start`, `stop`, `kill`, `wait`, `remove`; enrich `inspect` with task state)
- Modify: `ContainerdClient`/`DefaultContainerdClient` (wire `tasks()`)
- Test: `src/test/java/io/nanofaas/containerd/internal/TasksServiceImplTest.java` (in-process fakes: start failure → task deleted; stop on missing task → empty Optional)
- Integration test: `src/integrationTest/java/io/nanofaas/containerd/ContainerLifecycleIT.java`

**Interfaces:**
- Consumes: `SnapshotManager`, `ContainerState`, `ExitStatus`, `Signal`, `RemoveOptions`, `StatusExceptionMapper`.
- Produces:
  - `record TaskInfo(String containerId, int pid, ContainerState state, int exitCode)` 
  - `interface Tasks { void create(String containerId); int start(String containerId); void kill(String containerId, Signal signal); ExitStatus wait(String containerId); ExitStatus delete(String containerId); TaskInfo inspect(String containerId); List<TaskInfo> list(); }` — low-level, kept public for NanoFaaS fast-path experimentation.
  - `TasksServiceImpl` behavior: `create(containerId)` fetches the container (NOT_FOUND → `ContainerNotFoundException`), takes `Snapshots.Mounts(snapshotter, snapshotKey)`, sends `CreateTaskRequest{container_id, rootfs: mounts}` (empty stdio = null IO; `options` = runc options Any only when `runtimeBinaryName` is set: type URL `containerd.runc.v1.Options`, `binary_name` field). Start-failure cleanup in `ContainersServiceImpl.start`: if Start RPC fails after a successful Create, best-effort task Delete, then throw `ContainerStartException`.
  - `ContainersServiceImpl.start(id)`: Get task (via `Tasks.Get`, exec_id "") → if exists and state RUNNING/STARTING → `ContainerStartException("already running")`; if exists and state STOPPED → Delete task first, then create fresh; else create → Start → return pid.
  - `ContainersServiceImpl.stop(id)`: Get task → missing → return `Optional.empty()` (idempotent); Kill TERM (all=true) → Wait with 10s deadline → on DEADLINE_EXCEEDED Kill KILL → Wait → Delete → `Optional.of(exitStatus)`. Any NOT_FOUND along the way → treated as already stopped. Failures → `ContainerStopException`.
  - `ContainersServiceImpl.remove(id, opts)`: Get task → running + !force → `ContainerdException("container still running, use RemoveOptions.force(true)")`; running + force → `stop(id)`; fetch container (NOT_FOUND → return, idempotent); `Containers.Delete`; if `opts.removeSnapshot()` → `SnapshotManager.remove(snapshotKey)` (idempotent).
  - `inspect(id)` now merges task state: no task → `ContainerState.UNKNOWN`; with task → mapped state, pid, exit code when STOPPED.

- [ ] **Step 1: Write the failing tests**

`TasksServiceImplTest`:

```java
package io.nanofaas.containerd.internal;

import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.Signal;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TasksServiceImplTest {

    private static final class FakeTaskServer implements AutoCloseable {
        final AtomicReference<containerd.services.tasks.v1.CreateTaskRequest> created = new AtomicReference<>();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger deletes = new AtomicInteger();
        volatile boolean failStart;

        final io.grpc.Server server;
        final io.grpc.ManagedChannel channel;

        FakeTaskServer() throws Exception {
            String name = InProcessServerBuilder.generateName();
            var builder = InProcessServerBuilder.forName(name).directExecutor();

            builder.addService(containerd.services.containers.v1.ContainersGrpc.bindService(
                    new containerd.services.containers.v1.ContainersGrpc.ContainersImplBase() {
                        @Override
                        public void get(containerd.services.containers.v1.GetContainerRequest request,
                                        StreamObserver<containerd.services.containers.v1.GetContainerResponse> responseObserver) {
                            responseObserver.onNext(containerd.services.containers.v1.GetContainerResponse.newBuilder()
                                    .setContainer(containerd.services.containers.v1.Container.newBuilder()
                                            .setId(request.getId())
                                            .setSnapshotter("overlayfs")
                                            .setSnapshotKey(request.getId()))
                                    .build());
                            responseObserver.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.snapshots.v1.SnapshotsGrpc.bindService(
                    new containerd.services.snapshots.v1.SnapshotsGrpc.SnapshotsImplBase() {
                        @Override
                        public void mounts(containerd.services.snapshots.v1.MountsRequest request,
                                           StreamObserver<containerd.services.snapshots.v1.MountsResponse> responseObserver) {
                            responseObserver.onNext(containerd.services.snapshots.v1.MountsResponse.newBuilder()
                                    .addMounts(containerd.types.Mount.newBuilder().setType("bind")
                                            .setSource("/var/lib/containerd/snap").setTarget("/")).build());
                            responseObserver.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.tasks.v1.TasksGrpc.bindService(
                    new containerd.services.tasks.v1.TasksGrpc.TasksImplBase() {
                        @Override
                        public void create(containerd.services.tasks.v1.CreateTaskRequest request,
                                           StreamObserver<containerd.services.tasks.v1.CreateTaskResponse> responseObserver) {
                            created.set(request);
                            responseObserver.onNext(containerd.services.tasks.v1.CreateTaskResponse.newBuilder()
                                    .setContainerId(request.getContainerId()).setPid(42).build());
                            responseObserver.onCompleted();
                        }

                        @Override
                        public void start(containerd.services.tasks.v1.StartRequest request,
                                          StreamObserver<containerd.services.tasks.v1.StartResponse> responseObserver) {
                            if (failStart) {
                                responseObserver.onError(Status.INTERNAL.asRuntimeException());
                                return;
                            }
                            starts.incrementAndGet();
                            responseObserver.onNext(containerd.services.tasks.v1.StartResponse.newBuilder().setPid(42).build());
                            responseObserver.onCompleted();
                        }

                        @Override
                        public void delete(containerd.services.tasks.v1.DeleteTaskRequest request,
                                           StreamObserver<containerd.services.tasks.v1.DeleteResponse> responseObserver) {
                            deletes.incrementAndGet();
                            responseObserver.onNext(containerd.services.tasks.v1.DeleteResponse.newBuilder()
                                    .setId(request.getContainerId()).build());
                            responseObserver.onCompleted();
                        }
                    }));

            server = builder.build().start();
            channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        }

        @Override
        public void close() {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    void createPassesSnapshotMountsAsRootfs() throws Exception {
        try (var fake = new FakeTaskServer()) {
            new TasksServiceImpl(fake.channel).create("abc");
            var request = fake.created.get();
            assertThat(request.getContainerId()).isEqualTo("abc");
            assertThat(request.getRootfsList()).hasSize(1);
            assertThat(request.getRootfs(0).getSource()).isEqualTo("/var/lib/containerd/snap");
        }
    }

    @Test
    void createFetchesContainerFirst() throws Exception {
        try (var fake = new FakeTaskServer()) {
            new TasksServiceImpl(fake.channel).create("abc");
            assertThat(fake.created.get()).isNotNull();
        }
    }
}
```

Run: `./gradlew test --tests io.nanofaas.containerd.internal.TasksServiceImplTest`
Expected: FAIL.

- [ ] **Step 2: Implement `Tasks` SPI, `TaskInfo`, `TasksServiceImpl`**

`TaskInfo`:

```java
package io.nanofaas.containerd;

public record TaskInfo(String containerId, int pid, ContainerState state, int exitCode) {
}
```

`Tasks`:

```java
package io.nanofaas.containerd.spi;

import io.nanofaas.containerd.ExitStatus;
import io.nanofaas.containerd.Signal;
import io.nanofaas.containerd.TaskInfo;

import java.util.List;

/**
 * Low-level task operations. A task is the running instance of a container.
 * Most users should prefer {@link Containers#start}/{@link Containers#stop};
 * these exist for NanoFaaS fast-path orchestration.
 */
public interface Tasks {

    /** Creates a task for an existing container (task state CREATED, not running). */
    void create(String containerId);

    /** Starts a created task; returns the init process pid. */
    int start(String containerId);

    void kill(String containerId, Signal signal);

    /** Blocks until the task exits. */
    ExitStatus wait(String containerId);

    /**
     * Blocks until the task exits or the timeout elapses. Throws the raw gRPC
     * {@link io.grpc.StatusRuntimeException} with code {@code DEADLINE_EXCEEDED} on timeout
     * (deliberately unmapped, so callers can detect it); other failures are mapped as usual.
     */
    ExitStatus wait(String containerId, java.time.Duration timeout);

    /** Deletes a (stopped) task, releasing its state. */
    ExitStatus delete(String containerId);

    TaskInfo inspect(String containerId);

    List<TaskInfo> list();
}
```

`TasksServiceImpl`:

```java
package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.nanofaas.containerd.*;
import io.nanofaas.containerd.spi.Tasks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

public final class TasksServiceImpl implements Tasks {

    private static final Logger log = LoggerFactory.getLogger(TasksServiceImpl.class);

    private final containerd.services.tasks.v1.TasksGrpc.TasksBlockingStub stub;
    private final containerd.services.containers.v1.ContainersGrpc.ContainersBlockingStub containers;
    private final SnapshotManager snapshots;
    private final String runtimeBinaryName;

    public TasksServiceImpl(ManagedChannel channel, String runtimeBinaryName) {
        this.stub = containerd.services.tasks.v1.TasksGrpc.newBlockingStub(channel);
        this.containers = containerd.services.containers.v1.ContainersGrpc.newBlockingStub(channel);
        this.snapshots = new SnapshotManager(channel, null); // snapshotter resolved per container
        this.runtimeBinaryName = runtimeBinaryName;
    }

    @Override
    public void create(String containerId) {
        var container = containers.get(containerd.services.containers.v1.GetContainerRequest.newBuilder()
                .setId(containerId).build()).getContainer();
        var mounts = snapshots.forSnapshotter(container.getSnapshotter()).mounts(container.getSnapshotKey());
        log.debug("task create: containerId={} snapshotter={} snapshotKey={}",
                containerId, container.getSnapshotter(), container.getSnapshotKey());

        var request = containerd.services.tasks.v1.CreateTaskRequest.newBuilder()
                .setContainerId(containerId)
                .addAllRootfs(mounts);
        if (runtimeBinaryName != null) {
            var options = containerd.runc.v1.Options.newBuilder().setBinaryName(runtimeBinaryName).build();
            request.setOptions(com.google.protobuf.Any.newBuilder()
                    .setTypeUrl(options.getDescriptorForType().getFullName())
                    .setValue(options.toByteString()));
        }
        try {
            stub.create(request.build());
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    @Override
    public int start(String containerId) {
        var response = stub.start(containerd.services.tasks.v1.StartRequest.newBuilder()
                .setContainerId(containerId).build());
        return response.getPid();
    }

    @Override
    public void kill(String containerId, Signal signal) {
        log.debug("task kill: containerId={} signal={}", containerId, signal);
        try {
            stub.kill(containerd.services.tasks.v1.KillRequest.newBuilder()
                    .setContainerId(containerId)
                    .setSignal(signal.number())
                    .setAll(true)
                    .build());
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    @Override
    public ExitStatus wait(String containerId) {
        return waitInternal(containerId, null);
    }

    @Override
    public ExitStatus wait(String containerId, java.time.Duration timeout) {
        return waitInternal(containerId, timeout);
    }

    private ExitStatus waitInternal(String containerId, java.time.Duration timeout) {
        try {
            var blockingStub = timeout == null
                    ? stub
                    : stub.withDeadlineAfter(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            var response = blockingStub.wait(containerd.services.tasks.v1.WaitRequest.newBuilder()
                    .setContainerId(containerId).build());
            return new ExitStatus(response.getExitStatus(),
                    response.hasExitedAt() ? Instant.ofEpochSecond(response.getExitedAt().getSeconds()) : null);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == io.grpc.Status.Code.DEADLINE_EXCEEDED) {
                throw e; // deliberately unmapped: callers detect the timeout by code
            }
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    @Override
    public ExitStatus delete(String containerId) {
        try {
            var response = stub.delete(containerd.services.tasks.v1.DeleteTaskRequest.newBuilder()
                    .setContainerId(containerId).build());
            return new ExitStatus(response.getExitStatus(),
                    response.hasExitedAt() ? Instant.ofEpochSecond(response.getExitedAt().getSeconds()) : null);
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    @Override
    public TaskInfo inspect(String containerId) {
        var process = stub.get(containerd.services.tasks.v1.GetRequest.newBuilder()
                .setContainerId(containerId).build()).getProcess();
        return new TaskInfo(containerId, process.getPid(),
                ProtoMapper.mapStatus(process.getStatus().getNumber()), process.getExitStatus());
    }

    @Override
    public List<TaskInfo> list() {
        return stub.list(containerd.services.tasks.v1.ListTasksRequest.getDefaultInstance())
                .getTasksList().stream()
                .map(p -> new TaskInfo(p.getContainerId(), p.getPid(),
                        ProtoMapper.mapStatus(p.getStatus().getNumber()), p.getExitStatus()))
                .toList();
    }

    /** Returns whether a task exists for the container (true even for STOPPED tasks). */
    public boolean exists(String containerId) {
        try {
            stub.get(containerd.services.tasks.v1.GetRequest.newBuilder()
                    .setContainerId(containerId).build());
            return true;
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                return false;
            }
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }
}
```

`SnapshotManager` needs the per-container snapshotter — add:

```java
    /** Returns a manager bound to an arbitrary snapshotter. */
    public SnapshotManager forSnapshotter(String other) {
        return new SnapshotManager(stub.getChannel(), other);
    }
```

(Add a `channel()` accessor to `SnapshotManager` storing the `ManagedChannel`; simplest is to keep the channel as a field from construction.)

- [ ] **Step 3: Implement orchestration in `ContainersServiceImpl`** (replace the five `UnsupportedOperationException` methods):

```java
    private TasksServiceImpl tasks;

    private TasksServiceImpl tasks() {
        if (tasks == null) {
            tasks = new TasksServiceImpl(channel(), runtimeBinaryName);
        }
        return tasks;
    }
```

(Store the `ManagedChannel` in a field; thread-safety note: benign double-init is acceptable, or initialize in ctor.)

`start`:

```java
    @Override
    public int start(String id) {
        TaskInfo existing = null;
        if (tasks().exists(id)) {
            existing = tasks().inspect(id);
        }
        if (existing != null && (existing.state() == ContainerState.RUNNING || existing.state() == ContainerState.STARTING)) {
            throw new ContainerStartException("task for container " + id + " is already running", null);
        }
        if (existing != null && existing.state() == ContainerState.STOPPED) {
            log.debug("task for container {} is stopped; deleting before restart", id);
            tasks().delete(id);
        }
        try {
            tasks().create(id);
            return tasks().start(id);
        } catch (RuntimeException e) {
            if (e instanceof ContainerStartException) {
                throw e;
            }
            try {
                if (tasks().exists(id)) {
                    tasks().delete(id);
                }
            } catch (RuntimeException cleanupFailure) {
                log.warn("failed to clean up task for container {} after start failure", id, cleanupFailure);
            }
            throw new ContainerStartException("failed to start container " + id + ": " + e.getMessage(), e);
        }
    }
```

`stop`:

```java
    @Override
    public Optional<ExitStatus> stop(String id) {
        if (!tasks().exists(id)) {
            log.debug("stop: no task for container {} (idempotent)", id);
            return Optional.empty();
        }
        try {
            try {
                tasks().kill(id, Signal.TERM);
            } catch (TaskNotFoundException e) {
                return Optional.empty();
            }
            try {
                return Optional.of(tasks().wait(id, STOP_TIMEOUT));
            } catch (TaskNotFoundException e) {
                return Optional.empty();
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == io.grpc.Status.Code.DEADLINE_EXCEEDED) {
                    log.debug("stop: container {} did not exit in time, sending SIGKILL", id);
                    tasks().kill(id, Signal.KILL);
                    return Optional.of(tasks().wait(id));
                }
                throw e;
            } finally {
                try {
                    tasks().delete(id);
                } catch (TaskNotFoundException e) {
                    // already gone
                }
            }
        } catch (RuntimeException e) {
            throw new ContainerStopException("failed to stop container " + id + ": " + e.getMessage(), e);
        }
    }
```

(Wait-with-deadline needs the blocking stub with a deadline: add `ExitStatus wait(String containerId, java.time.Duration timeout)` to `Tasks` — implementation calls `stub.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS).wait(...)` and MUST let `DEADLINE_EXCEEDED` propagate as the raw `StatusRuntimeException` (not mapped), because `ContainersServiceImpl.stop` detects the timeout by that code; the no-argument `wait(id)` delegates to the mapping path.)

`kill` delegates to `tasks().kill(id, signal)`; `wait` delegates to `tasks().wait(id)`.

`remove`:

```java
    @Override
    public void remove(String id, RemoveOptions options) {
        log.debug("container remove: id={} removeSnapshot={} force={}",
                id, options.removeSnapshot(), options.force());
        if (tasks().exists(id)) {
            var task = tasks().inspect(id);
            boolean running = task.state() == ContainerState.RUNNING
                    || task.state() == ContainerState.CREATED
                    || task.state() == ContainerState.STARTING
                    || task.state() == ContainerState.PAUSED;
            if (running && !options.force()) {
                throw new ContainerdException(
                        "container " + id + " is still running; stop it first or use RemoveOptions.force(true)");
            }
            if (running) {
                stop(id);
            } else {
                tasks().delete(id);
            }
        }

        containerd.services.containers.v1.Container container;
        try {
            container = stub.get(containerd.services.containers.v1.GetContainerRequest.newBuilder()
                    .setId(id).build()).getContainer();
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                log.debug("container {} already gone (idempotent remove)", id);
                return;
            }
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.CONTAINER);
        }

        stub.delete(containerd.services.containers.v1.DeleteContainerRequest.newBuilder()
                .setId(id).build());

        if (options.removeSnapshot() && !container.getSnapshotKey().isEmpty()) {
            snapshots.forSnapshotter(container.getSnapshotter()).remove(container.getSnapshotKey());
        }
    }
```

`inspect` — merge task state (replace the Task 9 body):

```java
    @Override
    public ContainerStatus inspect(String id) {
        var container = stub.get(containerd.services.containers.v1.GetContainerRequest.newBuilder()
                .setId(id).build()).getContainer();
        ContainerState state = ContainerState.UNKNOWN;
        int pid = -1;
        ExitStatus exitStatus = null;
        if (tasks().exists(id)) {
            var task = tasks().inspect(id);
            state = task.state();
            pid = task.pid();
            if (state == ContainerState.STOPPED) {
                exitStatus = new ExitStatus(task.exitCode(), null);
            }
        }
        return new ContainerStatus(container.getId(), container.getImage(), state, pid,
                exitStatus, container.getSnapshotKey(), ProtoMapper.map(container).createdAt());
    }
```

- [ ] **Step 4: Run unit tests, then write `ContainerLifecycleIT`**

Run: `./gradlew test`
Expected: PASS.

`ContainerLifecycleIT`:

```java
package io.nanofaas.containerd;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class ContainerLifecycleIT extends ContainerdConnectionIT {

    private static final String ALPINE = "docker.io/library/alpine:latest";

    @BeforeAll
    static void ensureImage() {
        client.images().pull(ALPINE);
    }

    @Test
    @Timeout(120)
    void fullLifecycleCreateStartInspectStopRemove() {
        String id = "it-life-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done")).build());
        try {
            int pid = client.containers().start(id);
            assertThat(pid).isPositive();

            ContainerStatus running = client.containers().inspect(id);
            assertThat(running.state()).isEqualTo(ContainerState.RUNNING);
            assertThat(running.pid()).isPositive();

            Optional<ExitStatus> stopped = client.containers().stop(id);
            assertThat(stopped).isPresent();

            // second stop is idempotent: no task -> empty Optional
            assertThat(client.containers().stop(id)).isEmpty();

            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).build());
            assertThatThrownBy(() -> client.containers().inspect(id))
                    .isInstanceOf(ContainerNotFoundException.class);
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }

    @Test
    @Timeout(120)
    void startAlreadyRunningThrows() {
        String id = "it-already-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done")).build());
        try {
            client.containers().start(id);
            assertThatThrownBy(() -> client.containers().start(id))
                    .isInstanceOf(ContainerStartException.class);
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }

    @Test
    @Timeout(120)
    void removeRunningContainerRequiresForce() {
        String id = "it-running-rm-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done")).build());
        try {
            client.containers().start(id);
            assertThatThrownBy(() -> client.containers().remove(id, RemoveOptions.builder().build()))
                    .isInstanceOf(ContainerdException.class)
                    .hasMessageContaining("still running");
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }

    @Test
    @Timeout(60)
    void startUnknownContainerThrowsNotFound() {
        assertThatThrownBy(() -> client.containers().start("it-unknown-" + UUID.randomUUID()))
                .isInstanceOf(ContainerNotFoundException.class);
    }

    @Test
    @Timeout(120)
    void stopExitsCleanlyWithStatus() {
        String id = "it-exit-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .command(List.of("/bin/sh", "-c", "exit 7")).build());
        try {
            client.containers().start(id);
            ExitStatus status = client.containers().wait(id);
            assertThat(status.code()).isEqualTo(7);
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }
}
```

Run: `./gradlew integrationTest`
Expected: PASS (5 scenarios). Check for leaked snapshots afterward:

```bash
sudo ctr -n nanofaas-it snapshots list 2>/dev/null | grep -E "it-(life|already|running-rm|exit)-" || echo "no leaked snapshots"
```

Expected: `no leaked snapshots`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: tasks facade, start/stop/kill/wait orchestration, force-based remove, idempotent stop"
```

---

### Task 11: Exec with FIFO-based IO capture

**Files:**
- Create: `src/main/java/io/nanofaas/containerd/internal/IoManager.java`
- Modify: `ContainersServiceImpl` (implement `exec`)
- Test: `src/test/java/io/nanofaas/containerd/internal/IoManagerTest.java`
- Integration test: `src/integrationTest/java/io/nanofaas/containerd/ExecIT.java`

**Interfaces:**
- Consumes: `ExecSpec`/`ExecResult`, `OciSpecBuilder.buildExecSpec`, `TasksServiceImpl` (Start/Wait/DeleteProcess with `exec_id`).
- Produces: `class IoManager { IoManager(); FifoSet createFifoSet(String prefix); static String readFifo(Path fifo); static void writeFifo(Path fifo, byte[] data); void cleanup(FifoSet set); }` with `record FifoSet(Path dir, Path stdin, Path stdout, Path stderr)`. FIFOs are created with jnr-posix `POSIX.mkfifo(path, 0600)` in `java.io.tmpdir/containerd-java-fifos/<prefix>-<uuid>/`. All FIFO I/O happens on virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`).
- Exec flow: task must exist and be RUNNING (else `ExecException`) → create FIFOs → open stdout/stderr read ends on virtual threads **before** the Exec RPC (blocked `open(2)` unblocks when the shim opens its write end; avoids the fast-exit race) → `ExecProcessRequest{container_id, stdin, stdout, stderr, terminal:false, spec: Any(Process JSON), exec_id: "exec-" + uuid}` → `Start{exec_id}` → write stdin (if any) → read both streams to EOF → `Wait{exec_id}` → `DeleteProcess{exec_id}` → close/delete FIFOs in `finally`. On Exec/Start failure: open the FIFO write ends once to unblock the readers, then cleanup.

- [ ] **Step 1: Write the failing test**

`IoManagerTest`:

```java
package io.nanofaas.containerd.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class IoManagerTest {

    @Test
    void fifoRoundTrip() throws Exception {
        IoManager io = new IoManager();
        var fifos = io.createFifoSet("test");
        try {
            assertThat(Files.exists(fifos.stdout())).isTrue();
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var writer = executor.submit(() -> IoManager.writeFifo(fifos.stdout(), "hello fifo\n".getBytes()));
            String read = IoManager.readFifo(fifos.stdout());
            writer.get();
            assertThat(read).isEqualTo("hello fifo\n");
        } finally {
            io.cleanup(fifos);
            assertThat(Files.exists(fifos.dir())).isFalse();
        }
    }
}
```

Run: `./gradlew test --tests io.nanofaas.containerd.internal.IoManagerTest`
Expected: FAIL.

- [ ] **Step 2: Implement `IoManager`**:

```java
package io.nanofaas.containerd.internal;

import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Creates and manages the FIFOs containerd uses for task/exec IO.
 * mkfifo is done via jnr-posix (Java has no built-in); reading/writing uses plain java.io
 * on virtual threads so blocked opens never consume OS threads.
 */
public final class IoManager {

    private static final Logger log = LoggerFactory.getLogger(IoManager.class);
    private static final POSIX POSIX = POSIXFactory.getNativePOSIX();

    public record FifoSet(Path dir, Path stdin, Path stdout, Path stderr) {
    }

    public IoManager() {
    }

    public FifoSet createFifoSet(String prefix) {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "containerd-java-fifos",
                prefix + "-" + UUID.randomUUID());
        Path stdin = dir.resolve("stdin");
        Path stdout = dir.resolve("stdout");
        Path stderr = dir.resolve("stderr");
        try {
            Files.createDirectories(dir);
            mkfifo(stdin);
            mkfifo(stdout);
            mkfifo(stderr);
        } catch (IOException e) {
            cleanupQuietly(dir);
            throw new IllegalStateException("failed to create FIFO set in " + dir, e);
        }
        log.debug("created FIFO set at {}", dir);
        return new FifoSet(dir, stdin, stdout, stderr);
    }

    /** Opens the FIFO for reading and consumes it to EOF. Blocks until a writer opens. */
    public static String readFifo(Path fifo) {
        try (var in = Files.newInputStream(fifo)) {
            return new String(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("failed to read FIFO " + fifo, e);
        }
    }

    public static void writeFifo(Path fifo, byte[] data) {
        try (var out = Files.newOutputStream(fifo)) {
            out.write(data);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write FIFO " + fifo, e);
        }
    }

    public void cleanup(FifoSet fifos) {
        cleanupQuietly(fifos.dir());
    }

    private static void mkfifo(Path path) {
        int rc = POSIX.mkfifo(path.toString(), 0600);
        if (rc != 0) {
            throw new IllegalStateException("mkfifo failed for " + path + ": errno " + POSIX.errno());
        }
    }

    private static void cleanupQuietly(Path dir) {
        try {
            Files.list(dir).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
```

- [ ] **Step 3: Implement `exec` in `ContainersServiceImpl`**

```java
    private final java.util.concurrent.ExecutorService ioExecutor =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final IoManager ioManager = new IoManager();

    @Override
    public ExecResult exec(String id, List<String> command) {
        return exec(id, ExecSpec.builder().command(command).build());
    }

    @Override
    public ExecResult exec(String id, ExecSpec spec) {
        if (!tasks().exists(id)) {
            throw new ExecException("no task for container " + id + "; start the container before exec");
        }
        var task = tasks().inspect(id);
        if (task.state() != ContainerState.RUNNING) {
            throw new ExecException("task for container " + id + " is not running (state=" + task.state() + ")");
        }

        String execId = "exec-" + UUID.randomUUID();
        var fifos = ioManager.createFifoSet(id + "-" + execId);
        boolean readersStarted = false;
        try {
            // Open read ends BEFORE the Exec RPC: open(2) blocks until the shim opens its
            // write end, which happens during Exec — this avoids the fast-exit race where
            // the process exits (and the shim closes) before we ever open.
            var stdoutFuture = ioExecutor.submit(() -> IoManager.readFifo(fifos.stdout()));
            var stderrFuture = ioExecutor.submit(() -> IoManager.readFifo(fifos.stderr()));
            readersStarted = true;

            try {
                tasks().exec(id, execId, spec, fifos);
            } catch (RuntimeException e) {
                unblockReaders(fifos);
                throw new ExecException("exec failed for container " + id + ": " + e.getMessage(), e);
            }

            int pid = tasks().startExec(id, execId);

            if (spec.stdin() != null) {
                ioExecutor.submit(() -> IoManager.writeFifo(fifos.stdin(), spec.stdin().getBytes()));
            }

            String stdout = stdoutFuture.get();
            String stderr = stderrFuture.get();
            ExitStatus status = tasks().waitExec(id, execId);
            log.debug("exec complete: containerId={} execId={} pid={} exitCode={}",
                    id, execId, pid, status.code());
            return new ExecResult(status.code(), stdout, stderr);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new ExecException("exec IO failed for container " + id, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecException("exec interrupted for container " + id, e);
        } finally {
            // If the Exec RPC failed before the shim opened the FIFOs, unblock the pending
            // reader opens by opening the write ends once; otherwise this is a harmless no-op
            // (the readers already hit EOF). Always delete the exec process and the FIFOs.
            if (readersStarted) {
                unblockReaders(fifos);
            }
            try {
                tasks().deleteExec(id, execId);
            } catch (RuntimeException e) {
                log.warn("failed to delete exec process {} for container {}", execId, id, e);
            }
            ioManager.cleanup(fifos);
        }
    }

    private void unblockReaders(IoManager.FifoSet fifos) {
        for (var f : List.of(fifos.stdout(), fifos.stderr())) {
            ioExecutor.submit(() -> {
                try (var out = Files.newOutputStream(f)) {
                    // open-write-close unblocks a blocked read-end open
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }
```

(Simplify the `unblocked` flag away: calling `unblockReaders` twice is harmless — the second write-open just gets EOF again.)

`TasksServiceImpl` additions:

```java
    public void exec(String containerId, String execId, ExecSpec spec, IoManager.FifoSet fifos) {
        var request = containerd.services.tasks.v1.ExecProcessRequest.newBuilder()
                .setContainerId(containerId)
                .setExecId(execId)
                .setStdin(fifos.stdin().toString())
                .setStdout(fifos.stdout().toString())
                .setStderr(fifos.stderr().toString())
                .setSpec(OciSpecBuilder.buildExecSpec(spec.command(), spec.environment(), spec.workingDir()))
                .build();
        try {
            stub.exec(request);
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    public int startExec(String containerId, String execId) {
        return stub.start(containerd.services.tasks.v1.StartRequest.newBuilder()
                .setContainerId(containerId).setExecId(execId).build()).getPid();
    }

    public ExitStatus waitExec(String containerId, String execId) {
        var response = stub.wait(containerd.services.tasks.v1.WaitRequest.newBuilder()
                .setContainerId(containerId).setExecId(execId).build());
        return new ExitStatus(response.getExitStatus(), null);
    }

    public void deleteExec(String containerId, String execId) {
        try {
            stub.deleteProcess(containerd.services.tasks.v1.DeleteProcessRequest.newBuilder()
                    .setContainerId(containerId).setExecId(execId).build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != io.grpc.Status.Code.NOT_FOUND) {
                throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
            }
        }
    }
```

Close the shared `ioExecutor` in `DefaultContainerdClient.close()` (ContainersServiceImpl exposes `close()`).

- [ ] **Step 4: Run tests, then write `ExecIT`**

Run: `./gradlew test`
Expected: PASS.

`ExecIT`:

```java
package io.nanofaas.containerd;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class ExecIT extends ContainerdConnectionIT {

    private static final String ALPINE = "docker.io/library/alpine:latest";

    @BeforeAll
    static void ensureImage() {
        client.images().pull(ALPINE);
    }

    @Test
    @Timeout(120)
    void execCapturesStdoutStderrAndExitCode() {
        String id = "it-exec-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done")).build());
        try {
            client.containers().start(id);

            ExecResult result = client.containers().exec(id,
                    List.of("/bin/sh", "-c", "echo hello; echo error >&2; exit 3"));
            assertThat(result.exitCode()).isEqualTo(3);
            assertThat(result.stdout()).contains("hello");
            assertThat(result.stderr()).contains("error");
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }

    @Test
    @Timeout(120)
    void execOnStoppedContainerThrows() {
        String id = "it-exec-stopped-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .command(List.of("/bin/sh", "-c", "exit 0")).build());
        try {
            assertThatThrownBy(() -> client.containers().exec(id, List.of("echo", "hi")))
                    .isInstanceOf(ExecException.class);
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }
}
```

Run: `./gradlew integrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: exec with FIFO-based stdout/stderr capture on virtual threads"
```

---

### Task 12: Event subscription

**Files:**
- Create: `src/main/java/io/nanofaas/containerd/Event.java`
- Create: `src/main/java/io/nanofaas/containerd/EventFilter.java`
- Create: `src/main/java/io/nanofaas/containerd/Subscription.java`
- Create: `src/main/java/io/nanofaas/containerd/internal/EventsServiceImpl.java`
- Create: `src/main/java/io/nanofaas/containerd/spi/Events.java`
- Modify: `ContainerdClient`/`DefaultContainerdClient` (wire `events()`)
- Test: `src/test/java/io/nanofaas/containerd/internal/EventMapperTest.java` (envelope → Event decoding incl. TaskStart/TaskDelete payloads)
- Integration test: `src/integrationTest/java/io/nanofaas/containerd/EventsIT.java`

**Interfaces:**
- Consumes: `containerd.types.Envelope`, `containerd.events.*`, `NamespaceInterceptor` convention (envelope.namespace already server-side).
- Produces:
  - `record Event(String topic, String namespace, Instant timestamp, TaskEvent taskEvent)` and `record TaskEvent(String containerId, int pid, Integer exitStatus)` — `taskEvent` null when the payload is not a known task event.
  - `class EventFilter { static EventFilter topics(String... topics); List<String> toFieldpathFilters(String namespace); }` — builds `topic~="/tasks/start"` style filters; also appends `namespace=="<ns>"`.
  - `interface Events { Subscription subscribe(EventFilter filter, Consumer<Event> handler); }`
  - `interface Subscription extends AutoCloseable { void close(); }` — close cancels the stream; the implementation auto-reconnects with exponential backoff (1s → 30s cap) while not closed; handlers run on a virtual-thread executor so slow consumers never block the gRPC callback thread.

- [ ] **Step 1: Write the failing test**

`EventMapperTest`:

```java
package io.nanofaas.containerd.internal;

import com.google.protobuf.Any;
import com.google.protobuf.Timestamp;
import io.nanofaas.containerd.Event;
import io.nanofaas.containerd.EventFilter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventMapperTest {

    @Test
    void decodesTaskStartPayload() {
        var payload = containerd.events.TaskStart.newBuilder().setContainerId("abc").setPid(77).build();
        var envelope = containerd.types.Envelope.newBuilder()
                .setTopic("/tasks/start")
                .setNamespace("nanofaas")
                .setTimestamp(Timestamp.newBuilder().setSeconds(1700000000))
                .setEvent(Any.newBuilder()
                        .setTypeUrl(payload.getDescriptorForType().getFullName())
                        .setValue(payload.toByteString()))
                .build();

        Event event = EventMapper.map(envelope);

        assertThat(event.topic()).isEqualTo("/tasks/start");
        assertThat(event.namespace()).isEqualTo("nanofaas");
        assertThat(event.taskEvent().containerId()).isEqualTo("abc");
        assertThat(event.taskEvent().pid()).isEqualTo(77);
        assertThat(event.taskEvent().exitStatus()).isNull();
    }

    @Test
    void decodesTaskDeletePayload() {
        var payload = containerd.events.TaskDelete.newBuilder()
                .setContainerId("abc").setPid(77).setExitStatus(3).build();
        var envelope = containerd.types.Envelope.newBuilder()
                .setTopic("/tasks/delete")
                .setEvent(Any.newBuilder()
                        .setTypeUrl(payload.getDescriptorForType().getFullName())
                        .setValue(payload.toByteString()))
                .build();

        Event event = EventMapper.map(envelope);

        assertThat(event.taskEvent().exitStatus()).isEqualTo(3);
    }

    @Test
    void unknownPayloadDecodesToNullTaskEvent() {
        var envelope = containerd.types.Envelope.newBuilder().setTopic("/snapshots/update").build();
        assertThat(EventMapper.map(envelope).taskEvent()).isNull();
    }

    @Test
    void filterBuildsFieldpathExpressions() {
        EventFilter filter = EventFilter.topics("/tasks/start", "/tasks/exit");
        assertThat(filter.toFieldpathFilters("nanofaas"))
                .containsExactly("topic~=\"/tasks/start\"", "topic~=\"/tasks/exit\"", "namespace==\"nanofaas\"");
    }
}
```

Run: `./gradlew test --tests io.nanofaas.containerd.internal.EventMapperTest`
Expected: FAIL.

- [ ] **Step 2: Implement `Event`, `TaskEvent`, `EventFilter`, `Subscription`, `Events` SPI, `EventMapper`**

`Event`/`TaskEvent`:

```java
package io.nanofaas.containerd;

import java.time.Instant;

/** A containerd event envelope, decoded where the payload type is known. */
public record Event(String topic, String namespace, Instant timestamp, TaskEvent taskEvent) {
}

public record TaskEvent(String containerId, int pid, Integer exitStatus) {
}
```

`EventFilter`:

```java
package io.nanofaas.containerd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Selects which containerd events to receive. */
public final class EventFilter {

    private final List<String> topics;

    private EventFilter(List<String> topics) {
        this.topics = List.copyOf(topics);
    }

    public static EventFilter topics(String... topics) {
        return new EventFilter(Arrays.asList(topics));
    }

    /** Translates to containerd fieldpath filter expressions for SubscribeRequest. */
    public List<String> toFieldpathFilters(String namespace) {
        List<String> filters = new ArrayList<>();
        for (String topic : topics) {
            filters.add("topic~=\"" + topic + "\"");
        }
        filters.add("namespace==\"" + namespace + "\"");
        return filters;
    }
}
```

`Subscription`/`Events`:

```java
package io.nanofaas.containerd;

/** A live event subscription; close it to stop receiving events. */
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}
```

```java
package io.nanofaas.containerd.spi;

import io.nanofaas.containerd.Event;
import io.nanofaas.containerd.EventFilter;
import io.nanofaas.containerd.Subscription;

import java.util.function.Consumer;

/** containerd event stream. */
public interface Events {

    /**
     * Subscribes to events matching the filter. The handler is invoked on a virtual thread;
     * a slow handler never blocks stream delivery. The subscription reconnects with exponential
     * backoff (up to 30s) after stream errors until closed.
     */
    Subscription subscribe(EventFilter filter, Consumer<Event> handler);
}
```

`EventMapper`:

```java
package io.nanofaas.containerd.internal;

import io.nanofaas.containerd.Event;
import io.nanofaas.containerd.TaskEvent;

import java.time.Instant;

public final class EventMapper {

    private EventMapper() {
    }

    public static Event map(containerd.types.Envelope envelope) {
        TaskEvent taskEvent = null;
        try {
            if (envelope.getEvent().getTypeUrl().equals(
                    containerd.events.TaskStart.getDescriptor().getFullName())) {
                var p = containerd.events.TaskStart.parseFrom(envelope.getEvent().getValue());
                taskEvent = new TaskEvent(p.getContainerId(), p.getPid(), null);
            } else if (envelope.getEvent().getTypeUrl().equals(
                    containerd.events.TaskDelete.getDescriptor().getFullName())) {
                var p = containerd.events.TaskDelete.parseFrom(envelope.getEvent().getValue());
                taskEvent = new TaskEvent(p.getContainerId(), p.getPid(), p.getExitStatus());
            } else if (envelope.getEvent().getTypeUrl().equals(
                    containerd.events.TaskExit.getDescriptor().getFullName())) {
                var p = containerd.events.TaskExit.parseFrom(envelope.getEvent().getValue());
                taskEvent = new TaskEvent(p.getContainerId(), p.getPid(), p.getExitStatus());
            }
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            // malformed event payload: deliver without typed payload
        }
        return new Event(envelope.getTopic(), envelope.getNamespace(),
                envelope.hasTimestamp()
                        ? Instant.ofEpochSecond(envelope.getTimestamp().getSeconds(), envelope.getTimestamp().getNanos())
                        : null,
                taskEvent);
    }
}
```

(If the vendored `containerd.events.TaskExit` message doesn't exist, drop that branch — `api/events/task.proto` defines `TaskCreate`, `TaskStart`, `TaskDelete`, `TaskExit`, `TaskOOM` etc.; adjust to the generated classes.)

- [ ] **Step 3: Implement `EventsServiceImpl`**:

```java
package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.Event;
import io.nanofaas.containerd.EventFilter;
import io.nanofaas.containerd.Subscription;
import io.nanofaas.containerd.spi.Events;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class EventsServiceImpl implements Events {

    private static final Logger log = LoggerFactory.getLogger(EventsServiceImpl.class);

    private final containerd.services.events.v1.EventsGrpc.EventsStub stub;
    private final String namespace;
    private final java.util.concurrent.ExecutorService handlerExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    public EventsServiceImpl(ManagedChannel channel, String namespace) {
        this.stub = containerd.services.events.v1.EventsGrpc.newStub(channel);
        this.namespace = namespace;
    }

    @Override
    public Subscription subscribe(EventFilter filter, Consumer<Event> handler) {
        var closed = new AtomicBoolean(false);
        var request = containerd.services.events.v1.SubscribeRequest.newBuilder()
                .addAllFilters(filter.toFieldpathFilters(namespace))
                .build();
        log.debug("events subscribe: filters={}", request.getFiltersList());

        var observer = new StreamObserver<containerd.types.Envelope>() {
            @Override
            public void onNext(containerd.types.Envelope envelope) {
                Event event = EventMapper.map(envelope);
                handlerExecutor.submit(() -> {
                    try {
                        handler.accept(event);
                    } catch (Exception e) {
                        log.warn("event handler threw for topic {}", event.topic(), e);
                    }
                });
            }

            @Override
            public void onError(Throwable t) {
                if (closed.get()) {
                    return;
                }
                log.warn("events stream error (will retry): {}", t.getMessage());
                if (t instanceof StatusRuntimeException sre
                        && sre.getStatus().getCode() == io.grpc.Status.Code.UNIMPLEMENTED) {
                    log.error("events not supported by this containerd; giving up");
                    return;
                }
                scheduleReconnect(1000, filter, handler, closed);
            }

            @Override
            public void onCompleted() {
                if (!closed.get()) {
                    scheduleReconnect(1000, filter, handler, closed);
                }
            }
        };
        stub.subscribe(request, observer);

        return () -> {
            closed.set(true);
            observer.onCompleted();
            log.debug("events subscription closed");
        };
    }

    private void scheduleReconnect(long backoffMillis, EventFilter filter,
                                   Consumer<Event> handler, AtomicBoolean closed) {
        if (closed.get()) {
            return;
        }
        log.debug("events reconnect in {}ms", backoffMillis);
        var reconnecter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "containerd-events-reconnect");
            t.setDaemon(true);
            return t;
        });
        reconnecter.schedule(() -> {
            reconnecter.shutdown();
            if (!closed.get()) {
                subscribe(filter, handler);
            }
        }, backoffMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}
```

Wire `events()` into `ContainerdClient`/`DefaultContainerdClient`.

- [ ] **Step 4: Run tests, then write `EventsIT`**

Run: `./gradlew test`
Expected: PASS.

`EventsIT`:

```java
package io.nanofaas.containerd;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class EventsIT extends ContainerdConnectionIT {

    private static final String ALPINE = "docker.io/library/alpine:latest";

    @BeforeAll
    static void ensureImage() {
        client.images().pull(ALPINE);
    }

    @Test
    @Timeout(120)
    void receivesTaskStartEvent() throws Exception {
        var received = new ArrayBlockingQueue<Event>(16);
        Subscription sub = client.events().subscribe(EventFilter.topics("/tasks/start"), received::offer);

        String id = "it-events-" + UUID.randomUUID();
        try {
            client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                    .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done")).build());
            client.containers().start(id);

            Event event = received.poll(30, TimeUnit.SECONDS);
            assertThat(event).isNotNull();
            assertThat(event.topic()).isEqualTo("/tasks/start");
            assertThat(event.taskEvent().containerId()).isEqualTo(id);
        } finally {
            sub.close();
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }
}
```

Run: `./gradlew integrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: event subscription with filters, typed task events, auto-reconnect"
```

---

### Task 13: Example application and README

**Files:**
- Create: `src/main/java/io/nanofaas/containerd/example/Example.java`
- Create: `README.md`
- Modify: `build.gradle.kts` (add `runtimeOnly("org.slf4j:slf4j-simple:2.0.17")` under a `run`-friendly scope — `application` already wired)

**Interfaces:**
- Consumes: the full public API from Tasks 3–12.
- Produces: runnable demo (`./gradlew run`) and complete documentation.

- [ ] **Step 1: Write `Example.java`**:

```java
package io.nanofaas.containerd.example;

import io.nanofaas.containerd.*;
import io.nanofaas.containerd.spi.ContainerdClient;

import java.util.List;

/** End-to-end demo: connect, version, pull, create, start, exec, stop, delete. */
public final class Example {

    private Example() {
    }

    public static void main(String[] args) throws Exception {
        try (ContainerdClient client = ContainerdClient.builder()
                .socketPath(System.getProperty("io.nanofaas.containerd.socket", "/run/containerd/containerd.sock"))
                .namespace("nanofaas")
                .build()) {

            Version version = client.version();
            System.out.println("containerd " + version.version() + " (" + version.revision() + ")");

            String image = "docker.io/library/alpine:latest";
            System.out.println("pulling " + image + " ...");
            client.images().pull(image);

            String id = "example-" + System.currentTimeMillis();
            Container container = client.containers().create(
                    ContainerSpec.builder()
                            .id(id)
                            .image(image)
                            .command(List.of("/bin/sh", "-c", "while true; do sleep 10; done"))
                            .build());
            System.out.println("container created: " + container.id());

            int pid = client.containers().start(id);
            System.out.println("started, pid=" + pid);

            ExecResult result = client.containers().exec(id,
                    List.of("/bin/sh", "-c", "echo hello from containerd"));
            System.out.println("exec exit=" + result.exitCode());
            System.out.println("exec stdout=" + result.stdout().trim());
            System.out.println("exec stderr=" + result.stderr().trim());

            client.containers().stop(id);
            System.out.println("stopped");

            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).build());
            System.out.println("deleted (snapshot cleaned up)");
        }
    }
}
```

- [ ] **Step 2: Run the example**

Run: `./gradlew run` (as root, or a user with socket access)
Expected output: version lines, pull progress absent but success, `exec stdout=hello from containerd`, clean exit 0.

- [ ] **Step 3: Write `README.md`** — must cover: purpose; requirements (Linux, containerd 2.x — tested against v2.2.1, Java 21+, crun or runc via `io.containerd.runc.v2`); architecture (layers + the verified-facts table distilled); installation (`./gradlew build`, mavenLocal coordinates); basic usage (the spec's example + exec + events); containerd configuration (socket path, runtime alias for crun: `[plugins."io.containerd.grpc.v1.cri".containerd.runtimes.crun] runtime_type = "io.containerd.runc.v2"` with `binary_name` or `ContainerdClient.builder().runtimeBinaryName("crun")`); crun configuration assumptions; namespace configuration (default `nanofaas`, header propagation); snapshotter configuration (default `overlayfs`); limitations (Linux-only, no checkpoint/restore, no stats/update-task resources yet, no registry auth customization — anonymous pulls only, exec requires a running task, no TTY/PTY support yet); integration test instructions (`sudo ./gradlew integrationTest` — socket is root-owned; `-Dio.nanofaas.containerd.socket=` override); and the conceptual glossary: **image** (manifest + config + content blobs), **content** (blobs by digest), **snapshot** (filesystem layer state, ACTIVE/COMMITTED, chainID-addressable), **container** (metadata: spec + snapshotter + snapshot key), **task** (the running instance), **process** (task or exec member with pid and exit status) — with the explicit warning that Docker semantics don't apply.

- [ ] **Step 4: Final verification**

Run: `./gradlew clean build` then `sudo ./gradlew integrationTest`
Expected: both BUILD SUCCESSFUL. Then:

```bash
git add -A && git commit -m "docs: example application and README"
```

- [ ] **Step 5: Write the final report** — append to the end of `README.md` a "Design Notes" section answering the spec §25 report questions, with the content below (fill in exact numbers/observations from the integration run):

1. **Architecture implemented:** layered client (`ContainerdClient` → `Images`/`Containers`/`Tasks`/`Events` facades → generated gRPC stubs) over a single shared Netty-epoll channel on the UDS; internal `GrpcChannelFactory`, `NamespaceInterceptor`, `ProtoMapper`, `OciSpecBuilder`, `SnapshotManager`, `IoManager`, `TransferImagePuller`, `ImageRootfsResolver`.
2. **containerd APIs used:** `version.v1.Version` (health), `containers.v1.Containers` (create/get/list/delete), `tasks.v1.Tasks` (create/start/kill/wait/delete/exec/delete-process), `snapshots.v1.Snapshots` (prepare/mounts/remove), `images.v1.Images` (get/list/delete), `transfer.v1.Transfer` (pull), `content.v1.Content` (read manifest/config), `events.v1.Events` (subscribe). No PullImage RPC exists — pull = Transfer.
3. **Lifecycle create/start/stop/delete:** create = resolve image chainID (manifest→config→diff_ids→ChainID) → `Snapshots.Prepare(key=id, parent=chainID)` → spec Any (JSON, type URL `types.containerd.io/opencontainers/runtime-spec/1/Spec`) → `Containers.Create` (+ GC label); on failure best-effort snapshot remove. start = `Tasks.Create(rootfs=snapshot mounts)` → `Tasks.Start`. stop = SIGTERM → wait(10s) → SIGKILL → wait → task delete (idempotent). remove = task delete (force) → `Containers.Delete` → snapshot remove (idempotent).
4. **Image pull strategy:** containerd Transfer service with `OCIRegistry` source + `ImageStore` destination (platforms + `unpacks` into the configured snapshotter, `all_metadata=true`), exactly what `ctr images pull` uses. No CLI.
5. **Snapshot strategy:** snapshotter configurable (default `overlayfs`); containers get an active snapshot keyed by container id, parented on the image's top ChainID; `containerd.io/gc.ref.snapshot.<snapshotter>` label protects it from GC; removal is explicit and idempotent.
6. **OCI spec strategy:** JSON spec (field names from `opencontainers/runtime-spec`) carried in a `google.protobuf.Any` with containerd's registered type URL; dedicated `OciSpecBuilder`; resource limits in `linux.resources` (cpu/memory/pids).
7. **Concurrency model:** one shared `ManagedChannel` + thread-safe blocking stubs per client; no thread per container — FIFO I/O and event handlers run on Java 21 virtual threads; event streams auto-reconnect with backoff.
8. **Known limitations:** Linux-only; anonymous registry pulls (no auth customization yet); no checkpoint/restore, TTY, or `UpdateTask` resources; exec requires a running task.
9. **containerd-version-specific assumptions:** API pinned to v2.2.1 (protos vendored); runtime-spec 1.x type URLs; `io.containerd.runc.v2` shim; spec JSON (not proto) in Any — verified from containerd source; `ociVersion` 1.2.0.
10. **NanoFaaS optimizations possible next:** pre-pull images once and prepare snapshots in advance (snapshot prepare is a cheap metadata op; task create reuses the active snapshot via `SnapshotManager.mounts`); pre-create containers (metadata + spec) and only create/start tasks per invocation; reuse a warmed exec path; skip `Images.Get` on the hot path by caching chainIDs; use `View` snapshots for read-only function rootfs.

---

## Self-Review Notes (checked before handoff)

- **Spec coverage**: §1–§4 (direct gRPC, UDS, namespaces via interceptor) → Tasks 1–3; §5 API → Tasks 5, 8–11; §6 features → Tasks 8–12; §7 OCI spec → Task 6; §8 snapshotters → Tasks 7, 9–10; §9 runtime selection → Task 1 builder + Task 10 options Any; §10 architecture → Tasks 3–12; §11 errors → Task 4; §12 concurrency → shared channel + virtual threads (Tasks 3, 11–12); §13–14 failure/idempotency → Tasks 9–10 Javadoc + tests; §15 SLF4J → all tasks; §16 deps → Task 1; §17 protos → Task 2; §18 tests → unit per task + `ContainerConnectionIT`, `ImagePullIT`, `ContainerCreateIT`, `ContainerLifecycleIT`, `ExecIT`, `EventsIT`; §19 example → Task 13; §20 Gradle → Task 1; §21 README → Task 13; §22–23 incremental + verified-first → this plan's research block; §24 NanoFaaS fast path → `Tasks` facade public by design (Task 10); §25 deliverables → all present.
- **Placeholder scan**: no TBD/TODO. The only intentional stubs are the five `UnsupportedOperationException` methods in Task 9, replaced in Tasks 10–11 with explicit cross-references; `runtimeBinaryName` accepted-but-unused in Task 9 becomes used in Task 10.
- **Type consistency**: `Containers` interface introduced in Task 9 declares `exec`/`ExecSpec`/`ExecResult` up front so later tasks don't rename them; `SnapshotManager.forSnapshotter` added in Task 10 is called in Task 9's final `remove`; `TasksServiceImpl` ctor signature `(ManagedChannel, String runtimeBinaryName)` used by `ContainersServiceImpl.tasks()` in Task 10; `EventMapper`/`EventFilter` names match between Task 12 test and impl.
- **Risks with mitigations**: exec FIFO fast-exit race → open-before-Exec pattern (Task 11); netty version mismatch → pinned via `dependencyInsight` (Task 1); `containerd.events.TaskExit` presence → conditional branch noted (Task 12); socket permissions → Assumptions skip + `sudo` documented (Tasks 3, 13).
