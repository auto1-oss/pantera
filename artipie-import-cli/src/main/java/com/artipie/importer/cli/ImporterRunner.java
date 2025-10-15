/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer.cli;

import com.artipie.importer.api.ChecksumPolicy;
import com.artipie.importer.api.DigestType;
import com.artipie.importer.api.ImportHeaders;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ImporterRunner {

    private static final Set<String> CHECKSUM_SUFFIXES = Set.of(".md5", ".sha1", ".sha256", ".sha512");

    private final ImporterConfig config;

    ImporterRunner(final ImporterConfig config) {
        this.config = config;
    }

    int run() {
        try {
            if (!Files.isDirectory(this.config.exportDir())) {
                System.err.printf("Export dir %s does not exist or is not a directory.%n", this.config.exportDir());
                return 2;
            }
            Files.createDirectories(this.config.failuresDir());
            try (ProgressTracker progress = new ProgressTracker(this.config.progressLog(), this.config.resume());
                 FailureTracker failures = new FailureTracker(this.config.failuresDir())) {
                final SummaryTracker summary = new SummaryTracker();
                if (this.config.dryRun()) {
                    this.enumerateOnly(progress, summary);
                    summary.writeReport(this.config.report());
                    System.out.println();
                    System.out.println(summary.renderTable());
                    return 0;
                }
                // Use virtual threads for better I/O performance
                // Virtual threads are lightweight and don't block OS threads during I/O
                final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                try {
                    // Don't create shared HttpClient - each task will create its own
                    // This avoids connection pool exhaustion issues
                    final CompletionService<UploadResult> completion = new ExecutorCompletionService<>(executor);
                    final List<UploadTask> tasks = this.config.retryFailures()
                        ? collectRetryTasks(progress.completedKeys())
                        : collectTasks(progress.completedKeys());
                    final long totalBytes = tasks.stream().mapToLong(UploadTask::size).sum();
                    try (ConsoleProgress console = new ConsoleProgress(totalBytes, tasks.size())) {
                        console.start();
                        System.out.printf("Total tasks: %d, Batch size: %d, Using: Virtual Threads, Timeout: %d minutes%n",
                            tasks.size(), this.config.batchSize(), this.config.timeoutMinutes());
                        
                        // Start watchdog thread to detect stuck uploads
                        final Thread watchdog = startWatchdog(executor);
                        
                        int taskIndex = 0;
                        final int batchSize = this.config.batchSize();
                        
                        while (taskIndex < tasks.size()) {
                            // Submit next batch
                            final int batchEnd = Math.min(taskIndex + batchSize, tasks.size());
                            final int batchCount = batchEnd - taskIndex;
                            System.out.printf("%n[BATCH] Submitting tasks %d-%d (batch %d/%d)%n",
                                taskIndex + 1, batchEnd, (taskIndex / batchSize) + 1, (tasks.size() + batchSize - 1) / batchSize);
                            
                            // Track submitted futures for this batch
                            final List<Future<UploadResult>> batchFutures = new ArrayList<>(batchCount);
                            for (int i = taskIndex; i < batchEnd; i++) {
                                final UploadTask task = tasks.get(i);
                                batchFutures.add(completion.submit(uploadCallable(task, console)));
                            }
                            System.err.printf("[DEBUG] Submitted %d tasks, waiting for completion...%n", batchCount);
                            
                            // Wait for batch to complete
                            for (int i = 0; i < batchCount; i++) {
                                System.err.printf("[DEBUG] Waiting for result %d/%d...%n", i + 1, batchCount);
                                final Future<UploadResult> future = completion.take();
                                System.err.printf("[DEBUG] Got result %d/%d%n", i + 1, batchCount);
                                final UploadResult result;
                                try {
                                    // Add timeout to prevent indefinite blocking
                                    result = future.get(this.config.timeoutMinutes() + 5, TimeUnit.MINUTES);
                                } catch (final TimeoutException timeout) {
                                    System.err.printf("%n[ERROR] Task timed out after %d minutes. This may indicate a stuck upload or server issue.%n",
                                        this.config.timeoutMinutes() + 5);
                                    System.err.println("Cancelling remaining tasks in batch...");
                                    throw new RuntimeException("Upload task timed out", timeout);
                                } catch (final ExecutionException ex) {
                                    System.err.printf("%n[ERROR] Task failed with exception: %s%n", ex.getCause().getMessage());
                                    ex.getCause().printStackTrace(System.err);
                                    throw new RuntimeException("Upload task failed", ex.getCause());
                                }
                                final int currentTask = taskIndex + i + 1;
                                System.out.printf("[%d/%d] Processing: %s/%s (%.2f MB)%n",
                                    currentTask, tasks.size(),
                                    result.task.repoName(), result.task.relativePath(),
                                    result.task.size() / 1024.0 / 1024.0);
                                if (result.status == UploadStatus.SUCCESS || result.status == UploadStatus.ALREADY) {
                                    progress.markCompleted(result.task);
                                    summary.markSuccess(result.task.repoName(), result.status == UploadStatus.ALREADY);
                                    System.out.printf("[OK] %s/%s (%s)%n", result.task.repoName(), result.task.relativePath(), result.status == UploadStatus.ALREADY ? "already" : "created");
                                } else if (result.status == UploadStatus.QUARANTINED) {
                                    failures.record(result.task.repoName(), result.task.relativePath(), result.message);
                                    summary.markQuarantine(result.task.repoName());
                                    System.out.printf("[QUARANTINED] %s/%s :: %s%n", result.task.repoName(), result.task.relativePath(), result.message);
                                } else {
                                    failures.record(result.task.repoName(), result.task.relativePath(), result.message);
                                    summary.markFailure(result.task.repoName());
                                    System.out.printf("[FAIL] %s/%s :: %s%n", result.task.repoName(), result.task.relativePath(), result.message);
                                }
                            }
                            System.err.printf("[DEBUG] Batch complete, processed %d tasks%n", batchCount);
                            taskIndex = batchEnd;
                        }
                        System.err.println("[DEBUG] All batches complete!");
                        watchdog.interrupt();
                    }
                    summary.writeReport(this.config.report());
                    System.out.println();
                    System.out.println(summary.renderTable());
                    return summary.totalFailures() > 0 ? 3 : 0;
                } finally {
                    executor.shutdown();
                    executor.awaitTermination(30, TimeUnit.SECONDS);
                }
            }
        } catch (final Exception err) {
            err.printStackTrace(System.err);
            return 1;
        }
    }

    private void enumerateOnly(final ProgressTracker progress, final SummaryTracker summary) throws IOException {
        final List<UploadTask> tasks = this.config.retryFailures()
            ? collectRetryTasks(progress.completedKeys())
            : collectTasks(progress.completedKeys());
        for (final UploadTask task : tasks) {
            summary.markEnumerated(task.repoName());
        }
        summary.writeReport(this.config.report());
    }

    private List<UploadTask> collectTasks(final Set<String> completed) throws IOException {
        final List<UploadTask> tasks = new ArrayList<>();
        final TaskScanner scanner = new TaskScanner(this.config.exportDir(), this.config.owner(), this.config.checksumPolicy());
        Files.walk(this.config.exportDir())
            .filter(Files::isRegularFile)
            .filter(path -> CHECKSUM_SUFFIXES.stream().noneMatch(suffix -> path.getFileName().toString().endsWith(suffix)))
            .forEach(path -> scanner.analyze(path).ifPresent(task -> {
                if (!completed.contains(task.idempotencyKey())) {
                    tasks.add(task);
                }
            }));
        return tasks;
    }

    private List<UploadTask> collectRetryTasks(final Set<String> completed) throws IOException {
        final List<UploadTask> tasks = new ArrayList<>();
        final TaskScanner scanner = new TaskScanner(this.config.exportDir(), this.config.owner(), this.config.checksumPolicy());
        final Set<String> seen = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.config.failuresDir(), "*-failures.log")) {
            for (final Path flog : stream) {
                final String fname = flog.getFileName().toString();
                final String repo = fname.substring(0, fname.length() - "-failures.log".length());
                for (final String line : Files.readAllLines(flog, StandardCharsets.UTF_8)) {
                    final int sep = line.indexOf('|');
                    final String rel = sep >= 0 ? line.substring(0, sep) : line;
                    final String key = repo + "|" + rel;
                    if (!seen.add(key)) {
                        continue;
                    }
                    // Attempt to resolve the file under any top-level directory inside exportDir
                    boolean resolved = false;
                    try (DirectoryStream<Path> tops = Files.newDirectoryStream(this.config.exportDir())) {
                        for (final Path top : tops) {
                            if (!Files.isDirectory(top)) {
                                continue;
                            }
                            final Path candidate = top.resolve(repo).resolve(rel);
                            if (Files.isRegularFile(candidate)) {
                                scanner.analyze(candidate).ifPresent(task -> {
                                    if (!completed.contains(task.idempotencyKey())) {
                                        tasks.add(task);
                                    }
                                });
                                resolved = true;
                                break;
                            }
                        }
                    }
                    if (!resolved) {
                        System.err.printf("Could not resolve failed entry %s/%s under export-dir%n", repo, rel);
                    }
                }
            }
        }
        return tasks;
    }

    private Callable<UploadResult> uploadCallable(final UploadTask task, final ConsoleProgress console) {
        return () -> {
            // Create a dedicated HttpClient for this task to avoid connection pool contention
            final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
            System.err.printf("[DEBUG] Starting upload: %s/%s (%.2f MB)%n",
                task.repoName(), task.relativePath(), task.size() / 1024.0 / 1024.0);
            if (this.config.checksumPolicy() == ChecksumPolicy.COMPUTE) {
                task.computeDigests();
            } else if (this.config.checksumPolicy() == ChecksumPolicy.METADATA) {
                task.readMetadataChecksums();
                if (task.digests().isEmpty()) {
                    task.computeDigests();
                }
            }
            int attempt = 0;
            while (true) {
                attempt += 1;
                try {
                    System.err.printf("[DEBUG] Attempt %d: Sending request for %s%n", attempt, task.relativePath());
                    final long sendStart = System.currentTimeMillis();
                    final HttpResponse<String> response = client.send(buildRequest(task, console), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    final long sendDuration = System.currentTimeMillis() - sendStart;
                    System.err.printf("[DEBUG] Request completed in %d ms, status: %d%n", sendDuration, response.statusCode());
                    final int status = response.statusCode();
                    if (status == 201) {
                        console.markTaskDone();
                        return new UploadResult(task, UploadStatus.SUCCESS, "created");
                    }
                    if (status == 200) {
                        console.markTaskDone();
                        return new UploadResult(task, UploadStatus.ALREADY, "already present");
                    }
                    if (status == 409) {
                        final String body = response.body();
                        final String message = body == null ? "checksum mismatch" : body;
                        console.markTaskDone();
                        return new UploadResult(task, UploadStatus.QUARANTINED, message);
                    }
                    if (status >= 500 && attempt < this.config.maxRetries()) {
                        Thread.sleep(backoff(attempt));
                        continue;
                    }
                    console.markTaskDone();
                    return new UploadResult(task, UploadStatus.FAILED,
                        String.format("HTTP %d: %s", status, response.body()));
                } catch (final HttpTimeoutException timeout) {
                    System.err.printf("[DEBUG] HTTP timeout on attempt %d for %s: %s%n",
                        attempt, task.relativePath(), timeout.getMessage());
                    if (attempt >= this.config.maxRetries()) {
                        console.markTaskDone();
                        return new UploadResult(task, UploadStatus.FAILED, "Timeout: " + timeout.getMessage());
                    }
                    Thread.sleep(backoff(attempt));
                } catch (final IOException ex) {
                    System.err.printf("[DEBUG] IOException on attempt %d for %s: %s%n",
                        attempt, task.relativePath(), ex.getMessage());
                    if (attempt >= this.config.maxRetries()) {
                        console.markTaskDone();
                        return new UploadResult(task, UploadStatus.FAILED, "IOException: " + ex.getMessage());
                    }
                    Thread.sleep(backoff(attempt));
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    console.markTaskDone();
                    return new UploadResult(task, UploadStatus.FAILED, "Interrupted: " + ex.getMessage());
                } catch (final Exception ex) {
                    console.markTaskDone();
                    return new UploadResult(task, UploadStatus.FAILED, "Unexpected error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
            }
        };
    }

    private HttpRequest buildRequest(final UploadTask task, final ConsoleProgress console) throws IOException {
        final URI uri = task.buildUri(this.config.server());
        // Use 5 minutes per request timeout (not the full batch timeout)
        // Large files should complete in < 5 min, if not, likely stuck
        final HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofMinutes(5))
            .header(ImportHeaders.REPO_TYPE, task.repoType())
            .header(ImportHeaders.IDEMPOTENCY_KEY, task.idempotencyKey())
            .header(
                ImportHeaders.ARTIFACT_NAME,
                sanitizeHeaderValue(
                    "file".equals(task.repoType())
                        ? task.dotSeparatedName()
                        : task.metadata().artifact().orElse(task.derivedName())
                )
            )
            .header(ImportHeaders.ARTIFACT_VERSION, sanitizeHeaderValue(task.metadata().version().orElse("")))
            .header(ImportHeaders.ARTIFACT_OWNER, this.config.owner())
            .header(ImportHeaders.ARTIFACT_SIZE, Long.toString(task.size()))
            .header(ImportHeaders.ARTIFACT_CREATED, Long.toString(task.created()))
            .header(ImportHeaders.CHECKSUM_POLICY, this.config.checksumPolicy().name())
            .PUT(ProgressBodyPublisher.ofFile(task.file(), console));
        task.metadata().release().ifPresent(value -> builder.header(ImportHeaders.ARTIFACT_RELEASE, Long.toString(value)));
        task.digests().forEach((type, value) -> builder.header(headerName(type), value));
        this.config.username();
        if (this.config.token() != null && !this.config.token().isBlank()) {
            builder.header("Authorization", "Bearer " + this.config.token());
        } else if (this.config.username() != null && this.config.password() != null) {
            final String creds = this.config.username() + ":" + this.config.password();
            final String basic = java.util.Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + basic);
        }
        return builder.build();
    }

    private static String headerName(final DigestType type) {
        return switch (type) {
            case SHA1 -> ImportHeaders.CHECKSUM_SHA1;
            case SHA256 -> ImportHeaders.CHECKSUM_SHA256;
            case MD5 -> ImportHeaders.CHECKSUM_MD5;
        };
    }

    /**
     * Sanitize header value by removing invalid characters.
     * HTTP headers must only contain visible ASCII characters (0x20-0x7E)
     * and horizontal tabs (0x09). This method removes any other characters
     * including invisible Unicode characters like zero-width spaces.
     *
     * @param value The header value to sanitize
     * @return Sanitized header value
     */
    private static String sanitizeHeaderValue(final String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        // Remove all characters that are not visible ASCII or tab
        return value.replaceAll("[^\\x09\\x20-\\x7E]", "");
    }

    private long backoff(final int attempt) {
        final long jitter = ThreadLocalRandom.current().nextLong(50, 200);
        return (long) (this.config.backoffMs() * Math.pow(2, attempt - 1)) + jitter;
    }

    /**
     * Start a watchdog thread that monitors the executor for stuck tasks.
     * Prints thread dumps periodically to help diagnose hangs.
     */
    private Thread startWatchdog(final ExecutorService executor) {
        final Thread watchdog = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(60_000); // Check every minute
                    System.err.println("\\n[WATCHDOG] Still running... Active threads:");
                    Thread.getAllStackTraces().forEach((thread, stack) -> {
                        if (thread.getName().contains("pool") || thread.getName().contains("ForkJoin")) {
                            System.err.printf("  %s [%s]%n", thread.getName(), thread.getState());
                            if (stack.length > 0) {
                                // Show top 3 stack frames to understand what's blocking
                                for (int i = 0; i < Math.min(3, stack.length); i++) {
                                    System.err.printf("    at %s.%s(%s:%d)%n",
                                        stack[i].getClassName(),
                                        stack[i].getMethodName(),
                                        stack[i].getFileName(),
                                        stack[i].getLineNumber());
                                }
                            }
                        }
                    });
                    System.err.println("[WATCHDOG] If all threads are WAITING at Unsafe.park, the HTTP client connection pool may be exhausted.");
                    System.err.println("[WATCHDOG] Consider reducing --concurrency or checking if the server is responding.");
                }
            } catch (final InterruptedException ex) {
                // Normal shutdown
                Thread.currentThread().interrupt();
            }
        }, "import-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        return watchdog;
    }

    private static final class UploadResult {
        private final UploadTask task;
        private final UploadStatus status;
        private final String message;

        UploadResult(final UploadTask task, final UploadStatus status, final String message) {
            this.task = task;
            this.status = status;
            this.message = message;
        }
    }

    private enum UploadStatus {
        SUCCESS,
        ALREADY,
        QUARANTINED,
        FAILED
    }
}
