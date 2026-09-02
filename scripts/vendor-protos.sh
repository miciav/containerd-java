#!/usr/bin/env bash
#
# Vendors the containerd API protos used by this project and applies the
# Java codegen layout option.
#
# (a) Downloads each of the 23 api protos listed below VERBATIM from the
#     containerd git tag pinned by `containerdApiVersion` in build.gradle.kts
#     (v2.2.1) into the mirrored path under src/main/proto, preserving the
#     upstream github.com/containerd/containerd/api/... directory structure so
#     proto import paths resolve exactly as upstream.
# (b) Appends `option java_multiple_files = true;` after the
#     `syntax = "proto3";` line of every vendored file. The option changes no
#     message/RPC definition (pure Java codegen layout) and makes protoc emit
#     flat classes named after the proto messages (e.g. containerd.types.Mount,
#     containerd.services.containers.v1.Container, containerd.events.TaskStart).
#     Idempotent: skipped when the option line is already present.
#
# Ruling R7-B: the transformation is committed (this script + its effect) so
# that the vendored-tree layout step is reproducible and documented.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

TAG="$(grep -oP 'containerdApiVersion = "\K[^"]+' build.gradle.kts)"
if [ -z "$TAG" ]; then
    echo "ERROR: could not read containerdApiVersion from build.gradle.kts" >&2
    exit 1
fi
echo "Vendoring api protos from containerd tag $TAG (containerdApiVersion pin)"

# api-relative paths of every vendored file (mirrors the task-2 brief list)
FILES="
services/version/v1/version.proto
services/containers/v1/containers.proto
services/tasks/v1/tasks.proto
services/snapshots/v1/snapshots.proto
services/images/v1/images.proto
services/events/v1/events.proto
services/transfer/v1/transfer.proto
services/content/v1/content.proto
types/mount.proto
types/platform.proto
types/descriptor.proto
types/event.proto
types/fieldpath.proto
types/task/task.proto
types/metrics.proto
types/transfer/imagestore.proto
types/transfer/registry.proto
types/transfer/progress.proto
types/runc/options/oci.proto
types/runtimeoptions/v1/api.proto
events/task.proto
events/container.proto
events/image.proto
"

BASE="src/main/proto/github.com/containerd/containerd/api"
N=0
for rel in $FILES; do
    dest="$BASE/$rel"
    mkdir -p "$(dirname "$dest")"
    if ! curl -fsSL "https://raw.githubusercontent.com/containerd/containerd/$TAG/api/$rel" -o "$dest"; then
        echo "ERROR: failed to download $rel" >&2
        exit 1
    fi
    N=$((N + 1))
done
echo "downloaded=$N"

# (b) append java_multiple_files after the syntax line; idempotent
M=0
for dest in $(find "$BASE" -name '*.proto' | sort); do
    if grep -q 'option java_multiple_files = true;' "$dest"; then
        echo "skip (already patched): $dest"
        continue
    fi
    sed -i '/^syntax = "proto3";$/a option java_multiple_files = true;' "$dest"
    M=$((M + 1))
done
echo "patched=$M"

# (b2) events/image.proto is upstream-declared in the same proto package as
# services/images/v1/images.proto (containerd.services.images.v1) and uses the
# file-level option (containerd.types.fieldpath_all), which forces protoc to emit
# a file-level outer class named after the base name ("Image"). Under
# java_multiple_files that collides with the Image MESSAGE of images.proto
# ("Tried to write the same file twice"). java_outer_classname renames only that
# file-level holder class (also pure Java codegen layout; message classes
# ImageCreate/ImageUpdate/ImageDelete keep their flat names). Idempotent.
IMAGE_EVENTS="$BASE/events/image.proto"
if grep -q 'option java_outer_classname = "ImageEvents";' "$IMAGE_EVENTS"; then
    echo "skip (already patched): $IMAGE_EVENTS (java_outer_classname)"
else
    sed -i '/option java_multiple_files = true;/a option java_outer_classname = "ImageEvents";' "$IMAGE_EVENTS"
    echo "patched: $IMAGE_EVENTS (java_outer_classname)"
fi
