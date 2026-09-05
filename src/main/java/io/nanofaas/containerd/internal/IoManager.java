package io.nanofaas.containerd.internal;

import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Creates and manages the FIFOs containerd uses for task/exec IO.
 * mkfifo is done via jnr-posix (Java has no built-in); reading/writing uses plain java.io
 * on virtual threads so blocked opens never consume OS threads.
 */
public final class IoManager {

    private static final Logger log = LoggerFactory.getLogger(IoManager.class);
    private static final POSIX POSIX = POSIXFactory.getNativePOSIX();

    public record FifoSet(Path dir, Path stdin, Path stdout, Path stderr) {
    }

    private IoManager() {
    }

    public static FifoSet createFifoSet(String prefix) {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "containerd-java-fifos",
                prefix + "-" + UUID.randomUUID());
        Path stdin = dir.resolve("stdin");
        Path stdout = dir.resolve("stdout");
        Path stderr = dir.resolve("stderr");
        try {
            Files.createDirectories(dir);
            mkfifo(stdin);
            mkfifo(stdout);
            mkfifo(stderr);
        } catch (IOException e) {
            cleanupQuietly(dir);
            throw new IllegalStateException("failed to create FIFO set in " + dir, e);
        }
        log.debug("created FIFO set at {}", dir);
        return new FifoSet(dir, stdin, stdout, stderr);
    }

    /** Opens the FIFO for reading and consumes it to EOF. Blocks until a writer opens. */
    public static String readFifo(Path fifo) {
        try (var in = Files.newInputStream(fifo)) {
            // A plain read loop, not readAllBytes(): the stream is a ChannelInputStream whose
            // readAllBytes() lseeks to size the read, and pipes do not support seeking
            // ("Illegal seek"). read() blocks until data is available or all writers close.
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return buf.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read FIFO " + fifo, e);
        }
    }

    public static void writeFifo(Path fifo, byte[] data) {
        try (var out = Files.newOutputStream(fifo)) {
            out.write(data);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write FIFO " + fifo, e);
        }
    }

    public static void cleanup(FifoSet fifos) {
        cleanupQuietly(fifos.dir());
    }

    private static void mkfifo(Path path) {
        int rc = POSIX.mkfifo(path.toString(), 0600);
        if (rc != 0) {
            throw new IllegalStateException("mkfifo failed for " + path + ": errno " + POSIX.errno());
        }
    }

    private static void cleanupQuietly(Path dir) {
        try {
            Files.list(dir).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
