package net.kdt.pojavlaunch.tasks;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome;
import com.movtery.zalithlauncher.feature.log.Logging;
import com.movtery.zalithlauncher.feature.version.VersionsManager;
import com.movtery.zalithlauncher.setting.AllSettings;
import com.movtery.zalithlauncher.task.Task;
import com.movtery.zalithlauncher.utils.path.PathManager;

import net.kdt.pojavlaunch.JAssetInfo;
import net.kdt.pojavlaunch.JAssets;
import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.mirrors.MirrorTamperedException;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.DependentLibrary;
import net.kdt.pojavlaunch.value.MinecraftClientInfo;
import net.kdt.pojavlaunch.value.MinecraftLibraryArtifact;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class MinecraftDownloader {
    private static final double ONE_MEGABYTE = 1024d * 1024d;
    public static final String MINECRAFT_RES = "https://resources.download.minecraft.net/";
    private static final String MAVEN_CENTRAL_REPO1 = "https://repo1.maven.org/maven2/";
    private static final ThreadLocal<byte[]> THREAD_LOCAL_DOWNLOAD_BUFFER = new ThreadLocal<>();

    /**
     * Starts the game version download process.
     *
     * Important:
     * This downloader should only download and refresh the versions cache.
     * It must NOT decide which version becomes current, because loader installs
     * (Fabric/Forge/NeoForge) often download a base vanilla version first and then
     * create the final loader version afterward.
     *
     * The final installer flow should decide which version becomes selected.
     */
    public void start(
            @Nullable JMinecraftVersionList.Version version,
            @NonNull String realVersion,
            @NonNull AsyncMinecraftDownloader.DoneListener listener
    ) {
        Task.runTask(() -> {
                    downloadGame(version, realVersion);

                    // Refresh the in-memory version list so newly downloaded files become visible
                    // without requiring a manual refresh. Do not mark this version as current here.
                    VersionsManager.INSTANCE.refresh("MinecraftDownloader:start", true);

                    listener.onDownloadDone();
                    return null;
                })
                .onThrowable(listener::onDownloadFailed)
                .finallyTask(() -> ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_MINECRAFT))
                .execute();
    }

    private void downloadGame(
            @Nullable JMinecraftVersionList.Version listedVersion,
            @NonNull String versionName
    ) throws Exception {
        ProgressLayout.setProgress(
                ProgressLayout.DOWNLOAD_MINECRAFT,
                0,
                R.string.newdl_starting
        );

        DownloadPlan plan = buildDownloadPlan(listedVersion, versionName);
        DownloadStats stats = new DownloadStats(plan.totalFileCount, plan.totalSize, plan.useFileCounter);

        executePlan(plan, stats);
        ensureJarFileCopy(plan);
        extractNatives(plan);
    }

    private DownloadPlan buildDownloadPlan(
            @Nullable JMinecraftVersionList.Version listedVersion,
            @NonNull String versionName
    ) throws Exception {
        PlanBuilder builder = new PlanBuilder(createGameJarPath(versionName));
        collectMetadataAndSchedule(builder, listedVersion, versionName);
        return builder.build();
    }

    private void collectMetadataAndSchedule(
            @NonNull PlanBuilder builder,
            @Nullable JMinecraftVersionList.Version listedVersion,
            @NonNull String versionName
    ) throws Exception {
        File versionJsonFile;

        if (listedVersion != null) {
            versionJsonFile = downloadGameJson(listedVersion);
        } else {
            versionJsonFile = createGameJsonPath(versionName);
        }

        if (!versionJsonFile.isFile() || !versionJsonFile.canRead()) {
            throw new IOException("Unable to read version JSON for version " + versionName);
        }

        JMinecraftVersionList.Version resolvedVersion =
                Tools.GLOBAL_GSON.fromJson(Tools.read(versionJsonFile), JMinecraftVersionList.Version.class);

        if (resolvedVersion == null) {
            throw new IOException("Unable to parse version JSON for version " + versionName);
        }

        JAssets assets = downloadAssetsIndex(resolvedVersion);
        if (assets != null) {
            scheduleAssetDownloads(builder, assets);
        }

        MinecraftClientInfo clientInfo = getClientInfo(resolvedVersion);
        if (clientInfo != null) {
            scheduleGameJarDownload(builder, clientInfo, versionName);
        }

        if (resolvedVersion.libraries != null) {
            scheduleLibraryDownloads(builder, resolvedVersion.libraries);
        }

        if (Tools.isValidString(resolvedVersion.inheritsFrom)) {
            JMinecraftVersionList.Version inheritedListedVersion =
                    AsyncMinecraftDownloader.getListedVersion(resolvedVersion.inheritsFrom);
            collectMetadataAndSchedule(builder, inheritedListedVersion, resolvedVersion.inheritsFrom);
        }
    }

    private void executePlan(@NonNull DownloadPlan plan, @NonNull DownloadStats stats) throws Exception {
        if (plan.entries.isEmpty()) {
            reportProgress(stats, 0d);
            return;
        }

        int threadCount = Math.max(
                1,
                Math.min(AllSettings.getMaxDownloadThreads().getValue(), plan.entries.size())
        );

        ExecutorService executor = Executors.newFixedThreadPool(threadCount, new NamedThreadFactory("MCDownloader"));
        ExecutorCompletionService<Void> completionService = new ExecutorCompletionService<>(executor);
        List<Future<Void>> futures = new ArrayList<>(plan.entries.size());
        SpeedCalculator speedCalculator = new SpeedCalculator();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        try {
            for (DownloadEntry entry : plan.entries) {
                futures.add(completionService.submit(new DownloaderTask(entry, stats, failures)));
            }

            int completed = 0;
            while (completed < plan.entries.size()) {
                Future<Void> future = completionService.poll(100, TimeUnit.MILLISECONDS);

                double speed = speedCalculator.feed(stats.internetUsageCounter.get()) / ONE_MEGABYTE;
                reportProgress(stats, speed);

                if (future == null) {
                    continue;
                }

                try {
                    future.get();
                    completed++;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    failures.add(cause);
                    cancelAll(futures);
                    executor.shutdownNow();
                    throw asException(cause);
                } catch (CancellationException e) {
                    failures.add(e);
                    executor.shutdownNow();
                    throw e;
                }
            }

            double finalSpeed = speedCalculator.feed(stats.internetUsageCounter.get()) / ONE_MEGABYTE;
            reportProgress(stats, finalSpeed);
        } catch (InterruptedException e) {
            cancelAll(futures);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            executor.shutdownNow();
        }

        Throwable failure = failures.peek();
        if (failure != null) {
            throw asException(failure);
        }
    }

    private void cancelAll(@NonNull List<Future<Void>> futures) {
        for (Future<Void> future : futures) {
            future.cancel(true);
        }
    }

    @NonNull
    private Exception asException(@NonNull Throwable throwable) {
        if (throwable instanceof Exception) {
            return (Exception) throwable;
        }
        return new RuntimeException(throwable);
    }

    private void reportProgress(@NonNull DownloadStats stats, double speed) {
        if (stats.useFileCounter || stats.totalSize <= 0) {
            reportProgressByFileCount(stats, speed);
        } else {
            reportProgressBySize(stats, speed);
        }
    }

    private void reportProgressByFileCount(@NonNull DownloadStats stats, double speed) {
        long processedFiles = stats.processedFileCounter.get();
        long totalFiles = Math.max(1, stats.totalFileCount);
        int progress = (int) ((processedFiles * 100L) / totalFiles);

        ProgressLayout.setProgress(
                ProgressLayout.DOWNLOAD_MINECRAFT,
                progress,
                R.string.newdl_downloading_game_files,
                processedFiles,
                totalFiles,
                speed
        );
    }

    private void reportProgressBySize(@NonNull DownloadStats stats, double speed) {
        long processedBytes = stats.processedSizeCounter.get();
        long totalBytes = Math.max(1L, stats.totalSize);
        double processedMb = processedBytes / ONE_MEGABYTE;
        double totalMb = totalBytes / ONE_MEGABYTE;
        int progress = (int) ((processedBytes * 100L) / totalBytes);

        ProgressLayout.setProgress(
                ProgressLayout.DOWNLOAD_MINECRAFT,
                progress,
                R.string.newdl_downloading_game_files_size,
                processedMb,
                totalMb,
                speed
        );
    }

    private File createGameJsonPath(String versionId) {
        return new File(ProfilePathHome.getVersionsHome(), versionId + File.separator + versionId + ".json");
    }

    private File createGameJarPath(String versionId) {
        return new File(ProfilePathHome.getVersionsHome(), versionId + File.separator + versionId + ".jar");
    }

    private void ensureJarFileCopy(@NonNull DownloadPlan plan) throws IOException {
        if (plan.sourceJarFile == null) {
            return;
        }
        if (plan.sourceJarFile.equals(plan.targetJarFile)) {
            return;
        }
        if (plan.targetJarFile.exists()) {
            return;
        }

        FileUtils.ensureParentDirectory(plan.targetJarFile);
        Logging.i(
                "MinecraftDownloader",
                "Copying " + plan.sourceJarFile.getName() + " to " + plan.targetJarFile.getAbsolutePath()
        );
        org.apache.commons.io.FileUtils.copyFile(plan.sourceJarFile, plan.targetJarFile, false);
    }

    private void extractNatives(@NonNull DownloadPlan plan) throws IOException {
        if (plan.declaredNatives.isEmpty()) {
            return;
        }

        int totalCount = plan.declaredNatives.size();
        ProgressLayout.setProgress(
                ProgressLayout.DOWNLOAD_MINECRAFT,
                0,
                R.string.newdl_extracting_native_libraries,
                0,
                totalCount
        );

        File targetDirectory = new File(PathManager.DIR_CACHE, "natives/" + FilenameUtils.getBaseName(plan.targetJarFile.getName()));
        FileUtils.ensureDirectory(targetDirectory);

        NativesExtractor extractor = new NativesExtractor(targetDirectory);
        int extractedCount = 0;

        for (File source : plan.declaredNatives) {
            extractor.extractFromAar(source);
            extractedCount++;

            ProgressLayout.setProgress(
                    ProgressLayout.DOWNLOAD_MINECRAFT,
                    extractedCount * 100 / totalCount,
                    R.string.newdl_extracting_native_libraries,
                    extractedCount,
                    totalCount
            );
        }
    }

    private File downloadGameJson(@NonNull JMinecraftVersionList.Version version) throws IOException, MirrorTamperedException {
        File targetFile = createGameJsonPath(version.id);

        if (version.sha1 == null && targetFile.isFile() && targetFile.canRead()) {
            return targetFile;
        }

        FileUtils.ensureParentDirectory(targetFile);
        try {
            DownloadUtils.ensureSha1(
                    targetFile,
                    AllSettings.getVerifyManifest().getValue() ? version.sha1 : null,
                    () -> {
                        ProgressLayout.setProgress(
                                ProgressLayout.DOWNLOAD_MINECRAFT,
                                0,
                                R.string.newdl_downloading_metadata,
                                targetFile.getName()
                        );
                        DownloadMirror.downloadFileMirrored(
                                DownloadMirror.DOWNLOAD_CLASS_METADATA,
                                version.url,
                                targetFile
                        );
                        return null;
                    }
            );
        } catch (DownloadUtils.SHA1VerificationException e) {
            if (DownloadMirror.isMirrored()) {
                throw new MirrorTamperedException();
            }
            throw e;
        }

        return targetFile;
    }

    private JAssets downloadAssetsIndex(@NonNull JMinecraftVersionList.Version version) throws IOException {
        JMinecraftVersionList.AssetIndex assetIndex = version.assetIndex;
        if (assetIndex == null || version.assets == null) {
            return null;
        }

        File targetFile = new File(
                ProfilePathHome.getAssetsHome(),
                "indexes" + File.separator + version.assets + ".json"
        );

        FileUtils.ensureParentDirectory(targetFile);
        DownloadUtils.ensureSha1(targetFile, assetIndex.sha1, () -> {
            ProgressLayout.setProgress(
                    ProgressLayout.DOWNLOAD_MINECRAFT,
                    0,
                    R.string.newdl_downloading_metadata,
                    targetFile.getName()
            );
            DownloadMirror.downloadFileMirrored(
                    DownloadMirror.DOWNLOAD_CLASS_METADATA,
                    assetIndex.url,
                    targetFile
            );
            return null;
        });

        return Tools.GLOBAL_GSON.fromJson(Tools.read(targetFile), JAssets.class);
    }

    private MinecraftClientInfo getClientInfo(@NonNull JMinecraftVersionList.Version version) {
        Map<String, MinecraftClientInfo> downloads = version.downloads;
        if (downloads == null) {
            return null;
        }
        return downloads.get("client");
    }

    private void scheduleAssetDownloads(@NonNull PlanBuilder builder, @NonNull JAssets assets) throws IOException {
        Map<String, JAssetInfo> assetObjects = assets.objects;
        if (assetObjects == null || assetObjects.isEmpty()) {
            return;
        }

        for (Map.Entry<String, JAssetInfo> entry : assetObjects.entrySet()) {
            String assetName = entry.getKey();
            JAssetInfo assetInfo = entry.getValue();
            if (assetInfo == null || assetInfo.hash == null || assetInfo.hash.length() < 2) {
                continue;
            }

            String hashedPath = assetInfo.hash.substring(0, 2) + File.separator + assetInfo.hash;
            String basePath = assets.mapToResources
                    ? ProfilePathHome.getResourcesHome()
                    : ProfilePathHome.getAssetsHome();

            File targetFile;
            if (assets.virtual || assets.mapToResources) {
                targetFile = new File(basePath, assetName);
            } else {
                targetFile = new File(basePath, "objects" + File.separator + hashedPath);
            }

            String sha1 = AllSettings.getCheckLibraries().getValue() ? assetInfo.hash : null;
            builder.addDownload(
                    new DownloadEntry(
                            targetFile,
                            DownloadMirror.DOWNLOAD_CLASS_ASSETS,
                            MINECRAFT_RES + hashedPath,
                            sha1,
                            assetInfo.size,
                            false
                    )
            );
        }
    }

    private void scheduleGameJarDownload(
            @NonNull PlanBuilder builder,
            @NonNull MinecraftClientInfo clientInfo,
            @NonNull String versionName
    ) throws IOException {
        File clientJar = createGameJarPath(versionName);
        String clientSha1 = AllSettings.getCheckLibraries().getValue() ? clientInfo.sha1 : null;

        builder.addDownload(
                new DownloadEntry(
                        clientJar,
                        DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                        clientInfo.url,
                        clientSha1,
                        clientInfo.size,
                        false
                )
        );

        // The last scheduled client JAR in the inheritance chain becomes the source JAR.
        builder.sourceJarFile = clientJar;
    }

    private void scheduleNativeLibraryDownload(
            @NonNull PlanBuilder builder,
            @NonNull String baseRepository,
            @NonNull DependentLibrary dependentLibrary
    ) throws IOException {
        String libArtifactPath = Tools.artifactToPath(dependentLibrary);
        if (libArtifactPath == null) {
            return;
        }

        String aarPath = org.apache.commons.io.FilenameUtils.removeExtension(libArtifactPath) + ".aar";
        String downloadUrl = baseRepository + aarPath;
        File targetFile = new File(ProfilePathHome.getLibrariesHome(), aarPath);

        builder.addNative(targetFile);
        builder.addDownload(
                new DownloadEntry(
                        targetFile,
                        DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                        downloadUrl,
                        null,
                        0L,
                        true
                )
        );
    }

    private void scheduleLibraryDownloads(
            @NonNull PlanBuilder builder,
            @NonNull DependentLibrary[] dependentLibraries
    ) throws IOException {
        Tools.preProcessLibraries(dependentLibraries);

        for (DependentLibrary dependentLibrary : dependentLibraries) {
            if (dependentLibrary == null || dependentLibrary.name == null) {
                continue;
            }

            if (dependentLibrary.name.startsWith("org.lwjgl")) {
                continue;
            }

            if (dependentLibrary.name.startsWith("net.java.dev.jna:jna:")) {
                scheduleNativeLibraryDownload(builder, MAVEN_CENTRAL_REPO1, dependentLibrary);
            }

            String libArtifactPath = Tools.artifactToPath(dependentLibrary);
            if (libArtifactPath == null) {
                continue;
            }

            String sha1 = null;
            String url = null;
            long size = 0L;
            boolean skipIfFailed = false;

            if (dependentLibrary.downloads != null) {
                if (dependentLibrary.downloads.artifact != null) {
                    MinecraftLibraryArtifact artifact = dependentLibrary.downloads.artifact;
                    sha1 = artifact.sha1;
                    url = artifact.url;
                    size = artifact.size;
                } else {
                    Logging.i(
                            "MinecraftDownloader",
                            "Skipped library " + dependentLibrary.name + " due to lack of artifact"
                    );
                    continue;
                }
            }

            if (url == null) {
                url = (dependentLibrary.url == null
                        ? "https://libraries.minecraft.net/"
                        : dependentLibrary.url.replace("http://", "https://"))
                        + libArtifactPath;
                skipIfFailed = true;
            }

            if (!AllSettings.getCheckLibraries().getValue()) {
                sha1 = null;
            }

            builder.addDownload(
                    new DownloadEntry(
                            new File(ProfilePathHome.getLibrariesHome(), libArtifactPath),
                            DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                            url,
                            sha1,
                            size,
                            skipIfFailed
                    )
            );
        }
    }

    private static byte[] getLocalBuffer() {
        byte[] buffer = THREAD_LOCAL_DOWNLOAD_BUFFER.get();
        if (buffer != null) {
            return buffer;
        }

        buffer = new byte[32768];
        THREAD_LOCAL_DOWNLOAD_BUFFER.set(buffer);
        return buffer;
    }

    private static final class DownloadPlan {
        final List<DownloadEntry> entries;
        final List<File> declaredNatives;
        final File sourceJarFile;
        final File targetJarFile;
        final long totalFileCount;
        final long totalSize;
        final boolean useFileCounter;

        private DownloadPlan(
                List<DownloadEntry> entries,
                List<File> declaredNatives,
                File sourceJarFile,
                File targetJarFile,
                long totalFileCount,
                long totalSize,
                boolean useFileCounter
        ) {
            this.entries = entries;
            this.declaredNatives = declaredNatives;
            this.sourceJarFile = sourceJarFile;
            this.targetJarFile = targetJarFile;
            this.totalFileCount = totalFileCount;
            this.totalSize = totalSize;
            this.useFileCounter = useFileCounter;
        }
    }

    private static final class DownloadEntry {
        final File targetFile;
        final int downloadClass;
        final String url;
        final String sha1;
        final long size;
        final boolean skipIfFailed;

        private DownloadEntry(
                File targetFile,
                int downloadClass,
                String url,
                String sha1,
                long size,
                boolean skipIfFailed
        ) {
            this.targetFile = targetFile;
            this.downloadClass = downloadClass;
            this.url = url;
            this.sha1 = sha1;
            this.size = Math.max(0L, size);
            this.skipIfFailed = skipIfFailed;
        }
    }

    private static final class DownloadStats {
        final AtomicLong processedFileCounter = new AtomicLong(0);
        final AtomicLong processedSizeCounter = new AtomicLong(0);
        final AtomicLong internetUsageCounter = new AtomicLong(0);
        final long totalFileCount;
        final long totalSize;
        final boolean useFileCounter;

        private DownloadStats(long totalFileCount, long totalSize, boolean useFileCounter) {
            this.totalFileCount = totalFileCount;
            this.totalSize = totalSize;
            this.useFileCounter = useFileCounter;
        }
    }

    private static final class PlanBuilder {
        private final List<DownloadEntry> entries = new ArrayList<>();
        private final List<File> declaredNatives = new ArrayList<>();
        private final Set<String> scheduledPaths = new HashSet<>();
        private final Set<String> nativePaths = new HashSet<>();
        private final File targetJarFile;

        private long totalFileCount = 0L;
        private long totalSize = 0L;
        private boolean useFileCounter = false;
        private File sourceJarFile;

        private PlanBuilder(@NonNull File targetJarFile) {
            this.targetJarFile = targetJarFile;
        }

        private void addDownload(@NonNull DownloadEntry entry) throws IOException {
            FileUtils.ensureParentDirectory(entry.targetFile);

            String pathKey = entry.targetFile.getCanonicalPath();
            if (!scheduledPaths.add(pathKey)) {
                return;
            }

            entries.add(entry);
            totalFileCount++;

            if (entry.size > 0) {
                totalSize += entry.size;
            } else {
                useFileCounter = true;
            }
        }

        private void addNative(@NonNull File file) throws IOException {
            String pathKey = file.getCanonicalPath();
            if (nativePaths.add(pathKey)) {
                declaredNatives.add(file);
            }
        }

        private DownloadPlan build() {
            return new DownloadPlan(
                    entries,
                    declaredNatives,
                    sourceJarFile,
                    targetJarFile,
                    totalFileCount,
                    totalSize,
                    useFileCounter
            );
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private int counter = 0;

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public synchronized Thread newThread(@NonNull Runnable r) {
            Thread thread = new Thread(r, prefix + "-" + counter++);
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class DownloaderTask implements Callable<Void>, Tools.DownloaderFeedback {
        private final DownloadEntry entry;
        private final DownloadStats stats;
        private final ConcurrentLinkedQueue<Throwable> failures;
        private long lastCurr = 0L;

        private DownloaderTask(
                @NonNull DownloadEntry entry,
                @NonNull DownloadStats stats,
                @NonNull ConcurrentLinkedQueue<Throwable> failures
        ) {
            this.entry = entry;
            this.stats = stats;
            this.failures = failures;
        }

        @Override
        public Void call() throws Exception {
            try {
                runInternal();
                return null;
            } catch (Throwable t) {
                failures.add(t);
                throw t;
            }
        }

        private void runInternal() throws Exception {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Download interrupted before start");
            }

            String effectiveSha1 = entry.sha1;

            // Optimization:
            // Only try to fetch a remote .sha1 sidecar when it can help us skip downloading an already existing file.
            if (entry.downloadClass == DownloadMirror.DOWNLOAD_CLASS_LIBRARIES
                    && !Tools.isValidString(effectiveSha1)
                    && entry.targetFile.isFile()
                    && entry.targetFile.canRead()) {
                effectiveSha1 = tryDownloadRemoteSha1(entry.downloadClass, entry.url, stats);
            }

            if (Tools.isValidString(effectiveSha1)) {
                if (entry.targetFile.isFile()
                        && entry.targetFile.canRead()
                        && Tools.compareSHA1(entry.targetFile, effectiveSha1)) {
                    finishWithoutDownloading();
                    return;
                }
            } else if (entry.targetFile.exists()) {
                finishWithoutDownloading();
                return;
            }

            downloadFile(effectiveSha1);
        }

        private String tryDownloadRemoteSha1(int downloadClass, String targetUrl, DownloadStats stats) {
            try {
                String hash = DownloadMirror.downloadStringMirrored(downloadClass, targetUrl + ".sha1");
                if (!Tools.isValidString(hash)) {
                    return null;
                }

                hash = hash.trim();
                if (hash.length() != 40) {
                    return null;
                }

                stats.internetUsageCounter.addAndGet(hash.length());
                Logging.i("MinecraftDownloader", "Got remote SHA1 for " + targetUrl);
                return hash;
            } catch (IOException e) {
                Logging.i("MinecraftDownloader", "Failed to download remote SHA1 for " + targetUrl, e);
                return null;
            }
        }

        private void downloadFile(@Nullable String effectiveSha1) throws Exception {
            try {
                DownloadUtils.ensureSha1(entry.targetFile, effectiveSha1, () -> {
                    DownloadMirror.downloadFileMirrored(
                            entry.downloadClass,
                            entry.url,
                            entry.targetFile,
                            getLocalBuffer(),
                            this
                    );
                    return null;
                });
            } catch (Exception e) {
                if (!entry.skipIfFailed) {
                    throw e;
                }
                Logging.i("MinecraftDownloader", "Skipping failed optional download: " + entry.url, e);
            }

            stats.processedFileCounter.incrementAndGet();
            if (entry.size > 0 && lastCurr <= 0) {
                // Fallback: in case the downloader did not report progress callbacks,
                // still count known-size completed files as processed.
                stats.processedSizeCounter.addAndGet(entry.size);
            }
        }

        private void finishWithoutDownloading() {
            stats.processedFileCounter.incrementAndGet();
            if (entry.size > 0) {
                stats.processedSizeCounter.addAndGet(entry.size);
            }
        }

        @Override
        public void updateProgress(long curr, long max) {
            long delta = curr - lastCurr;
            if (delta <= 0) {
                return;
            }

            stats.processedSizeCounter.addAndGet(delta);
            stats.internetUsageCounter.addAndGet(delta);
            lastCurr = curr;
        }
    }
}