/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cooldown.metadata;

import java.time.Instant;
import java.util.Map;

/**
 * Optional extension for {@link MetadataParser} implementations that can extract
 * release dates directly from metadata.
 *
 * <p>Some package formats include release timestamps in their metadata:</p>
 * <ul>
 *   <li>NPM: {@code time} object with version → ISO timestamp</li>
 *   <li>Composer: {@code time} field in version objects</li>
 * </ul>
 *
 * <p>When a parser implements this interface, the cooldown metadata service can
 * preload release dates into inspectors, avoiding additional upstream HTTP requests.</p>
 *
 * @param <T> Type of parsed metadata object (must match {@link MetadataParser})
 * @since 1.0
 */
public interface ReleaseDateProvider<T> {

    /**
     * Extract release dates from parsed metadata.
     *
     * @param metadata Parsed metadata object
     * @return Map of version string → release timestamp (may be empty, never null)
     */
    Map<String, Instant> releaseDates(T metadata);
}
