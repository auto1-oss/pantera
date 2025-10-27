/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer;

import com.artipie.asto.Storage;
import com.artipie.composer.ComposerImportMerge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * Logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ComposerImportPostProcessor.class);

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
        LOG.info(
            "Starting Composer import post-processing for repository: {}",
            this.repoName
        );
        
        final ComposerImportMerge merge = new ComposerImportMerge(
            this.storage,
            this.baseUrl
        );
        
        return merge.mergeAll()
            .whenComplete((result, error) -> {
                if (error != null) {
                    LOG.error(
                        "Composer import merge failed for {}: {}",
                        this.repoName,
                        error.getMessage(),
                        error
                    );
                } else if (result.failedPackages > 0) {
                    LOG.warn(
                        "Composer import merge completed with errors for {}: {}",
                        this.repoName,
                        result
                    );
                } else {
                    LOG.info(
                        "Composer import merge completed successfully for {}: merged {} packages ({} versions)",
                        this.repoName,
                        result.mergedPackages,
                        result.mergedVersions
                    );
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
