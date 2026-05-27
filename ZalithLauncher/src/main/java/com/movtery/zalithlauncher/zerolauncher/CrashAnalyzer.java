package com.movtery.zalithlauncher.zerolauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.movtery.zalithlauncher.utils.path.PathManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CrashAnalyzer – offline post-mortem parser for ZeroLauncher.
 *
 * Workflow:
 *  1. {@link #analyze(Context, File, String)} – call after the JVM exits with a non-zero code.
 *  2. Returns a {@link CrashReport} with the exception chain, suspected culprits, and
 *     whether a recent injection may be responsible.
 *
 * Heuristics (no internet required):
 *  • Exception type + message extracted from the first "Exception in thread" or
 *    stand-alone exception class line.
 *  • "Caused by" chain is fully collected.
 *  • Mod culprit: every stack-trace line is checked against the names of JAR files
 *    found in the active instance's mods/ directory.
 *  • Injection culprit: the InjectionConsoleFragment history (SharedPreferences) is
 *    read; if the most-recent entry's timestamp is within INJECTION_WINDOW_MS of the
 *    crash, it is flagged.
 *  • Pre-mod crash: if the crash occurs before any recognisable Minecraft class appears
 *    in the trace, a special flag is set.
 */
public class CrashAnalyzer {

    private static final String TAG = "CrashAnalyzer";

    /** Max milliseconds between an injection and a crash to flag it as suspect. */
    private static final long INJECTION_WINDOW_MS = 2 * 60 * 1000; // 2 minutes

    /** Package segments that are definitely Minecraft / Java, never a mod. */
    private static final List<String> VANILLA_PREFIXES = Arrays.asList(
        "net.minecraft", "com.mojang", "java.", "javax.", "sun.", "android.",
        "org.lwjgl", "net.java.games", "org.joml", "io.netty", "com.google.gson"
    );

    /** Known Minecraft package prefixes – used to detect "pre-mod" crashes. */
    private static final List<String> MC_PREFIXES = Arrays.asList(
        "net.minecraft", "com.mojang.blaze3d", "net.optifine"
    );

    // ── Public result model ────────────────────────────────────────────────────

    public static class CrashReport {
        /** Top-level exception class, e.g. "java.lang.NullPointerException". */
        public String exceptionType = "";
        /** Exception message, may be empty. */
        public String exceptionMessage = "";
        /** Full "Caused by" chain as a single formatted string. */
        public String causeChain = "";
        /** All stack trace lines collected. */
        public List<String> stackLines = new ArrayList<>();

        /** Mods that appear in the stack trace, ordered by first appearance. */
        public List<SuspectMod> suspectMods = new ArrayList<>();

        /**
         * True when a recent code injection was executed before the crash and
         * its timestamp falls within {@link #INJECTION_WINDOW_MS}.
         */
        public boolean injectionSuspect = false;
        /** First line of the suspected injection script (for display). */
        public String injectionFirstLine = "";
        /** Formatted time of the injection (HH:mm:ss). */
        public String injectionTime = "";

        /** True when Minecraft's own classes haven't appeared before the crash. */
        public boolean preModCrash = false;

        /** Raw last-50-lines tail of the log for context. */
        public String logTail = "";

        /** Absolute path to the crash log file that was analysed. */
        public String crashLogPath = "";

        /** Timestamp of the analysis (ms since epoch). */
        public long analysisTimestamp = System.currentTimeMillis();
    }

    public static class SuspectMod {
        /** Human-readable mod name derived from the JAR filename. */
        public String modName;
        /** JAR filename (basename only). */
        public String jarFilename;
        /** Stack-trace line where the mod first appeared. */
        public String firstOccurrence;

        SuspectMod(String modName, String jarFilename, String firstOccurrence) {
            this.modName      = modName;
            this.jarFilename  = jarFilename;
            this.firstOccurrence = firstOccurrence;
        }
    }

    // ── Entry point ────────────────────────────────────────────────────────────

    /**
     * @param context       Android context (for SharedPreferences).
     * @param crashLogFile  The crash log file to parse (may be null; we'll auto-find it).
     * @param instanceModsDir  Absolute path to the mods/ folder of the crashed instance
     *                         (may be null if unknown).
     */
    public static CrashReport analyze(Context context, File crashLogFile, String instanceModsDir) {
        CrashReport report = new CrashReport();

        // 1. Locate crash log
        if (crashLogFile == null || !crashLogFile.exists()) {
            crashLogFile = findLatestCrashLog();
        }
        if (crashLogFile == null || !crashLogFile.exists()) {
            // Nothing to parse – still check injection
            checkInjectionSuspect(context, report);
            return report;
        }
        report.crashLogPath = crashLogFile.getAbsolutePath();

        // 2. Read file
        List<String> lines = readLines(crashLogFile);
        if (lines.isEmpty()) {
            checkInjectionSuspect(context, report);
            return report;
        }

        // 3. Extract log tail (last 50 lines)
        int tailStart = Math.max(0, lines.size() - 50);
        StringBuilder tailSb = new StringBuilder();
        for (int i = tailStart; i < lines.size(); i++) {
            tailSb.append(lines.get(i)).append('\n');
        }
        report.logTail = tailSb.toString();

        // 4. Parse exception + stack trace
        parseException(lines, report);

        // 5. Identify mod suspects from mods folder
        List<ModEntry> modEntries = collectModEntries(instanceModsDir);
        identifyModSuspects(report, modEntries);

        // 6. Detect pre-mod crash
        detectPreModCrash(report);

        // 7. Check injection history
        checkInjectionSuspect(context, report);

        return report;
    }

    // ── Step 2: find latest crash log ─────────────────────────────────────────

    private static File findLatestCrashLog() {
        // Try game home crash-reports directory first (standard Minecraft location)
        String gameHome = PathManager.DIR_GAME_HOME;
        if (gameHome == null || gameHome.isEmpty()) return null;

        // Minecraft stores crash reports in <instance>/.minecraft/crash-reports/
        // and also in <game_home>/crash-reports/
        File[] candidates = {
            new File(gameHome, "crash-reports"),
            new File(gameHome, "crash_logs"),
            new File(PathManager.DIR_LAUNCHER_LOG)
        };

        File latest = null;
        long latestTime = 0;

        for (File dir : candidates) {
            if (!dir.isDirectory()) continue;
            File[] logs = dir.listFiles(f -> {
                String n = f.getName().toLowerCase(Locale.ROOT);
                return f.isFile() && (n.endsWith(".log") || n.endsWith(".txt"));
            });
            if (logs == null) continue;
            for (File f : logs) {
                if (f.lastModified() > latestTime) {
                    latestTime = f.lastModified();
                    latest = f;
                }
            }
        }
        return latest;
    }

    // ── Step 3: read lines ─────────────────────────────────────────────────────

    private static List<String> readLines(File f) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read crash log: " + f, e);
        }
        return lines;
    }

    // ── Step 4: exception parsing ──────────────────────────────────────────────

    /**
     * Recognises patterns:
     *   Exception in thread "main" java.lang.NullPointerException: some message
     *   java.lang.RuntimeException: some message
     *   Caused by: java.io.IOException: disk full
     *       at some.Class.method(Class.java:42)
     */
    private static final Pattern EXCEPTION_LINE =
        Pattern.compile("^(\\w[\\w.]*Exception|\\w[\\w.]*Error)(: (.*))?$");
    private static final Pattern THREAD_EXCEPTION =
        Pattern.compile("Exception in thread \"[^\"]*\" (\\w[\\w.]*(?:Exception|Error))(: (.*))?");
    private static final Pattern CAUSED_BY =
        Pattern.compile("^\\s*Caused by: (\\w[\\w.]*(?:Exception|Error))(: (.*))?");
    private static final Pattern AT_LINE =
        Pattern.compile("^\\s*at (\\S+)\\(.*\\)");

    private static void parseException(List<String> lines, CrashReport report) {
        boolean foundFirst = false;
        StringBuilder causeChain = new StringBuilder();

        for (String line : lines) {
            // "Exception in thread" is the canonical first line
            Matcher m = THREAD_EXCEPTION.matcher(line);
            if (m.find() && !foundFirst) {
                report.exceptionType    = m.group(1);
                report.exceptionMessage = m.group(3) != null ? m.group(3) : "";
                foundFirst = true;
                report.stackLines.add(line.trim());
                continue;
            }

            // Bare exception class at start of line
            m = EXCEPTION_LINE.matcher(line.trim());
            if (m.matches() && !foundFirst) {
                report.exceptionType    = m.group(1);
                report.exceptionMessage = m.group(3) != null ? m.group(3) : "";
                foundFirst = true;
                report.stackLines.add(line.trim());
                continue;
            }

            // Caused by
            m = CAUSED_BY.matcher(line);
            if (m.find()) {
                String causedType = m.group(1);
                String causedMsg  = m.group(3) != null ? m.group(3) : "";
                causeChain.append("Caused by: ").append(causedType);
                if (!causedMsg.isEmpty()) causeChain.append(": ").append(causedMsg);
                causeChain.append('\n');
                report.stackLines.add(line.trim());
                continue;
            }

            // Stack frame "at …"
            m = AT_LINE.matcher(line);
            if (m.find()) {
                report.stackLines.add(line.trim());
            }
        }
        report.causeChain = causeChain.toString().trim();
    }

    // ── Step 5: mod identification ─────────────────────────────────────────────

    private static class ModEntry {
        String jarFilename;   // "sodium-1.2.jar"
        String modName;       // "sodium"
        List<String> packages; // ["me.jellysquid.mods.sodium"]

        ModEntry(String jarFilename) {
            this.jarFilename = jarFilename;
            this.modName     = stripVersion(jarFilename.replaceAll("\\.jar$", ""));
            this.packages    = new ArrayList<>();
            // Simple heuristic package: use the lowercase stem as a package segment
            packages.add(this.modName.toLowerCase(Locale.ROOT).replace('-', '_'));
        }

        /** Removes trailing version numbers: "sodium-mc1.20-0.5.3" → "sodium". */
        private static String stripVersion(String name) {
            // Remove common version separators
            return name.replaceAll("[-_](mc)?\\d.*$", "").trim();
        }
    }

    private static List<ModEntry> collectModEntries(String modsDir) {
        List<ModEntry> entries = new ArrayList<>();
        if (modsDir == null || modsDir.isEmpty()) return entries;

        File dir = new File(modsDir);
        if (!dir.isDirectory()) return entries;

        File[] jars = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
        if (jars == null) return entries;

        for (File jar : jars) {
            entries.add(new ModEntry(jar.getName()));
        }
        return entries;
    }

    /** Known heuristic package-segment keywords → friendly mod name */
    private static final Map<String, String> KNOWN_MOD_KEYWORDS = new LinkedHashMap<>();
    static {
        KNOWN_MOD_KEYWORDS.put("optifine",   "OptiFine");
        KNOWN_MOD_KEYWORDS.put("sodium",     "Sodium");
        KNOWN_MOD_KEYWORDS.put("lithium",    "Lithium");
        KNOWN_MOD_KEYWORDS.put("phosphor",   "Phosphor");
        KNOWN_MOD_KEYWORDS.put("iris",       "Iris Shaders");
        KNOWN_MOD_KEYWORDS.put("embeddium",  "Embeddium");
        KNOWN_MOD_KEYWORDS.put("rubidium",   "Rubidium");
        KNOWN_MOD_KEYWORDS.put("xaero",      "Xaero's");
        KNOWN_MOD_KEYWORDS.put("jei",        "Just Enough Items");
        KNOWN_MOD_KEYWORDS.put("journeymap", "JourneyMap");
        KNOWN_MOD_KEYWORDS.put("create",     "Create");
        KNOWN_MOD_KEYWORDS.put("mekanism",   "Mekanism");
        KNOWN_MOD_KEYWORDS.put("twilightforest", "Twilight Forest");
        KNOWN_MOD_KEYWORDS.put("immersiveengineering", "Immersive Engineering");
        KNOWN_MOD_KEYWORDS.put("botania",    "Botania");
        KNOWN_MOD_KEYWORDS.put("quark",      "Quark");
        KNOWN_MOD_KEYWORDS.put("thermal",    "Thermal Series");
        KNOWN_MOD_KEYWORDS.put("enderman",   "Enderman");
    }

    private static void identifyModSuspects(CrashReport report, List<ModEntry> modEntries) {
        // Build a lookup: package-segment → ModEntry
        Map<String, ModEntry> pkgToMod = new LinkedHashMap<>();
        for (ModEntry e : modEntries) {
            for (String pkg : e.packages) {
                pkgToMod.put(pkg, e);
            }
        }

        // Also add known keyword → synthetic entry if not already present
        for (Map.Entry<String, String> kw : KNOWN_MOD_KEYWORDS.entrySet()) {
            if (!pkgToMod.containsKey(kw.getKey())) {
                // Synthetic – no JAR file known
                ModEntry synthetic = new ModEntry(kw.getKey() + ".jar");
                synthetic.modName = kw.getValue();
                pkgToMod.put(kw.getKey(), synthetic);
            }
        }

        List<String> seenModNames = new ArrayList<>();

        for (String frame : report.stackLines) {
            // Skip vanilla
            if (isVanillaLine(frame)) continue;

            String lowerFrame = frame.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, ModEntry> entry : pkgToMod.entrySet()) {
                if (lowerFrame.contains(entry.getKey())) {
                    ModEntry mod = entry.getValue();
                    if (!seenModNames.contains(mod.modName)) {
                        seenModNames.add(mod.modName);
                        report.suspectMods.add(
                            new SuspectMod(mod.modName, mod.jarFilename, frame));
                    }
                    break;
                }
            }
        }
    }

    private static boolean isVanillaLine(String frame) {
        for (String prefix : VANILLA_PREFIXES) {
            if (frame.contains(prefix)) return true;
        }
        return false;
    }

    // ── Step 6: pre-mod crash ─────────────────────────────────────────────────

    private static void detectPreModCrash(CrashReport report) {
        // If no Minecraft class appears in any stack frame, the crash happened before
        // mods would have been loaded (e.g. JVM init or launcher code).
        if (report.suspectMods.isEmpty()) {
            boolean foundMcClass = false;
            for (String frame : report.stackLines) {
                for (String prefix : MC_PREFIXES) {
                    if (frame.contains(prefix)) { foundMcClass = true; break; }
                }
                if (foundMcClass) break;
            }
            report.preModCrash = !foundMcClass;
        }
    }

    // ── Step 7: injection suspect ─────────────────────────────────────────────

    /**
     * Reads InjectionConsoleFragment's SharedPreferences.
     *
     * History format: one entry per line, each entry is:
     *   {@code <iso-timestamp>\n<script body>}
     * separated by the {@code \u0000} (NUL) delimiter used in the fragment.
     *
     * Since the exact serialisation used by InjectionConsoleFragment stores only
     * raw script strings (no timestamp embedded in the value), we treat the file's
     * last-modified time as a proxy, or we use the extended history format produced
     * by the new {@link InjectionHistoryEntry} class (if the app has been updated).
     */
    static void checkInjectionSuspect(Context context, CrashReport report) {
        // Try to load the enhanced history (with timestamps)
        List<InjectionHistoryEntry> history = InjectionHistoryManager.loadHistory(context);
        if (history.isEmpty()) return;

        long crashTime = report.analysisTimestamp;
        InjectionHistoryEntry latest = history.get(0); // most-recent first

        long delta = crashTime - latest.timestampMs;
        if (delta >= 0 && delta <= INJECTION_WINDOW_MS) {
            report.injectionSuspect    = true;
            report.injectionFirstLine  = firstLineOf(latest.code);
            report.injectionTime       = formatTime(latest.timestampMs);
        }
    }

    static String firstLineOf(String code) {
        if (code == null || code.isEmpty()) return "(empty script)";
        String[] lines = code.split("\n", 2);
        String line = lines[0].trim();
        return line.length() > 80 ? line.substring(0, 77) + "…" : line;
    }

    private static String formatTime(long ms) {
        return new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(ms));
    }
}

// ── Package-visible helpers ────────────────────────────────────────────────────
// (used by CrashSessionManager)
