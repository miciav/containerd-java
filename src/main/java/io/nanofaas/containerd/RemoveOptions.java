package io.nanofaas.containerd;

/** Options for container removal. */
public final class RemoveOptions {

    private final boolean removeSnapshot;
    private final boolean force;

    private RemoveOptions(Builder b) {
        this.removeSnapshot = b.removeSnapshot;
        this.force = b.force;
    }

    /** {@return whether the container's snapshot is removed too; false by default} */
    public boolean removeSnapshot() { return removeSnapshot; }

    /** {@return whether a running container is stopped first; false by default, which throws instead} */
    public boolean force() { return force; }

    /** {@return a builder for new remove options} */
    public static Builder builder() {
        return new Builder();
    }

    /** Collects the fields of a {@link RemoveOptions}. */
    public static final class Builder {

        /** Creates an empty builder; prefer {@link RemoveOptions#builder()}. */
        public Builder() {
        }
        private boolean removeSnapshot;
        private boolean force;

        /**
         * Sets whether the snapshot is removed with the container.
         *
         * @param removeSnapshot true to delete the root filesystem snapshot too
         * @return this builder
         */
        public Builder removeSnapshot(boolean removeSnapshot) { this.removeSnapshot = removeSnapshot; return this; }
        /**
         * Sets whether a running container is stopped before removal.
         *
         * @param force true to stop a running task instead of throwing
         * @return this builder
         */
        public Builder force(boolean force) { this.force = force; return this; }

        /** {@return the remove options} */
        public RemoveOptions build() {
            return new RemoveOptions(this);
        }
    }
}
