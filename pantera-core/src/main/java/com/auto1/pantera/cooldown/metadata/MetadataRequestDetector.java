/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cooldown.metadata;

import java.util.Optional;

/**
 * Detects whether an HTTP request path is a metadata request (vs artifact download).
 * Each adapter implements this interface to identify metadata endpoints for its package format.
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>NPM: {@code /lodash} is metadata, {@code /lodash/-/lodash-4.17.21.tgz} is artifact</li>
 *   <li>Maven: {@code .../maven-metadata.xml} is metadata, {@code .../artifact-1.0.jar} is artifact</li>
 *   <li>PyPI: {@code /simple/requests/} is metadata</li>
 *   <li>Go: {@code /@v/list} is metadata</li>
 * </ul>
 *
 * @since 1.0
 */
public interface MetadataRequestDetector {

    /**
     * Check if the given request path is a metadata request.
     *
     * @param path Request path (e.g., "/lodash", "/simple/requests/")
     * @return {@code true} if this is a metadata request, {@code false} if artifact download
     */
    boolean isMetadataRequest(String path);

    /**
     * Extract package name from a metadata request path.
     *
     * @param path Request path
     * @return Package name if this is a metadata request, empty otherwise
     */
    Optional<String> extractPackageName(String path);

    /**
     * Get the repository type this detector handles.
     *
     * @return Repository type identifier (e.g., "npm", "maven", "pypi")
     */
    String repoType();
}
