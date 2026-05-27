package com.movtery.zalithlauncher.zerolauncher;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Static helpers for decoding the agent's binary response format.
 *
 * All strings are length-prefixed UTF-8: [4-byte int length][UTF-8 bytes].
 * All integers are big-endian (Java default).
 */
public final class BinaryProtocol {

    private BinaryProtocol() {}

    /** Read a length-prefixed UTF-8 string from the buffer. */
    public static String readString(ByteBuffer buf) {
        int len = buf.getInt();
        if (len < 0 || len > buf.remaining()) throw new IllegalArgumentException("bad string len: " + len);
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Encode a string as length-prefixed UTF-8 bytes (for outgoing requests). */
    public static byte[] encodeString(String s) {
        byte[] strBytes = s.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + strBytes.length);
        buf.putInt(strBytes.length);
        buf.put(strBytes);
        return buf.array();
    }

    /** Read a list of strings: [4-byte count][...strings]. */
    public static List<String> readStringList(ByteBuffer buf) {
        int count = buf.getInt();
        List<String> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) list.add(readString(buf));
        return list;
    }

    // ── ClassDetail ───────────────────────────────────────────────────────────

    public static final class MethodInfo {
        public final String name, descriptor;
        public final int    modifiers;
        MethodInfo(String name, String descriptor, int modifiers) {
            this.name = name; this.descriptor = descriptor; this.modifiers = modifiers;
        }
    }

    public static final class FieldInfo {
        public final String name, descriptor;
        public final int    modifiers;
        FieldInfo(String name, String descriptor, int modifiers) {
            this.name = name; this.descriptor = descriptor; this.modifiers = modifiers;
        }
    }

    public static final class AnnotationInfo {
        public final String       name;
        public final List<String> keys, values;
        AnnotationInfo(String name, List<String> keys, List<String> values) {
            this.name = name; this.keys = keys; this.values = values;
        }
    }

    public static final class ClassDetail {
        public final String             className, superClass;
        public final List<String>       interfaces;
        public final List<MethodInfo>   methods;
        public final List<FieldInfo>    fields;
        public final List<AnnotationInfo> annotations;

        ClassDetail(String className, String superClass, List<String> interfaces,
                    List<MethodInfo> methods, List<FieldInfo> fields,
                    List<AnnotationInfo> annotations) {
            this.className   = className;
            this.superClass  = superClass;
            this.interfaces  = interfaces;
            this.methods     = methods;
            this.fields      = fields;
            this.annotations = annotations;
        }
    }

    /**
     * Parse a GET_CLASS_DETAIL response payload into a ClassDetail.
     * Throws IllegalArgumentException on malformed data.
     */
    public static ClassDetail parseClassDetail(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);

        String className  = readString(buf);
        String superClass = readString(buf);
        List<String> interfaces = readStringList(buf);

        int methodCount = buf.getInt();
        List<MethodInfo> methods = new ArrayList<>(methodCount);
        for (int i = 0; i < methodCount; i++) {
            methods.add(new MethodInfo(readString(buf), readString(buf), buf.getInt()));
        }

        int fieldCount = buf.getInt();
        List<FieldInfo> fields = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            fields.add(new FieldInfo(readString(buf), readString(buf), buf.getInt()));
        }

        int annotCount = buf.getInt();
        List<AnnotationInfo> annotations = new ArrayList<>(annotCount);
        for (int i = 0; i < annotCount; i++) {
            String annotName = readString(buf);
            int kvCount = buf.getInt();
            List<String> keys = new ArrayList<>(kvCount), vals = new ArrayList<>(kvCount);
            for (int k = 0; k < kvCount; k++) {
                keys.add(readString(buf));
                vals.add(readString(buf));
            }
            annotations.add(new AnnotationInfo(annotName, keys, vals));
        }

        return new ClassDetail(className, superClass, interfaces, methods, fields, annotations);
    }
}
