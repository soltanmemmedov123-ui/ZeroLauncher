package com.movtery.zalithlauncher.zerolauncher;

/*
 * InjectionHandler.java  –  agent-side handler for CMD_INJECT_CODE (id = 4)
 *
 * This class lives inside the Java agent JAR that is injected into the running
 * Minecraft JVM.  It is NOT part of the Android launcher project; it is compiled
 * separately and bundled with (or alongside) the agent JAR.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * DEPENDENCY: BeanShell 2.x
 * ─────────────────────────────────────────────────────────────────────────────
 * Gradle (agent module build.gradle):
 *
 *   // Shade BeanShell so it is self-contained inside the agent JAR:
 *   implementation("org.beanshell:bsh:2.0b6")
 *   // OR the community fork (recommended – actively maintained):
 *   implementation("com.github.beanshell:BeanShell:2.1.1")
 *
 * Maven:
 *   <dependency>
 *     <groupId>org.beanshell</groupId>
 *     <artifactId>bsh</artifactId>
 *     <version>2.0b6</version>
 *   </dependency>
 *
 * Manual JAR: drop bsh-2.0b6.jar next to your agent JAR and add it to the
 * -classpath / Class-Path manifest entry, OR shade it into the agent fat-jar.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * PROTOCOL  –  CMD_INJECT_CODE (id = 4)
 * ─────────────────────────────────────────────────────────────────────────────
 * Request  payload : [4-byte int len][UTF-8 code bytes]   (BinaryProtocol.encodeString)
 * Response payload : [1-byte status: 0=OK, 1=ERROR]
 *                    [4-byte int len][UTF-8 message bytes]
 *
 * status 0 → message = captured stdout  +  "\n=> "  +  return value (or "(null)")
 * status 1 → message = error message + optional stack trace
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THREADING
 * ─────────────────────────────────────────────────────────────────────────────
 * Each injection spawns a fresh daemon Thread ("zerolauncher-inject") so that:
 *  a) Scripts run off the game's main thread and cannot deadlock the render loop.
 *  b) If the script runs into an infinite loop, Thread.stop() can forcibly kill
 *     it — which Future.cancel(true) cannot do for CPU-bound loops.
 *
 * The handle() call blocks the IPC worker thread for up to 14 seconds (one
 * second under the launcher's 15-second IPC timeout), then kills the script
 * thread if it is still alive.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * INTERPRETER STRATEGY: fresh per injection vs. reuse
 * ─────────────────────────────────────────────────────────────────────────────
 *  FRESH (implemented here):  safe for memory, no cross-injection side-effects.
 *  REUSE:  allows state to persist across injections (variables, imports).
 *          Risk: class loader leaks over time; a crashed Interpreter taints future
 *          calls.  Not recommended for untrusted / looping scripts.
 *
 * To enable stateful reuse, change createInterpreter() to return a cached
 * instance (see comment in the method body).
 */

import bsh.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public final class InjectionHandler {

    // ── Public entry point called by the IPC server's handleRequest() ─────────

    /**
     * Called by the IPC request dispatcher when cmdId == 4.
     *
     * @param requestPayload raw bytes from the launcher (BinaryProtocol.encodeString)
     * @return response bytes: [1-byte status][length-prefixed UTF-8 string]
     */
    public static byte[] handle(byte[] requestPayload) {
        // 1. Decode the script string from the launcher's payload
        final String script;
        try {
            script = decodeString(requestPayload);
        } catch (Exception e) {
            return buildResponse(true, "Bad request payload: " + e.getMessage());
        }

        // 2. Run on a dedicated daemon Thread so we can call Thread.stop() on it
        //    if it spins in an infinite loop. Future.cancel(true) only interrupts
        //    blocking I/O, not CPU-bound loops — Thread.stop() is the only
        //    option when cooperative cancellation is not available.
        //    (Thread.stop() is deprecated but remains the sole escape hatch here.)
        final AtomicReference<byte[]> result = new AtomicReference<>();
        final Thread scriptThread = new Thread(() -> result.set(evalScript(script)),
                "zerolauncher-inject");
        scriptThread.setDaemon(true);
        try {
            scriptThread.start();
            scriptThread.join(14_000);          // wait up to 14 s (1 s under IPC timeout)
            if (scriptThread.isAlive()) {
                // G2 FIX: Hard-kill a truly infinite loop that ignores interruption.
                // Thread.stop() throws ThreadDeath into the script thread, unwinding
                // any loop regardless of CPU-bound spin. Deprecated, but the only
                // reliable option when the script does not cooperate.
                boolean stopped = stopThread(scriptThread);
                return buildResponse(true,
                    "[TIMEOUT] Script did not complete within 14 seconds.\n" +
                    "It may contain an infinite loop or be waiting on a game lock." +
                    (stopped ? "\nScript thread has been forcibly terminated." : ""));
            }
            byte[] res = result.get();
            return res != null ? res
                    : buildResponse(true, "Script thread completed but returned no result.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return buildResponse(true, "Execution interrupted: " + e.getMessage());
        } catch (Exception e) {
            return buildResponse(true, "Execution dispatch error: " + e.getMessage());
        }
    }

    /** Attempts to forcibly stop a thread. @SuppressWarnings is valid at method level. */
    @SuppressWarnings("deprecation")
    private static boolean stopThread(Thread t) {
        try {
            t.stop();
            return true;
        } catch (UnsupportedOperationException ignored) {
            return false;
        }
    }

    // ── Core evaluation ───────────────────────────────────────────────────────

    private static byte[] evalScript(String script) {
        // Capture BeanShell print() output via the interpreter's own stream.
        // NOTE: We intentionally do NOT call System.setOut/setErr here — those
        // are process-global and would corrupt output if two injections ever
        // ran concurrently (e.g. a second request arriving while a slow script
        // is still running). BeanShell's setOut()/setErr() captures all output
        // from bsh's print() builtin, which is sufficient for developer scripts.
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        PrintStream captured = new PrintStream(baos, true, StandardCharsets.UTF_8);

        // Point BeanShell's own output stream at the capture sink
        Interpreter bsh = createInterpreter(captured);

        try {
            // Populate the BeanShell context with commonly needed Minecraft objects
            populateContext(bsh);

            // Evaluate the script
            Object returnValue = bsh.eval(script);

            // Flush captured output
            captured.flush();
            String stdout = baos.toString(StandardCharsets.UTF_8.name());

            // Build the success message: stdout + "=> " + return value
            StringBuilder msg = new StringBuilder();
            if (!stdout.isEmpty()) {
                msg.append(stdout);
                if (!stdout.endsWith("\n")) msg.append('\n');
            }
            msg.append("=> ").append(returnValue == null ? "(null)" : returnValue.toString());

            return buildResponse(false, msg.toString());

        } catch (Throwable e) {
            // Catch Throwable (not just Exception) so that ThreadDeath — an Error
            // subclass thrown by Thread.stop() — is caught here and surfaced as an
            // error response rather than crashing the agent thread entirely.
            // Re-throw any Error that is NOT ThreadDeath (e.g. OutOfMemoryError).
            if (e instanceof Error && !(e instanceof ThreadDeath)) {
                throw (Error) e;
            }
            captured.flush();
            String stdout = safeToString(baos);

            // Compose: any stdout before the crash + the exception details
            StringWriter sw = new StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));

            String errorMsg = (stdout.isEmpty() ? "" : stdout + "\n")
                + "[ERROR] " + e.getClass().getSimpleName()
                + ": " + e.getMessage() + "\n\n"
                + sw.toString();

            return buildResponse(true, errorMsg);

        } finally {
            // Nothing to restore — we no longer touch System.out/System.err.
        }
    }

    // ── BeanShell setup ───────────────────────────────────────────────────────

    /**
     * Creates a fresh BeanShell Interpreter for each invocation.
     *
     * To reuse a single interpreter across injections (stateful REPL mode),
     * replace this method body with a cached field:
     *
     *   private static final Interpreter REUSED_BSH = new Interpreter();
     *   private static Interpreter createInterpreter(PrintStream out) {
     *       try { REUSED_BSH.setOut(out); REUSED_BSH.setErr(out); }
     *       catch (Exception ignored) {}
     *       return REUSED_BSH;
     *   }
     *
     * Caveat: reuse can cause class loader leaks and stale state between sessions.
     */
    private static Interpreter createInterpreter(PrintStream out) {
        Interpreter bsh = new Interpreter();
        try {
            bsh.setOut(out);
            bsh.setErr(out);
        } catch (Exception ignored) {}

        // G1 FIX: Give BeanShell access to Minecraft's class loader so that
        // net.minecraft.* (and other game/mod classes) can be resolved.
        // Without this, bsh creates its own ClassLoader which cannot see classes
        // loaded by Minecraft's parent loader, causing ClassNotFoundException for
        // any Minecraft type referenced in a script.
        try {
            // Prefer Minecraft's own class loader (most direct path to MC classes)
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            bsh.setClassLoader(mcClass.getClassLoader());
        } catch (ClassNotFoundException e) {
            // Minecraft class not yet visible — fall back to the thread context
            // class loader, which Pojav sets to the game's class loader before
            // entering the main game loop.
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            if (ctx != null) {
                bsh.setClassLoader(ctx);
            }
        }

        return bsh;
    }

    /**
     * Populates the BeanShell namespace with standard Minecraft objects obtained
     * via reflection, so scripts can write {@code mc.player.posX} without
     * any extra imports.
     *
     * All reflective lookups are guarded: if the class / field does not exist in
     * this version of Minecraft (obfuscated name, Fabric vs Forge, version
     * mismatch), the variable is simply left null rather than crashing the handler.
     */
    private static void populateContext(Interpreter bsh) {
        Object mc     = null;
        Object player = null;
        Object world  = null;

        // ── Try Minecraft.getMinecraft() (legacy unobfuscated / MCP names) ──
        mc = tryGetMinecraft(
            "net.minecraft.client.Minecraft",
            "getMinecraft");

        // ── Fall back to the obfuscated srg name used in some mod mappings ──
        if (mc == null) {
            mc = tryGetMinecraft(
                "net.minecraft.client.Minecraft",
                "getInstance");     // 1.16+ Yarn / official mappings
        }

        if (mc != null) {
            // G4 FIX: Try all known SRG/Intermediary/Official field names so the
            // handler works across mapping sets without a recompile.
            // Names sourced from INTEGRATION_GUIDE.md mapping table:
            //   1.8–1.12.2  SRG  : field_71439_g  /  field_71441_e
            //   pre-1.9     MCP  : thePlayer       /  theWorld
            //   1.16+ MCP   : player          /  world
            //   1.16+ Yarn  : player          /  world
            //   1.21+ Official : player       /  level   ← newly added
            player = tryGetField(mc, "player",         // MCP / Yarn / Official 1.16+
                                     "field_71439_g",  // SRG obfuscated 1.8–1.12.2
                                     "thePlayer");     // MCP pre-1.9
            world  = tryGetField(mc, "world",          // MCP / Yarn 1.16+
                                     "level",          // Official mappings 1.21+
                                     "field_71441_e",  // SRG obfuscated 1.8–1.12.2
                                     "theWorld");      // MCP pre-1.9
        }

        // Inject variables into the BeanShell namespace
        setVar(bsh, "mc",     mc);
        setVar(bsh, "player", player);
        setVar(bsh, "world",  world);

        // Convenience: pre-import frequently used packages
        try {
            bsh.eval("import net.minecraft.client.Minecraft;");
            bsh.eval("import net.minecraft.entity.player.*;");
            bsh.eval("import net.minecraft.world.*;");
            bsh.eval("import net.minecraft.util.math.*;");
        } catch (Exception ignored) {
            // If the class doesn't exist (different version), skip silently
        }
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    /** Calls a no-arg static method on a class by fully-qualified name. */
    private static Object tryGetMinecraft(String className, String methodName) {
        try {
            Class<?> clazz = Class.forName(className);
            Method   m     = clazz.getMethod(methodName);
            return m.invoke(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Tries each candidate field name in order until one succeeds.
     * Handles both public and (via setAccessible) private fields.
     */
    private static Object tryGetField(Object target, String... fieldNames) {
        if (target == null) return null;
        for (String name : fieldNames) {
            try {
                java.lang.reflect.Field f = target.getClass().getField(name);
                f.setAccessible(true);
                Object value = f.get(target);
                if (value != null) return value;
            } catch (Exception ignored) {}
            // Also search declared fields (catches private + obfuscated ones)
            try {
                java.lang.reflect.Field f =
                    target.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object value = f.get(target);
                if (value != null) return value;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void setVar(Interpreter bsh, String name, Object value) {
        try {
            bsh.set(name, value);
        } catch (Exception ignored) {}
    }

    // ── Binary protocol helpers ───────────────────────────────────────────────

    /**
     * Decode a length-prefixed UTF-8 string (BinaryProtocol.encodeString format).
     * [4-byte big-endian int length][UTF-8 bytes]
     */
    private static String decodeString(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        int len = buf.getInt();
        if (len < 0 || len > buf.remaining())
            throw new IllegalArgumentException("invalid string length: " + len);
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Build the response payload:
     *   [1-byte status][4-byte int len][UTF-8 message bytes]
     */
    private static byte[] buildResponse(boolean error, String message) {
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf  = ByteBuffer.allocate(1 + 4 + msgBytes.length);
        buf.put((byte) (error ? 1 : 0));
        buf.putInt(msgBytes.length);
        buf.put(msgBytes);
        return buf.array();
    }

    private static String safeToString(ByteArrayOutputStream baos) {
        try {
            return baos.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }
}
