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
