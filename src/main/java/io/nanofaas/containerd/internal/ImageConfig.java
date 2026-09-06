package io.nanofaas.containerd.internal;

import java.util.List;

/**
 * The parts of an OCI image configuration that shape how its container runs.
 *
 * <p>Everything here is a default the caller's {@link io.nanofaas.containerd.ContainerSpec} may
 * override. An image that sets none of them yields {@link #EMPTY}.
 *
 * @param entrypoint the image's Entrypoint, empty when it declares none
 * @param cmd the image's Cmd, empty when it declares none
 * @param env the image's Env as {@code KEY=VALUE} entries
 * @param user the image's User, {@code null} when unset. May be a name rather than a uid
 * @param workingDir the image's WorkingDir, {@code null} when unset
 */
record ImageConfig(List<String> entrypoint, List<String> cmd, List<String> env,
                   String user, String workingDir) {

    static final ImageConfig EMPTY = new ImageConfig(List.of(), List.of(), List.of(), null, null);

    ImageConfig {
        entrypoint = List.copyOf(entrypoint);
        cmd = List.copyOf(cmd);
        env = List.copyOf(env);
    }

    /**
     * The argv this image runs by default: its entrypoint followed by its cmd, which is how the
     * OCI image spec defines the two.
     *
     * @return the default argv, empty if the image declares neither
     */
    List<String> defaultArgs() {
        if (entrypoint.isEmpty()) {
            return cmd;
        }
        if (cmd.isEmpty()) {
            return entrypoint;
        }
        List<String> args = new java.util.ArrayList<>(entrypoint);
        args.addAll(cmd);
        return List.copyOf(args);
    }
}
