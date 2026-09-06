package io.nanofaas.containerd;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Desired state of an exec in a running container. Build with {@link #builder()}. */
public record ExecSpec(List<String> command, Map<String, String> environment, String workingDir, String stdin) {

    public ExecSpec {
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<String> command = List.of();
        private Map<String, String> environment = Map.of();
        private String workingDir;
        private String stdin;

        public Builder command(List<String> command) { this.command = Objects.requireNonNull(command, "command"); return this; }
        public Builder environment(Map<String, String> environment) { this.environment = Objects.requireNonNull(environment, "environment"); return this; }
        public Builder workingDir(String workingDir) { this.workingDir = workingDir; return this; }
        public Builder stdin(String stdin) { this.stdin = stdin; return this; }

        public ExecSpec build() {
            return new ExecSpec(command, environment, workingDir, stdin);
        }
    }
}
