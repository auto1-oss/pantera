/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.composer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Expands Composer v2 minified package metadata ({@code "minified":
 * "composer/2.0"}, as packagist serves every {@code /p2/} file).
 *
 * <p>In a minified version array each entry carries only the keys that
 * changed from the previous entry; a key whose value is {@code "__unset"}
 * drops the inherited value. The entries are therefore not independent:
 * removing or reordering one (a cooldown filter dropping a blocked version,
 * a merge re-keying the array by version) silently strips fields such as
 * {@code name}, {@code require} or {@code dist} from the entries that
 * inherited them. Expanding first makes every entry self-contained. The
 * semantics mirror Composer's own {@code MetadataMinifier::expand}: start
 * from the previous expanded entry, overlay the current entry's keys, drop
 * the keys set to {@code "__unset"}.</p>
 *
 * <p>An expanded document is served as-is, without the {@code minified}
 * marker: Composer only expands when the marker is present and reads
 * unmarked version arrays verbatim.</p>
 *
 * @since 2.2.9
 */
public final class MinifiedMetadata {

    /**
     * Top-level key naming the minification scheme.
     */
    private static final String MARKER_KEY = "minified";

    /**
     * The only minification scheme Composer defines.
     */
    private static final String MARKER = "composer/2.0";

    /**
     * Value that removes an inherited key.
     */
    private static final String UNSET = "__unset";

    /**
     * Shared mapper (thread-safe).
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Whether the document declares composer/2.0 minification.
     *
     * @param root Parsed metadata document
     * @return True when the version arrays are minified
     */
    public boolean isMinified(final JsonNode root) {
        return root != null && root.isObject()
            && MARKER.equals(root.path(MARKER_KEY).textValue());
    }

    /**
     * Expand every version array of a minified document in place and drop
     * the marker. Documents that are not minified are returned untouched.
     *
     * @param root Parsed metadata document
     * @return The same node, expanded
     */
    public JsonNode expand(final JsonNode root) {
        if (!this.isMinified(root)) {
            return root;
        }
        final ObjectNode doc = (ObjectNode) root;
        final JsonNode packages = doc.get("packages");
        if (packages instanceof ObjectNode pkgs) {
            final List<String> names = new ArrayList<>(pkgs.size());
            pkgs.fieldNames().forEachRemaining(names::add);
            for (final String name : names) {
                final JsonNode versions = pkgs.get(name);
                if (versions instanceof ArrayNode array) {
                    pkgs.set(name, MinifiedMetadata.expandVersions(array));
                }
            }
        }
        doc.remove(MARKER_KEY);
        return doc;
    }

    /**
     * Byte-level variant of {@link #expand(JsonNode)}: returns the input
     * unchanged unless it is a minified document, in which case the
     * expanded document is re-serialised.
     *
     * @param bytes Metadata bytes
     * @return Expanded bytes, or the input when there is nothing to expand
     */
    public byte[] expandBytes(final byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return bytes;
        }
        try {
            final JsonNode root = MAPPER.readTree(bytes);
            if (!this.isMinified(root)) {
                return bytes;
            }
            return MAPPER.writeValueAsBytes(this.expand(root));
        } catch (final IOException ex) {
            // Not JSON we can read: leave it to the caller's own parser,
            // which reports the failure exactly as before.
            return bytes;
        }
    }

    /**
     * Expand one minified version array.
     *
     * @param versions Minified entries, in served order
     * @return Self-contained entries, in the same order
     */
    private static ArrayNode expandVersions(final ArrayNode versions) {
        final ArrayNode out = MAPPER.createArrayNode();
        ObjectNode current = MAPPER.createObjectNode();
        for (final JsonNode entry : versions) {
            if (!entry.isObject()) {
                out.add(entry);
                continue;
            }
            current = current.deepCopy();
            final Iterator<Map.Entry<String, JsonNode>> fields = entry.fields();
            while (fields.hasNext()) {
                final Map.Entry<String, JsonNode> field = fields.next();
                if (UNSET.equals(field.getValue().textValue())) {
                    current.remove(field.getKey());
                } else {
                    current.set(field.getKey(), field.getValue().deepCopy());
                }
            }
            out.add(current);
        }
        return out;
    }
}
