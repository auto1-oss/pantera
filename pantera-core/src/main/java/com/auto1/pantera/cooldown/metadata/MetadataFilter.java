/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cooldown.metadata;

import java.util.Set;

/**
 * Filters blocked versions from parsed metadata.
 * Each adapter implements this to remove blocked versions from its metadata format.
 *
 * <p>Implementations must:</p>
 * <ul>
 *   <li>Remove blocked versions from version lists/objects</li>
 *   <li>Remove associated data (timestamps, checksums, download URLs) for blocked versions</li>
 *   <li>Preserve all other metadata unchanged</li>
 * </ul>
 *
 * @param <T> Type of parsed metadata object (must match {@link MetadataParser})
 * @since 1.0
 */
public interface MetadataFilter<T> {

    /**
     * Filter blocked versions from metadata.
     * Returns a new or modified metadata object with blocked versions removed.
     *
     * @param metadata Parsed metadata object
     * @param blockedVersions Set of version strings to remove
     * @return Filtered metadata (may be same instance if mutable, or new instance)
     */
    T filter(T metadata, Set<String> blockedVersions);

    /**
     * Update the "latest" version tag in metadata.
     * Called when the current latest version is blocked and needs to be updated
     * to point to the highest unblocked version.
     *
     * @param metadata Filtered metadata object
     * @param newLatest New latest version string
     * @return Updated metadata (may be same instance if mutable, or new instance)
     */
    T updateLatest(T metadata, String newLatest);
}
