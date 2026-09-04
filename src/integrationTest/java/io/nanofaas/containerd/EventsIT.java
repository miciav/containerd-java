package io.nanofaas.containerd;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class EventsIT extends ContainerdConnectionIT {

    private static final String ALPINE = "docker.io/library/alpine:latest";

    @BeforeAll
    static void ensureImage() {
        client.images().pull(ALPINE);
    }

    @Test
    @Timeout(120)
    void receivesTaskStartEvent() throws Exception {
        var received = new ArrayBlockingQueue<Event>(16);
        Subscription sub = client.events().subscribe(EventFilter.topics("/tasks/start"), received::offer);

        String id = "it-events-" + UUID.randomUUID();
        try {
            client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                    .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done")).build());
            client.containers().start(id);

            Event event = received.poll(30, TimeUnit.SECONDS);
            assertThat(event).isNotNull();
            assertThat(event.topic()).isEqualTo("/tasks/start");
            assertThat(event.taskEvent().containerId()).isEqualTo(id);
        } finally {
            sub.close();
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }
}
