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

    /** Image operations: pull, get, list, remove. */
    Images images();

    /** Container lifecycle: create, inspect, list, remove, start, stop, kill, wait, exec. */
    Containers containers();

    /** Low-level task operations (NanoFaaS fast path). */
    Tasks tasks();

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
