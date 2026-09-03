package io.nanofaas.containerd;

/** Lifecycle state of a container's task, mirroring containerd's task status enum. */
public enum ContainerState {
    CREATED, RUNNING, STOPPED, PAUSED, PAUSING, STARTING, UNKNOWN
}
