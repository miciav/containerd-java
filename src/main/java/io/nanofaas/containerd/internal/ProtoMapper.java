package io.nanofaas.containerd.internal;

import io.nanofaas.containerd.*;

import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

/** Conversions between containerd protobuf messages and the public model. */
public final class ProtoMapper {

    // containerd identifier rules: alphanumerics plus . _ - , max 76 chars
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,75}");

    private ProtoMapper() {
    }

    public static String requireValidId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid containerd identifier: " + id);
        }
        return id;
    }

    public static Container map(containerd.services.containers.v1.Container c) {
        return new Container(
                c.getId(),
                c.getImage(),
                c.getSnapshotter(),
                c.getSnapshotKey(),
                c.hasCreatedAt() ? Instant.ofEpochSecond(c.getCreatedAt().getSeconds(), c.getCreatedAt().getNanos()) : Instant.EPOCH,
                Map.copyOf(c.getLabelsMap()));
    }

    public static Image map(containerd.services.images.v1.Image i) {
        return new Image(
                i.getName(),
                i.getTarget().getDigest(),
                i.getTarget().getSize(),
                i.hasCreatedAt() ? Instant.ofEpochSecond(i.getCreatedAt().getSeconds(), i.getCreatedAt().getNanos()) : Instant.EPOCH,
                Map.copyOf(i.getLabelsMap()));
    }

    public static ContainerState mapStatus(int containerdStatusNumber) {
        return switch (containerdStatusNumber) {
            case 1 -> ContainerState.CREATED;
            case 2 -> ContainerState.RUNNING;
            case 3 -> ContainerState.STOPPED;
            case 4 -> ContainerState.PAUSED;
            case 5 -> ContainerState.PAUSING;
            case 6 -> ContainerState.STARTING; // vendored v2.2.1 enum stops at PAUSING(5); kept for forward compatibility
            default -> ContainerState.UNKNOWN;
        };
    }

    public static containerd.types.Platform toProto(Platform platform) {
        return containerd.types.Platform.newBuilder()
                .setOs(platform.os())
                .setArchitecture(platform.architecture())
                .build();
    }
}
