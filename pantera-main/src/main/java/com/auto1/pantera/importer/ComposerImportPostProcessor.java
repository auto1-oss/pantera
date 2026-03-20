/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.importer;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.composer.ComposerImportMerge;
import com.auto1.pantera.http.log.EcsLogger;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Post-processor for Composer imports.
 * 
 * <p>After bulk import completes, this merges staged versions into final p2/ layout.</p>
 * 
 * <p>Usage:</p>
 * <pre>
 * // After ImportService completes batch import:
 * final ComposerImportPostProcessor postProcessor = 
 *     new ComposerImportPostProcessor(storage, repoName, baseUrl);
 * 
 * postProcessor.process()
 *     .thenAccept(result -> LOG.info("Composer import merged: {}", result));
 * </pre>
 * 
 * @since 1.18.14
 */
public final class ComposerImportPostProcessor {

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Repository base URL.
     */
    private final Optional<String> baseUrl;

    /**
     * Ctor.
     * 
     * @param storage Storage
     * @param repoName Repository name
     * @param baseUrl Base URL for repository
     */
    public ComposerImportPostProcessor(
        final Storage storage,
        final String repoName,
        final Optional<String> baseUrl
    ) {
        this.storage = storage;
        this.repoName = repoName;
        this.baseUrl = baseUrl;
    }

    /**
     * Process Composer import - merge staged versions into final layout.
     * 
     * @return Completion stage with merge result
     */
    public CompletionStage<ComposerImportMerge.MergeResult> process() {
        EcsLogger.info("com.auto1.pantera.importer")
            .message("Starting Composer import post-processing")
            .eventCategory("repository")
            .eventAction("import_post_process")
            .field("repository.name", this.repoName)
            .log();

        final ComposerImportMerge merge = new ComposerImportMerge(
            this.storage,
            this.baseUrl
        );

        return merge.mergeAll()
            .whenComplete((result, error) -> {
                if (error != null) {
                    EcsLogger.error("com.auto1.pantera.importer")
                        .message("Composer import merge failed")
                        .eventCategory("repository")
                        .eventAction("import_post_process")
                        .eventOutcome("failure")
                        .field("repository.name", this.repoName)
                        .error(error)
                        .log();
                } else if (result.failedPackages > 0) {
                    EcsLogger.warn("com.auto1.pantera.importer")
                        .message("Composer import merge completed with errors (" + result.mergedPackages + " packages, " + result.mergedVersions + " versions merged, " + result.failedPackages + " failed)")
                        .eventCategory("repository")
                        .eventAction("import_post_process")
                        .eventOutcome("partial_failure")
                        .field("repository.name", this.repoName)
                        .log();
                } else {
                    EcsLogger.info("com.auto1.pantera.importer")
                        .message("Composer import merge completed successfully (" + result.mergedPackages + " packages, " + result.mergedVersions + " versions merged)")
                        .eventCategory("repository")
                        .eventAction("import_post_process")
                        .eventOutcome("success")
                        .field("repository.name", this.repoName)
                        .log();
                }
            });
    }

    /**
     * Check if repository needs Composer post-processing.
     * 
     * @param repoType Repository type from import request
     * @return True if Composer/PHP repository
     */
    public static boolean needsProcessing(final String repoType) {
        if (repoType == null) {
            return false;
        }
        final String type = repoType.toLowerCase();
        return type.equals("php") || type.equals("composer");
    }
}
