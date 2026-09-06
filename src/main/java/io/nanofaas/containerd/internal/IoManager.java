package io.nanofaas.containerd.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Creates and manages the FIFOs containerd uses for task/exec IO.
 *
 * <p>Java has no mkfifo, so it is called through the Foreign Function and Memory API. That choice
 * is not stylistic: the JNR binding this replaced generates its native stubs as bytecode at
 * runtime, which a GraalVM native image cannot do — the image built, then died on the first exec
 * with "Class defined at runtime". FFM resolves the symbol without generating anything, and is
 * why this library needs Java 22.
 *
 * <p>Reading and writing use plain java.io on virtual threads, so a blocked open never costs an
 * OS thread.
 */
public final class IoManager {

    private static final Logger log = LoggerFactory.getLogger(IoManager.class);

    /** Owner read/write only: the FIFOs carry a container's stdio and belong to nobody else. */
    private static final int FIFO_MODE = 0600;

    private static final Linker LINKER = Linker.nativeLinker();
    /** {@code int mkfifo(const char *path, mode_t mode)}, with errno captured alongside. */
    private static final MethodHandle MKFIFO = LINKER.downcallHandle(
            LINKER.defaultLookup().find("mkfifo").orElseThrow(
                    () -> new IllegalStateException("mkfifo not found in the C library")),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            Linker.Option.captureCallState("errno"));
    private static final MemoryLayout CAPTURED_STATE = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO =
            CAPTURED_STATE.varHandle(MemoryLayout.PathElement.groupElement("errno"));

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
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capturedState = arena.allocate(CAPTURED_STATE);
            MemorySegment pathName = arena.allocateFrom(path.toString());
            int rc = (int) MKFIFO.invokeExact(capturedState, pathName, FIFO_MODE);
            if (rc != 0) {
                int errno = (int) ERRNO.get(capturedState, 0L);
                throw new IllegalStateException("mkfifo failed for " + path + ": errno " + errno);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            // invokeExact is declared to throw Throwable; nothing here throws a checked exception.
            throw new IllegalStateException("mkfifo failed for " + path, e);
        }
    }

    private static void cleanupQuietly(Path dir) {
        try (var entries = Files.list(dir)) { // the stream holds a directory handle
            entries.forEach(p -> {
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
