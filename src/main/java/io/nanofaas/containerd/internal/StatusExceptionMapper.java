package io.nanofaas.containerd.internal;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.nanofaas.containerd.*;

/** Maps gRPC status codes to library exceptions, preserving the original as cause. */
public final class StatusExceptionMapper {

    public enum ResourceKind { CONTAINER, IMAGE, TASK, SNAPSHOT, CONTENT, GENERAL }

    private StatusExceptionMapper() {
    }

    public static ContainerdException map(StatusRuntimeException e, ResourceKind kind) {
        String message = "containerd " + kind.name().toLowerCase() + " operation failed: " + e.getStatus().getCode();
        if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
            return switch (kind) {
                case CONTAINER -> new ContainerNotFoundException(message, e);
                case IMAGE -> new ImageNotFoundException(message, e);
                case TASK -> new TaskNotFoundException(message, e);
                default -> new ContainerdException(message, e);
            };
        }
        if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS && kind == ResourceKind.CONTAINER) {
            return new ContainerAlreadyExistsException(message, e);
        }
        return new ContainerdException(message, e);
    }
}
