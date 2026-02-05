/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.metadata;

import com.artipie.cache.MetadataMerger;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NPM metadata merger for group repositories.
 * Merges package.json metadata from multiple NPM repositories.
 *
 * <p>Merge rules:
 * <ul>
 *   <li>versions: Combined from all members, priority wins for conflicts</li>
 *   <li>dist-tags: Combined, priority wins for conflicts</li>
 *   <li>time: All timestamps merged</li>
 *   <li>Other fields: Priority member's values used</li>
 * </ul>
 *
 * @since 1.18.0
 */
public final class NpmMetadataMerger implements MetadataMerger {

    /**
     * Jackson object mapper for JSON parsing.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Versions field name.
     */
    private static final String VERSIONS = "versions";

    /**
     * Dist-tags field name.
     */
    private static final String DIST_TAGS = "dist-tags";

    /**
     * Time field name.
     */
    private static final String TIME = "time";

    @Override
    public byte[] merge(final LinkedHashMap<String, byte[]> responses) {
        if (responses.isEmpty()) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
        try {
            final ObjectNode result = MAPPER.createObjectNode();
            final ObjectNode versions = MAPPER.createObjectNode();
            final ObjectNode distTags = MAPPER.createObjectNode();
            final ObjectNode time = MAPPER.createObjectNode();
            // Process in priority order (first entries have higher priority)
            for (final Map.Entry<String, byte[]> entry : responses.entrySet()) {
                final JsonNode root = MAPPER.readTree(entry.getValue());
                if (root.isObject()) {
                    this.mergeObject((ObjectNode) root, result, versions, distTags, time);
                }
            }
            // Add merged collections to result
            if (versions.size() > 0) {
                result.set(VERSIONS, versions);
            }
            if (distTags.size() > 0) {
                result.set(DIST_TAGS, distTags);
            }
            if (time.size() > 0) {
                result.set(TIME, time);
            }
            return MAPPER.writeValueAsBytes(result);
        } catch (final IOException ex) {
            throw new IllegalArgumentException("Failed to merge NPM metadata", ex);
        }
    }

    /**
     * Merge a single member's metadata into the result.
     *
     * @param source Source object from member
     * @param result Result object to merge into
     * @param versions Accumulated versions object
     * @param distTags Accumulated dist-tags object
     * @param time Accumulated time object
     */
    private void mergeObject(
        final ObjectNode source,
        final ObjectNode result,
        final ObjectNode versions,
        final ObjectNode distTags,
        final ObjectNode time
    ) {
        final Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            final Map.Entry<String, JsonNode> field = fields.next();
            final String name = field.getKey();
            final JsonNode value = field.getValue();
            if (VERSIONS.equals(name) && value.isObject()) {
                this.mergeWithPriority(versions, (ObjectNode) value);
            } else if (DIST_TAGS.equals(name) && value.isObject()) {
                this.mergeWithPriority(distTags, (ObjectNode) value);
            } else if (TIME.equals(name) && value.isObject()) {
                this.mergeWithPriority(time, (ObjectNode) value);
            } else if (!result.has(name)) {
                // Other fields: priority member (first to set) wins
                result.set(name, value);
            }
        }
    }

    /**
     * Merge source into target, priority (existing entries) wins.
     *
     * @param target Target object (has priority)
     * @param source Source object to merge
     */
    private void mergeWithPriority(final ObjectNode target, final ObjectNode source) {
        final Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            final Map.Entry<String, JsonNode> field = fields.next();
            if (!target.has(field.getKey())) {
                target.set(field.getKey(), field.getValue());
            }
        }
    }
}
