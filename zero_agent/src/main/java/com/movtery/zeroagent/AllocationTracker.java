package com.movtery.zeroagent;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight allocation tracking helper used by the Java agent.
 *
 * This class preserves only very small per-class counters and a short event
 * history for tracked classes. The goal is to make allocation profiling
 * observable with minimal runtime overhead.
 */
public final class AllocationTracker {

    public interface IpcEventListener {
        void onAllocationEvent(String className, StackTraceElement[] stackTrace);
    }

    private static final ConcurrentHashMap<String, AtomicLong> classCounts = new ConcurrentHashMap<>();
    private static final Set<String> trackedClasses = ConcurrentHashMap.newKeySet();
    private static final Deque<AllocationEvent> recentEvents = new ArrayDeque<>();
    private static final Object EVENT_LOCK = new Object();
    private static final int MAX_EVENT_HISTORY = 200;
    private static volatile IpcEventListener eventListener;

    private static final Map<String, Integer> AVERAGE_SIZE_TABLE = createAverageSizeTable();

    private AllocationTracker() {}

    public static void setEventListener(IpcEventListener listener) {
        eventListener = listener;
    }

    public static int trackClass(String className) {
        if (className == null || className.isEmpty()) return 2;
        return trackedClasses.add(className) ? 0 : 1;
    }

    public static void trackAllocation(String className) {
        trackAllocation(className, null);
    }

    public static void trackAllocation(String className, StackTraceElement[] stackTrace) {
        if (className == null || className.isEmpty()) return;

        classCounts.computeIfAbsent(className, k -> new AtomicLong(0)).incrementAndGet();

        if (!trackedClasses.contains(className)) {
            return;
        }

        if (stackTrace == null) {
            stackTrace = Thread.currentThread().getStackTrace();
        }

        int startIndex = 0;
        while (startIndex < stackTrace.length) {
            String cn = stackTrace[startIndex].getClassName();
            if (cn.equals(AllocationTracker.class.getName())
                    || cn.equals(Thread.class.getName())
                    || cn.endsWith("AllocationTransformer")) {
                startIndex++;
                continue;
            }
            break;
        }

        int endIndex = Math.min(stackTrace.length, startIndex + 10);
        StackTraceElement[] trimmed = new StackTraceElement[Math.max(0, endIndex - startIndex)];
        System.arraycopy(stackTrace, startIndex, trimmed, 0, trimmed.length);

        AllocationEvent event = new AllocationEvent(className, trimmed);
        synchronized (EVENT_LOCK) {
            if (recentEvents.size() >= MAX_EVENT_HISTORY) {
                recentEvents.removeFirst();
            }
            recentEvents.addLast(event);
        }

        IpcEventListener listener = eventListener;
        if (listener != null) {
            try {
                listener.onAllocationEvent(className, trimmed);
            } catch (Throwable ignored) {}
        }
    }

    public static List<AllocationSummary> getTopAllocations(int limit) {
        List<AllocationSummary> list = new ArrayList<>();
        for (Map.Entry<String, AtomicLong> entry : classCounts.entrySet()) {
            long count = entry.getValue().get();
            if (count <= 0) continue;
            long size = estimateShallowSize(entry.getKey(), count);
            list.add(new AllocationSummary(entry.getKey(), count, size));
        }
        Collections.sort(list, (a, b) -> Long.compare(b.instanceCount, a.instanceCount));
        if (list.size() > limit) {
            return new ArrayList<>(list.subList(0, limit));
        }
        return list;
    }

    public static List<AllocationEvent> getRecentEvents() {
        synchronized (EVENT_LOCK) {
            return new ArrayList<>(recentEvents);
        }
    }

    public static long getClassCount(String className) {
        AtomicLong value = classCounts.get(className);
        return value == null ? 0 : value.get();
    }

    private static long estimateShallowSize(String className, long count) {
        int averageSize = AVERAGE_SIZE_TABLE.getOrDefault(className, 64);
        return averageSize * count;
    }

    private static Map<String, Integer> createAverageSizeTable() {
        Map<String, Integer> m = new HashMap<>();
        m.put("java.lang.String", 48);
        m.put("java.lang.Integer", 16);
        m.put("java.lang.Long", 24);
        m.put("java.lang.Double", 24);
        m.put("java.lang.Float", 20);
        m.put("java.lang.Boolean", 16);
        m.put("java.lang.Short", 16);
        m.put("java.lang.Byte", 16);
        m.put("java.lang.Character", 16);
        m.put("java.util.ArrayList", 40);
        m.put("java.util.HashMap", 96);
        m.put("java.util.LinkedList", 56);
        m.put("net.minecraft.client.Minecraft", 256);
        return m;
    }

    public static byte[] encodeEventPayload(String className, StackTraceElement[] stackTrace) {
        List<byte[]> parts = new ArrayList<>();
        byte[] classBytes = className.getBytes(StandardCharsets.UTF_8);
        parts.add(ByteBuffer.allocate(4).putInt(classBytes.length).array());
        parts.add(classBytes);

        int count = Math.min(stackTrace.length, 10);
        parts.add(ByteBuffer.allocate(4).putInt(count).array());
        for (int i = 0; i < count; i++) {
            byte[] frameBytes = stackTrace[i].toString().getBytes(StandardCharsets.UTF_8);
            parts.add(ByteBuffer.allocate(4).putInt(frameBytes.length).array());
            parts.add(frameBytes);
        }

        int total = 0;
        for (byte[] part : parts) total += part.length;
        ByteBuffer out = ByteBuffer.allocate(total);
        for (byte[] part : parts) out.put(part);
        return out.array();
    }

    public static final class AllocationSummary {
        public final String className;
        public final long instanceCount;
        public final long shallowSizeEstimate;

        AllocationSummary(String className, long instanceCount, long shallowSizeEstimate) {
            this.className = className;
            this.instanceCount = instanceCount;
            this.shallowSizeEstimate = shallowSizeEstimate;
        }
    }

    public static final class AllocationEvent {
        public final String className;
        public final StackTraceElement[] stackTrace;

        AllocationEvent(String className, StackTraceElement[] stackTrace) {
            this.className = className;
            this.stackTrace = stackTrace;
        }
    }
}
