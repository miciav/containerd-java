package io.nanofaas.containerd;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Desired state of an exec in a running container. Build with {@link #builder()}.
 *
 * @param command argv of the process to run; defaults to {@code /bin/sh} when empty
 * @param environment environment variables added on top of a default {@code PATH}
 * @param workingDir working directory, or {@code null} for {@code /}
 * @param stdin data to write to the process's stdin; {@code null} closes stdin immediately
 */
public record ExecSpec(List<String> command, Map<String, String> environment, String workingDir, String stdin) {

    /** Defensively copies the collections and rejects null ones. */
    public ExecSpec {
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }

    /** {@return a builder for a new exec spec} */
    public static Builder builder() {
        return new Builder();
    }

    /** Collects the fields of an {@link ExecSpec}. */
    public static final class Builder {

        /** Creates an empty builder; prefer {@link ExecSpec#builder()}. */
        public Builder() {
            // every field has its default in its declaration; nothing to do here
        }
        private List<String> command = List.of();
        private Map<String, String> environment = Map.of();
        private String workingDir;
        private String stdin;

        /**
         * Sets the argv of the process to run.
         *
         * @param command argv; {@code /bin/sh} is used when empty
         * @return this builder
         */
        public Builder command(List<String> command) { this.command = Objects.requireNonNull(command, "command"); return this; }
        /**
         * Sets environment variables for the process.
         *
         * @param environment variables added on top of a default {@code PATH}
         * @return this builder
         */
        public Builder environment(Map<String, String> environment) { this.environment = Objects.requireNonNull(environment, "environment"); return this; }
        /**
         * Sets the working directory.
         *
         * @param workingDir directory inside the container, or {@code null} for {@code /}
         * @return this builder
         */
        public Builder workingDir(String workingDir) { this.workingDir = workingDir; return this; }
        /**
         * Sets data to write to the process's stdin.
         *
         * @param stdin data to send; {@code null} closes stdin immediately, so a process
         *        reading it sees EOF rather than blocking
         * @return this builder
         */
        public Builder stdin(String stdin) { this.stdin = stdin; return this; }

        /** {@return the exec spec} */
        public ExecSpec build() {
            return new ExecSpec(command, environment, workingDir, stdin);
        }
    }
}
