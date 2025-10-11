/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SummaryTrackerTest {

    @TempDir
    Path temp;

    @Test
    void writesJsonReport() throws Exception {
        final SummaryTracker tracker = new SummaryTracker();
        tracker.markSuccess("repo", false);
        tracker.markQuarantine("repo");
        tracker.markFailure("repo");
        tracker.markEnumerated("repo");
        final Path report = this.temp.resolve("report.json");
        tracker.writeReport(report);
        final String json = Files.readString(report);
        Assertions.assertTrue(json.contains("totalSuccess"));
        Assertions.assertTrue(json.contains("totalFailures"));
    }
}
