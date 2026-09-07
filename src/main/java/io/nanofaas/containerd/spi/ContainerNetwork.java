package io.nanofaas.containerd.spi;

/**
 * Attaches and detaches a container's network, called by the client at the two moments when it can
 * be done at all.
 *
 * <p>This interface is the whole of what the core knows about networking: it deliberately names no
 * CNI type, so an implementation can pull in whatever it needs without that reaching consumers who
 * do not want networking. {@code containerd-java-cni} provides one built on CNI plugins.
 *
 * <p>The timing is not the caller's to get right, which is why it lives here. A container's network
 * namespace exists only while its task does — it is {@code /proc/<pid>/ns/net} — so attaching is
 * possible only after the task starts, and detaching only before it is torn down. Getting that
 * order wrong leaks addresses and virtual interfaces on the host that nothing will ever reclaim.
 */
public interface ContainerNetwork {

    /**
     * Attaches the container to its network, once its task is running.
     *
     * <p>Throwing means the container did not get the network it asked for. The client then tears
     * the task down rather than leaving a container running without one, and calls
     * {@link #detach} first, because a failure part-way through may already have allocated an
     * address or created an interface.
     *
     * @param containerId the container being attached
     * @param network the network name from {@link io.nanofaas.containerd.ContainerSpec}
     * @param pid the task's init process; its network namespace is {@code /proc/<pid>/ns/net}
     * @return what the container got — the client writes the DNS into the container's
     *         {@code /etc/resolv.conf} and hands the rest back to the caller
     */
    io.nanofaas.containerd.NetworkAttachment attach(String containerId, String network, int pid);

    /**
     * Detaches the container from its network, before its task goes away.
     *
     * <p>Must tolerate being called when there is nothing to undo, and when the namespace has
     * already gone: a task that exited on its own takes its namespace with it, and the address it
     * held still has to be released. Implementations should not throw for either case — by the
     * time this runs the caller is usually already tearing things down, and a failure here would
     * replace whatever they were doing.
     *
     * @param containerId the container being detached
     * @param network the network name it was attached to
     * @param pid the task's init process, or {@code -1} if it is already gone
     */
    void detach(String containerId, String network, int pid);
}
