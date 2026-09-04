package io.nanofaas.containerd.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class IoManagerTest {

    @Test
    void fifoRoundTrip() throws Exception {
        IoManager io = new IoManager();
        var fifos = io.createFifoSet("test");
        try {
            assertThat(Files.exists(fifos.stdout())).isTrue();
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var writer = executor.submit(() -> IoManager.writeFifo(fifos.stdout(), "hello fifo\n".getBytes()));
            String read = IoManager.readFifo(fifos.stdout());
            writer.get();
            assertThat(read).isEqualTo("hello fifo\n");
        } finally {
            io.cleanup(fifos);
            assertThat(Files.exists(fifos.dir())).isFalse();
        }
    }
}
