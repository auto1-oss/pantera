/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import java.util.LinkedHashMap;

/**
 * Adapter-specific metadata merger for group repositories.
 * Each package format implements this interface to merge metadata
 * responses from multiple group members into a single response.
 *
 * <p>The merge operation preserves priority order - entries earlier
 * in the LinkedHashMap have higher priority and win in case of conflicts.
 *
 * @since 1.18.0
 */
@FunctionalInterface
public interface MetadataMerger {

    /**
     * Merge metadata from multiple group members.
     *
     * @param responses Map of member name to metadata bytes, in priority order.
     *                  Earlier entries have higher priority.
     * @return Merged metadata bytes
     */
    byte[] merge(LinkedHashMap<String, byte[]> responses);
}
