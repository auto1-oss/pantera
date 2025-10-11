/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer.cli;

import java.io.PrintStream;
import java.text.DecimalFormat;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Console progress renderer (aggregate across all uploads).
 */
final class ConsoleProgress implements AutoCloseable {

    private final long totalBytes;
    private final int totalTasks;

    private final AtomicLong uploadedBytes;
    private final AtomicInteger completedTasks;

    private final ScheduledExecutorService scheduler;
    private volatile boolean running;

    ConsoleProgress(final long totalBytes, final int totalTasks) {
        this.totalBytes = Math.max(0L, totalBytes);
        this.totalTasks = Math.max(0, totalTasks);
        this.uploadedBytes = new AtomicLong(0L);
        this.completedTasks = new AtomicInteger(0);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "import-progress");
            t.setDaemon(true);
            return t;
        });
        this.running = false;
    }

    void start() {
        if (this.totalTasks == 0 || this.totalBytes == 0L) {
            return; // nothing to show
        }
        this.running = true;
        this.scheduler.scheduleAtFixedRate(this::render, 0L, 250L, TimeUnit.MILLISECONDS);
    }

    void addUploaded(final long delta) {
        if (delta > 0) {
            this.uploadedBytes.addAndGet(delta);
        }
    }

    void markTaskDone() {
        this.completedTasks.incrementAndGet();
    }

    private void render() {
        if (!this.running) {
            return;
        }
        final long uploaded = Math.min(this.uploadedBytes.get(), this.totalBytes);
        final int done = Math.min(this.completedTasks.get(), this.totalTasks);
        final double pct = this.totalBytes == 0 ? 100.0 : (uploaded * 100.0 / this.totalBytes);
        final String human = humanBytes(uploaded) + " / " + humanBytes(this.totalBytes);
        final String bar = progressBar(pct, 30);
        final String line = String.format("\r[%s] %5.1f%%  %s  | tasks %d/%d", bar, pct, human, done, this.totalTasks);
        final PrintStream out = System.out;
        out.print(line);
        out.flush();
    }

    private static String humanBytes(final long bytes) {
        final String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        double val = bytes;
        int idx = 0;
        while (val >= 1024 && idx < units.length - 1) {
            val /= 1024.0;
            idx += 1;
        }
        return new DecimalFormat("0.0").format(val) + " " + units[idx];
    }

    private static String progressBar(final double pct, final int width) {
        final int filled = (int)Math.round((pct / 100.0) * width);
        final StringBuilder sb = new StringBuilder(width);
        for (int i = 0; i < width; i++) {
            sb.append(i < filled ? '=' : ' ');
        }
        return sb.toString();
    }

    void stop() {
        this.running = false;
        this.scheduler.shutdownNow();
        // Print final 100% line if everything completed
        if (this.completedTasks.get() >= this.totalTasks && this.totalTasks > 0 && this.totalBytes > 0) {
            this.uploadedBytes.set(this.totalBytes);
            render();
            System.out.println();
        } else {
            System.out.println();
        }
    }

    @Override
    public void close() {
        stop();
    }
}
