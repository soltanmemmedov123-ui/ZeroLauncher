package com.movtery.zeroagent;

import net.kdt.pojavlaunch.IpcServer;

import java.lang.annotation.Annotation;
import java.lang.instrument.Instrumentation;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

/**
 * Handles CMD_GET_CLASS_LIST and CMD_GET_CLASS_DETAIL using Instrumentation
 * to enumerate loaded classes and reflection to inspect them.
 * Response binary format matches BinaryProtocol on the launcher side exactly.
 */
public class AgentCommandHandler implements IpcServer.CommandHandler {

    private static final String[] MC_PREFIXES = {
        "net.minecraft.", "com.mojang.", "net.minecraftforge.",
        "cpw.mods.", "net.fabricmc.", "org.spongepowered."
    };

    private final Instrumentation inst;

    public AgentCommandHandler(Instrumentation inst) {
        this.inst = inst;
    }

    @Override
    public byte[] handle(int cmd, byte[] payload) throws Exception {
        switch (cmd) {
            case IpcServer.CMD_GET_CLASS_LIST:   return handleClassList();
            case IpcServer.CMD_GET_CLASS_DETAIL: return handleClassDetail(payload);
            case IpcServer.CMD_HEAP_SUMMARY:     return handleHeapSummary();
            case IpcServer.CMD_TOP_ALLOCATIONS:  return handleTopAllocations(payload);
            case IpcServer.CMD_TRACK_CLASS:      return handleTrackClass(payload);
            case IpcServer.CMD_RUN_GC:           return handleRunGc();
            default: return new byte[0];
        }
    }

    // ── Class list ────────────────────────────────────────────────────────────

    private byte[] handleClassList() {
        StringBuilder sb = new StringBuilder();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String name = c.getName();
            // Skip array classes (name starts with '[')
            if (name.charAt(0) == '[') continue;
            if (isMcClass(name)) sb.append(name).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isMcClass(String name) {
        for (String p : MC_PREFIXES) if (name.startsWith(p)) return true;
        return false;
    }

    // ── Class detail ──────────────────────────────────────────────────────────

    private byte[] handleClassDetail(byte[] payload) throws Exception {
        String className = readString(ByteBuffer.wrap(payload));

        // Try to find the class using the classloader of an already-loaded MC class,
        // falling back to the context classloader. This is needed because premain's
        // thread uses the system classloader which doesn't know about Minecraft classes.
        Class<?> cls = null;
        ClassNotFoundException lastEx = null;
        ClassLoader[] candidates = {
            Thread.currentThread().getContextClassLoader(),
            findMcClassLoader(),
            ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader cl : candidates) {
            if (cl == null) continue;
            try { cls = Class.forName(className, false, cl); break; }
            catch (ClassNotFoundException e) { lastEx = e; }
        }
        if (cls == null) throw (lastEx != null ? lastEx : new ClassNotFoundException(className));

        List<byte[]> parts = new ArrayList<>();

        // class name + superclass
        parts.add(encodeString(cls.getName()));
        Class<?> sup = cls.getSuperclass();
        parts.add(encodeString(sup != null ? sup.getName() : ""));

        // interfaces
        Class<?>[] ifaces = cls.getInterfaces();
        parts.add(encodeInt(ifaces.length));
        for (Class<?> i : ifaces) parts.add(encodeString(i.getName()));

        // methods
        Method[] methods = safeGetDeclaredMethods(cls);
        parts.add(encodeInt(methods.length));
        for (Method m : methods) {
            parts.add(encodeString(m.getName()));
            parts.add(encodeString(methodDescriptor(m)));
            parts.add(encodeInt(m.getModifiers()));
        }

        // fields
        Field[] fields = safeGetDeclaredFields(cls);
        parts.add(encodeInt(fields.length));
        for (Field f : fields) {
            parts.add(encodeString(f.getName()));
            parts.add(encodeString(f.getType().getName()));
            parts.add(encodeInt(f.getModifiers()));
        }

        // annotations
        Annotation[] annots = cls.getDeclaredAnnotations();
        parts.add(encodeInt(annots.length));
        for (Annotation a : annots) {
            parts.add(encodeString(a.annotationType().getName()));
            Method[] am = a.annotationType().getDeclaredMethods();
            parts.add(encodeInt(am.length));
            for (Method m : am) {
                parts.add(encodeString(m.getName()));
                try { parts.add(encodeString(String.valueOf(m.invoke(a)))); }
                catch (Exception e) { parts.add(encodeString("?")); }
            }
        }

        return concat(parts);
    }

    private byte[] handleHeapSummary() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        int loadedClasses = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();

        StringJoiner gcNames = new StringJoiner(",");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcNames.add(gc.getName());
        }

        byte[] gcBytes = gcNames.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 * 3 + 4 + 4 + 4 + gcBytes.length);
        buf.putLong(heap.getUsed());
        buf.putLong(heap.getCommitted());
        buf.putLong(heap.getMax());
        buf.putInt(loadedClasses);
        buf.putInt(threadCount);
        buf.putInt(gcBytes.length);
        buf.put(gcBytes);
        return buf.array();
    }

    private byte[] handleTopAllocations(byte[] payload) {
        ByteBuffer request = ByteBuffer.wrap(payload);
        int limit = request.getInt();
        List<AllocationTracker.AllocationSummary> summaries = AllocationTracker.getTopAllocations(limit);

        List<byte[]> parts = new ArrayList<>();
        parts.add(encodeInt(summaries.size()));
        for (AllocationTracker.AllocationSummary s : summaries) {
            parts.add(encodeString(s.className));
            parts.add(encodeLong(s.instanceCount));
            parts.add(encodeLong(s.shallowSizeEstimate));
        }
        return concat(parts);
    }

    private byte[] handleTrackClass(byte[] payload) {
        String className = readString(ByteBuffer.wrap(payload));
        int result = AllocationTracker.trackClass(className);
        return new byte[]{(byte) result};
    }

    private byte[] handleRunGc() {
        System.gc();
        return new byte[]{0};
    }

    private static byte[] encodeLong(long v) {
        return ByteBuffer.allocate(8).putLong(v).array();
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    /** Find the classloader used by Minecraft by looking at already-loaded MC classes. */
    private ClassLoader findMcClassLoader() {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().startsWith("net.minecraft.") && c.getClassLoader() != null) {
                return c.getClassLoader();
            }
        }
        return null;
    }

    private static Method[] safeGetDeclaredMethods(Class<?> c) {
        try { return c.getDeclaredMethods(); } catch (Throwable t) { return new Method[0]; }
    }

    private static Field[] safeGetDeclaredFields(Class<?> c) {
        try { return c.getDeclaredFields(); } catch (Throwable t) { return new Field[0]; }
    }

    private static String methodDescriptor(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) sb.append(typeDesc(p));
        return sb.append(")").append(typeDesc(m.getReturnType())).toString();
    }

    private static String typeDesc(Class<?> c) {
        if (c == void.class)    return "V";
        if (c == boolean.class) return "Z";
        if (c == byte.class)    return "B";
        if (c == char.class)    return "C";
        if (c == short.class)   return "S";
        if (c == int.class)     return "I";
        if (c == long.class)    return "J";
        if (c == float.class)   return "F";
        if (c == double.class)  return "D";
        if (c.isArray())        return c.getName().replace('.', '/');
        return "L" + c.getName().replace('.', '/') + ";";
    }

    // ── Binary encoding ───────────────────────────────────────────────────────

    private static String readString(ByteBuffer buf) {
        byte[] b = new byte[buf.getInt()];
        buf.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] encodeString(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + b.length).putInt(b.length).put(b).array();
    }

    private static byte[] encodeInt(int v) {
        return ByteBuffer.allocate(4).putInt(v).array();
    }

    private static byte[] concat(List<byte[]> parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        ByteBuffer out = ByteBuffer.allocate(total);
        for (byte[] p : parts) out.put(p);
        return out.array();
    }
}
