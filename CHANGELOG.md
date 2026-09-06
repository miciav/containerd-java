# Changelog

All notable changes to this project are documented here. This project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the major version is 0, minor
versions may carry breaking changes.

## [0.2.0] — 2026-09-06

First release with a licence and published javadoc. The bulk of this release is a full review of
the library: 14 defects fixed and 14 code smells cleaned up, with the test suite grown from 50 to
78 unit tests plus 26 integration tests that run against a real containerd in CI.

### Breaking

- `TaskInfo` gained an `exitedAt` component. `inspect()` used to discard the exit timestamp that
  `Tasks.wait` already reported.
- `ProtoMapper.mapStatus` takes the generated `containerd.v1.types.Status` enum instead of an
  `int` (internal API).
- `ContainerdClientBuilder` is no longer public; reach it through `ContainerdClient.builder()`.
- Container ids are validated against containerd's real rule. Ids the previous, looser pattern
  accepted — a trailing or leading separator (`fn-`, `-fn`), doubled separators (`a..b`) — are
  now rejected up front instead of failing server-side a round trip later.
- `exec` no longer sets `no_new_privs`. It now matches the container's init process, as docker
  and `ctr` do by default, so setuid binaries behave the same way whichever way a command is
  started. Callers relying on execs being more restricted than the entrypoint should set their
  own policy.

### Fixed

- `ContentStoreReader.read` looped forever when the Read stream completed before delivering the
  size Info advertised, re-issuing the same RPC at full CPU. Reached by every `containers.create`.
- Closing the client raised `RejectedExecutionException` on a gRPC callback thread: the event
  executors were shut down while streams were still live.
- `Subscription.close()` only set a flag and never cancelled the gRPC call, so containerd kept
  streaming into a subscription nobody read for the life of the channel.
- User labels could overwrite the `containerd.io/gc.ref.snapshot` label, leaving containerd free
  to collect the snapshot out from under a live container.
- `stop()` could block forever: after SIGKILL it waited with no deadline, and SIGKILL does not
  reach a process parked in uninterruptible sleep. It now reports the failure instead.
- `stop()` reported the wrong cause: the task delete ran in a `finally`, and deleting a live task
  fails, so that failure replaced the exception explaining why the stop failed.
- `exec` left a process reading stdin blocked forever when the caller supplied none; stdin is now
  always opened and closed so the process sees EOF.
- Six RPCs bypassed the exception mapper and threw raw `StatusRuntimeException` at callers:
  `Tasks.start`/`inspect`/`list`, `Containers.list`, `Images.list`, `SnapshotManager.mounts`.
- Task lookup used `exists()` followed by `inspect()`, two round trips with a race in the gap. A
  single-round-trip `find()` replaces both.
- Exception messages dropped containerd's own explanation, leaving only a bare status code.
- OCI manifests were decoded with the platform default charset instead of UTF-8.
- `EventFilter.topics()` with no argument does not compile — it resolves against the instance
  getter of the same name — leaving the documented "every topic" filter unreachable. Added
  `EventFilter.all()`.
- A directory stream was left unclosed in `IoManager`, and `close()` did not let in-flight exec
  IO drain before the channel went away.

### Changed

- Stop grace period is configurable per client with `.stopTimeout(Duration)`; it was a hard-coded
  10 seconds.
- `Signal` covers 17 signals and gained `fromNumber`.
- `Platform` normalizes the architecture the same way as the os, so `AMD64` is recognised.
- Builders reject nulls where they are given rather than in a later `List.copyOf`.
- `Identifiers` is a public class; the model no longer reaches into the `internal` package.

### Added

- Apache 2.0 `LICENSE`, and a `NOTICE` attributing the containerd protobuf definitions vendored
  under `src/main/proto`.
- Published `sources` and `javadoc` jars. Javadoc covers the public API and is checked strictly:
  an undocumented public member, a missing `@param`/`@return`, a broken link or malformed HTML
  fails the build.
- GitHub Actions CI: build plus unit tests, and integration tests against containerd 2.2.1
  installed in the runner. The integration job fails if any test skipped, since these tests skip
  themselves when containerd is unreachable.

### Security

- The checked-in `gradle-wrapper.jar` was Gradle 4.4.1, paired by hand with a properties file
  declaring 9.7.1, and matched no published checksum. Replaced with the real 9.7.1 wrapper, and
  `distributionSha256Sum` — previously absent — now pins the distribution the wrapper downloads.

## [0.1.0] — 2026-09-04

Initial implementation: images, containers, tasks, exec and events over containerd's native gRPC
API.
