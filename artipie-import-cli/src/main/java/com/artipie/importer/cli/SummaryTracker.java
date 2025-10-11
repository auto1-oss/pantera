/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class SummaryTracker {

    private final ConcurrentHashMap<String, RepoStats> stats;

    SummaryTracker() {
        this.stats = new ConcurrentHashMap<>();
    }

    void markSuccess(final String repo, final boolean already) {
        this.stats.computeIfAbsent(repo, RepoStats::new).markSuccess(already);
    }

    void markFailure(final String repo) {
        this.stats.computeIfAbsent(repo, RepoStats::new).failures.incrementAndGet();
    }

    void markQuarantine(final String repo) {
        this.stats.computeIfAbsent(repo, RepoStats::new).quarantined.incrementAndGet();
    }

    void markEnumerated(final String repo) {
        this.stats.computeIfAbsent(repo, RepoStats::new).enumerated.incrementAndGet();
    }

    long totalFailures() {
        return this.stats.values().stream().mapToLong(stat -> stat.failures.get() + stat.quarantined.get()).sum();
    }

    void writeReport(final Path target) throws IOException {
        final StringBuilder json = new StringBuilder();
        json.append('{');
        json.append("\"totalSuccess\":").append(totalSuccess()).append(',');
        json.append("\"totalAlready\":").append(totalAlready()).append(',');
        json.append("\"totalFailures\":").append(totalFailures()).append(',');
        json.append("\"repos\":{");
        boolean first = true;
        for (Map.Entry<String, RepoStats> entry : this.stats.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            final RepoStats stat = entry.getValue();
            json.append('"').append(entry.getKey()).append('"').append(':');
            json.append('{')
                .append("\"success\":").append(stat.success.get()).append(',')
                .append("\"already\":").append(stat.already.get()).append(',')
                .append("\"failures\":").append(stat.failures.get()).append(',')
                .append("\"quarantined\":").append(stat.quarantined.get()).append(',')
                .append("\"enumerated\":").append(stat.enumerated.get())
                .append('}');
        }
        json.append('}').append('}');
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, json.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Render a console table with totals and per-repository stats.
     * Columns: Repository | Success | Already | Failures | Quarantined | Enumerated | Total
     * @return Formatted table as a string
     */
    String renderTable() {
        final String[] headers = {
            "Repository", "Success", "Already", "Failures", "Quarantined", "Enumerated", "Total"
        };
        // Snapshot and sort repos by name
        final java.util.List<Map.Entry<String, RepoStats>> entries = new java.util.ArrayList<>(this.stats.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        long sumSucc = 0L, sumAlr = 0L, sumFail = 0L, sumQuar = 0L, sumEnum = 0L;
        for (RepoStats s : this.stats.values()) {
            sumSucc += s.success.get();
            sumAlr += s.already.get();
            sumFail += s.failures.get();
            sumQuar += s.quarantined.get();
            sumEnum += s.enumerated.get();
        }

        int wRepo = headers[0].length();
        int wSucc = headers[1].length();
        int wAlr = headers[2].length();
        int wFail = headers[3].length();
        int wQuar = headers[4].length();
        int wEnum = headers[5].length();
        int wTot = headers[6].length();

        for (final Map.Entry<String, RepoStats> e : entries) {
            final RepoStats s = e.getValue();
            final long succ = s.success.get();
            final long alr = s.already.get();
            final long fail = s.failures.get();
            final long quar = s.quarantined.get();
            final long enm = s.enumerated.get();
            final long tot = succ + alr + fail + quar;
            wRepo = Math.max(wRepo, e.getKey().length());
            wSucc = Math.max(wSucc, Long.toString(succ).length());
            wAlr = Math.max(wAlr, Long.toString(alr).length());
            wFail = Math.max(wFail, Long.toString(fail).length());
            wQuar = Math.max(wQuar, Long.toString(quar).length());
            wEnum = Math.max(wEnum, Long.toString(enm).length());
            wTot = Math.max(wTot, Long.toString(tot).length());
        }
        // Totals row label
        wRepo = Math.max(wRepo, "TOTAL".length());
        // Totals widths
        wSucc = Math.max(wSucc, Long.toString(sumSucc).length());
        wAlr = Math.max(wAlr, Long.toString(sumAlr).length());
        wFail = Math.max(wFail, Long.toString(sumFail).length());
        wQuar = Math.max(wQuar, Long.toString(sumQuar).length());
        wEnum = Math.max(wEnum, Long.toString(sumEnum).length());
        wTot = Math.max(wTot, Long.toString(sumSucc + sumAlr + sumFail + sumQuar).length());

        final StringBuilder sb = new StringBuilder();
        // Header
        sb.append('|')
            .append(' ').append(pad(headers[0], wRepo)).append(' ')
            .append('|').append(' ').append(pad(headers[1], wSucc)).append(' ')
            .append('|').append(' ').append(pad(headers[2], wAlr)).append(' ')
            .append('|').append(' ').append(pad(headers[3], wFail)).append(' ')
            .append('|').append(' ').append(pad(headers[4], wQuar)).append(' ')
            .append('|').append(' ').append(pad(headers[5], wEnum)).append(' ')
            .append('|').append(' ').append(pad(headers[6], wTot)).append(' ')
            .append('|').append('\n');
        // Separator
        final String sep = "+" + repeat('-', wRepo + 2)
            + "+" + repeat('-', wSucc + 2)
            + "+" + repeat('-', wAlr + 2)
            + "+" + repeat('-', wFail + 2)
            + "+" + repeat('-', wQuar + 2)
            + "+" + repeat('-', wEnum + 2)
            + "+" + repeat('-', wTot + 2)
            + "+\n";
        sb.append(sep);
        // Rows
        for (final Map.Entry<String, RepoStats> e : entries) {
            final RepoStats s = e.getValue();
            final long succ = s.success.get();
            final long alr = s.already.get();
            final long fail = s.failures.get();
            final long quar = s.quarantined.get();
            final long enm = s.enumerated.get();
            final long tot = succ + alr + fail + quar;
            sb.append('|').append(' ').append(pad(e.getKey(), wRepo)).append(' ')
                .append('|').append(' ').append(padLeft(Long.toString(succ), wSucc)).append(' ')
                .append('|').append(' ').append(padLeft(Long.toString(alr), wAlr)).append(' ')
                .append('|').append(' ').append(padLeft(Long.toString(fail), wFail)).append(' ')
                .append('|').append(' ').append(padLeft(Long.toString(quar), wQuar)).append(' ')
                .append('|').append(' ').append(padLeft(Long.toString(enm), wEnum)).append(' ')
                .append('|').append(' ').append(padLeft(Long.toString(tot), wTot)).append(' ')
                .append('|').append('\n');
        }
        // Totals row
        sb.append(sep);
        final long totAll = sumSucc + sumAlr + sumFail + sumQuar;
        sb.append('|').append(' ').append(pad("TOTAL", wRepo)).append(' ')
            .append('|').append(' ').append(padLeft(Long.toString(sumSucc), wSucc)).append(' ')
            .append('|').append(' ').append(padLeft(Long.toString(sumAlr), wAlr)).append(' ')
            .append('|').append(' ').append(padLeft(Long.toString(sumFail), wFail)).append(' ')
            .append('|').append(' ').append(padLeft(Long.toString(sumQuar), wQuar)).append(' ')
            .append('|').append(' ').append(padLeft(Long.toString(sumEnum), wEnum)).append(' ')
            .append('|').append(' ').append(padLeft(Long.toString(totAll), wTot)).append(' ')
            .append('|').append('\n');
        return sb.toString();
    }

    private static String pad(final String s, final int width) {
        if (s.length() >= width) {
            return s;
        }
        final StringBuilder b = new StringBuilder(width);
        b.append(s);
        while (b.length() < width) {
            b.append(' ');
        }
        return b.toString();
    }

    private static String padLeft(final String s, final int width) {
        if (s.length() >= width) {
            return s;
        }
        final StringBuilder b = new StringBuilder(width);
        while (b.length() < width - s.length()) {
            b.append(' ');
        }
        b.append(s);
        return b.toString();
    }

    private static String repeat(final char ch, final int count) {
        final StringBuilder b = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            b.append(ch);
        }
        return b.toString();
    }

    private long totalSuccess() {
        return this.stats.values().stream().mapToLong(stat -> stat.success.get()).sum();
    }

    private long totalAlready() {
        return this.stats.values().stream().mapToLong(stat -> stat.already.get()).sum();
    }

    private static final class RepoStats {
        private final String repo;
        private final AtomicLong success;
        private final AtomicLong already;
        private final AtomicLong failures;
        private final AtomicLong quarantined;
        private final AtomicLong enumerated;

        RepoStats(final String repo) {
            this.repo = repo;
            this.success = new AtomicLong();
            this.already = new AtomicLong();
            this.failures = new AtomicLong();
            this.quarantined = new AtomicLong();
            this.enumerated = new AtomicLong();
        }

        void markSuccess(final boolean already) {
            if (already) {
                this.already.incrementAndGet();
            } else {
                this.success.incrementAndGet();
            }
        }
    }
}
