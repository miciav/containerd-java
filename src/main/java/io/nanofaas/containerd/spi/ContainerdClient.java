package io.nanofaas.containerd.spi;

import io.nanofaas.containerd.Version;

/**
 * A client for the containerd gRPC API.
 *
 * <p>Thread-safe. Owns a single shared gRPC channel; close it when done. All service facades
 * returned by this client operate on the namespace configured at build time.
 */
public interface ContainerdClient extends AutoCloseable {

    /** {@return containerd's version and revision; doubles as a health check} */
    Version version();

    /** {@return the image operations facade: pull, get, list, remove} */
    Images images();

    /** {@return the container lifecycle facade: create, inspect, list, remove, start, stop, kill, wait, exec} */
    Containers containers();

    /** {@return the low-level task facade, the NanoFaaS fast path} */
    Tasks tasks();

    /** {@return the event stream facade} */
    Events events();

    /** {@return the containerd namespace every call through this client is scoped to} */
    String namespace();

    /** {@return the snapshotter used for container root filesystems} */
    String snapshotter();

    /** {@return the runtime identifier passed to tasks, e.g. {@code io.containerd.runc.v2}} */
    String runtimeName();

    /**
     * Releases the gRPC channel and every executor this client owns. Open event subscriptions
     * are cancelled. The client cannot be used afterwards.
     */
    @Override
    void close();

    /** {@return a builder for a new client} */
    static Builder builder() {
        return new ContainerdClientBuilder();
    }

    /** Configures and creates a {@link ContainerdClient}. */
    interface Builder {

        /**
         * Sets the Unix domain socket to connect to.
         *
         * @param socketPath socket path; defaults to {@code /run/containerd/containerd.sock}
         * @return this builder
         */
        Builder socketPath(String socketPath);

        /**
         * Sets the containerd namespace every call is scoped to.
         *
         * @param namespace namespace name; defaults to {@code nanofaas}
         * @return this builder
         */
        Builder namespace(String namespace);

        /**
         * Sets the snapshotter for container root filesystems.
         *
         * @param snapshotter snapshotter name; defaults to {@code overlayfs}
         * @return this builder
         */
        Builder snapshotter(String snapshotter);

        /**
         * Sets the runtime identifier passed to tasks.
         *
         * @param runtimeName runtime id; defaults to {@code io.containerd.runc.v2}
         * @return this builder
         */
        Builder runtimeName(String runtimeName);

        /**
         * When set (e.g. {@code crun}), the runc-v2 shim is told to exec this OCI runtime binary
         * instead of its default. Requires a shim that supports the {@code binary_name} option.
         *
         * @param runtimeBinaryName OCI runtime binary, or {@code null} for the shim's default
         * @return this builder
         */
        Builder runtimeBinaryName(String runtimeBinaryName);

        /**
         * How long {@link Containers#stop} waits after SIGTERM before sending SIGKILL.
         * Default 10 seconds.
         *
         * @param stopTimeout grace period; must be positive
         * @return this builder
         */
        Builder stopTimeout(java.time.Duration stopTimeout);

        /** {@return a client connected to the configured socket} */
        ContainerdClient build();
    }
}
