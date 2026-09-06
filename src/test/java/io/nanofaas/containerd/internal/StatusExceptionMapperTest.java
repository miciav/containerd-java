package io.nanofaas.containerd.internal;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.nanofaas.containerd.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatusExceptionMapperTest {

    private static StatusRuntimeException sre(Status.Code code) {
        return Status.fromCode(code).asRuntimeException();
    }

    @Test
    void mapsNotFoundPerResourceKind() {
        assertThat(StatusExceptionMapper.map(sre(Status.Code.NOT_FOUND), StatusExceptionMapper.ResourceKind.CONTAINER))
                .isInstanceOf(ContainerNotFoundException.class);
        assertThat(StatusExceptionMapper.map(sre(Status.Code.NOT_FOUND), StatusExceptionMapper.ResourceKind.IMAGE))
                .isInstanceOf(ImageNotFoundException.class);
        assertThat(StatusExceptionMapper.map(sre(Status.Code.NOT_FOUND), StatusExceptionMapper.ResourceKind.TASK))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void mapsAlreadyExistsToContainerAlreadyExists() {
        assertThat(StatusExceptionMapper.map(sre(Status.Code.ALREADY_EXISTS), StatusExceptionMapper.ResourceKind.CONTAINER))
                .isInstanceOf(ContainerAlreadyExistsException.class);
    }

    @Test
    void preservesOriginalStatusAsCause() {
        var original = sre(Status.Code.NOT_FOUND);
        assertThatThrownBy(() -> {
            throw StatusExceptionMapper.map(original, StatusExceptionMapper.ResourceKind.CONTAINER);
        }).isInstanceOf(ContainerNotFoundException.class)
                .hasCause(original);
    }

    @Test
    void messageCarriesContainerdsOwnDescription() {
        // The status description is where containerd explains itself; a bare code says nothing
        // the exception type does not already.
        var e = Status.NOT_FOUND.withDescription("container does not exist").asRuntimeException();
        assertThat(StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.CONTAINER))
                .hasMessageContaining("container does not exist")
                .hasMessageContaining("NOT_FOUND");
    }

    @Test
    void messageOmitsTheSeparatorWhenThereIsNoDescription() {
        assertThat(StatusExceptionMapper.map(sre(Status.Code.INTERNAL), StatusExceptionMapper.ResourceKind.TASK))
                .hasMessage("containerd task operation failed: INTERNAL");
    }

    @Test
    void unmappedCodesFallBackToContainerdException() {
        assertThat(StatusExceptionMapper.map(sre(Status.Code.INTERNAL), StatusExceptionMapper.ResourceKind.GENERAL))
                .isInstanceOf(ContainerdException.class);
    }
}
