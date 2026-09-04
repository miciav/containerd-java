package io.nanofaas.containerd.internal;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;

import java.util.List;
import java.util.Map;

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

    /**
     * Prints a Struct as JSON. Unlike {@code JsonFormat.printer()} (which renders every
     * {@code Value.number_value} double as {@code 1024.0}), integral values are printed as bare
     * integers: containerd unmarshals the OCI spec into Go structs whose numeric fields are
     * {@code uint64}/{@code int64} and reject {@code 1024.0}
     * ({"@code json: cannot unmarshal number 1024.0 into Go struct field ... of type uint64}).
     */
    public static String print(Struct struct) {
        StringBuilder sb = new StringBuilder();
        printStruct(struct, sb);
        return sb.toString();
    }

    private static void printStruct(Struct s, StringBuilder sb) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Value> e : s.getFieldsMap().entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            printJsonString(e.getKey(), sb);
            sb.append(':');
            printValue(e.getValue(), sb);
        }
        sb.append('}');
    }

    private static void printValue(Value v, StringBuilder sb) {
        switch (v.getKindCase()) {
            case NUMBER_VALUE -> {
                double d = v.getNumberValue();
                if (!Double.isNaN(d) && !Double.isInfinite(d) && d == Math.rint(d)
                        && Math.abs(d) < 9007199254740992.0) { // 2^53: exact as long
                    sb.append((long) d);
                } else {
                    sb.append(d);
                }
            }
            case STRING_VALUE -> printJsonString(v.getStringValue(), sb);
            case BOOL_VALUE -> sb.append(v.getBoolValue());
            case STRUCT_VALUE -> printStruct(v.getStructValue(), sb);
            case LIST_VALUE -> {
                List<Value> items = v.getListValue().getValuesList();
                sb.append('[');
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    printValue(items.get(i), sb);
                }
                sb.append(']');
            }
            case NULL_VALUE, KIND_NOT_SET -> sb.append("null");
        }
    }

    private static void printJsonString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
