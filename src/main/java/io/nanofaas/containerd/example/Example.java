package io.nanofaas.containerd.example;

import io.nanofaas.containerd.*;
import io.nanofaas.containerd.spi.ContainerdClient;

import java.util.List;

/** End-to-end demo: connect, version, pull, create, start, exec, stop, delete. */
public final class Example {

    private Example() {
    }

    // System.out on purpose: this is the runnable example, and its output is what running it is
    // for. A reader trying the library would write exactly this. The library itself uses SLF4J.
    @SuppressWarnings("java:S106")
    public static void main(String[] args) {
        try (ContainerdClient client = ContainerdClient.builder()
                .socketPath(System.getProperty("io.nanofaas.containerd.socket", "/run/containerd/containerd.sock"))
                .namespace("nanofaas")
                .build()) {

            Version version = client.version();
            System.out.println("containerd " + version.version() + " (" + version.revision() + ")");

            String image = "docker.io/library/alpine:latest";
            System.out.println("pulling " + image + " ...");
            client.images().pull(image);

            String id = "example-" + System.currentTimeMillis();
            Container container = client.containers().create(
                    ContainerSpec.builder()
                            .id(id)
                            .image(image)
                            .command(List.of("/bin/sh", "-c", "while true; do sleep 10; done"))
                            .build());
            System.out.println("container created: " + container.id());

            int pid = client.containers().start(id);
            System.out.println("started, pid=" + pid);

            ExecResult result = client.containers().exec(id,
                    List.of("/bin/sh", "-c", "echo hello from containerd"));
            System.out.println("exec exit=" + result.exitCode());
            System.out.println("exec stdout=" + result.stdout().trim());
            System.out.println("exec stderr=" + result.stderr().trim());

            client.containers().stop(id);
            System.out.println("stopped");

            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).build());
            System.out.println("deleted (snapshot cleaned up)");
        }
    }
}
