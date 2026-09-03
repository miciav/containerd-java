package io.nanofaas.containerd.internal;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;

/** JSON helpers built on protobuf Struct/JsonFormat (no extra JSON dependency). */
public final class JsonSupport {

    private JsonSupport() {
    }

    public static Struct parse(String json) {
        try {
            Struct.Builder b = Struct.newBuilder();
            JsonFormat.parser().merge(json, b);
            return b.build();
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("invalid JSON: " + json, e);
        }
    }

    public static String print(Struct struct) {
        try {
            return JsonFormat.printer().print(struct);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("cannot serialize Struct to JSON", e);
        }
    }
}
