/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.proxy.json;

import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.json.Json;
import javax.json.JsonObject;

/**
 * Abstract package content representation that supports JSON transformation.
 *
 * <p>PERFORMANCE OPTIMIZATION: Uses regex-based string replacement instead of
 * JSON patch operations. For packages with many versions (100+), this is
 * 10-100x faster than the previous O(n²) JSON patch approach.</p>
 *
 * @since 0.1
 */
public abstract class TransformedContent {
    /**
     * Original package content.
     */
    private final String data;

    /**
     * Pattern to match tarball URLs in JSON.
     * Matches: "tarball":"https://..." or "tarball": "https://..."
     * Captures the URL for transformation.
     */
    private static final Pattern TARBALL_PATTERN = Pattern.compile(
        "(\"tarball\"\\s*:\\s*\")([^\"]+)(\")"
    );

    /**
     * Ctor.
     * @param data Package content to be transformed
     */
    public TransformedContent(final String data) {
        this.data = data;
    }

    /**
     * Returns transformed package content as JsonObject.
     * @return Transformed package content
     */
    public JsonObject value() {
        return this.transformAssetRefs();
    }

    /**
     * Returns transformed package content as String.
     * This is more efficient when you only need the string output,
     * as it avoids an extra JSON parse/serialize cycle.
     * @return Transformed package content as string
     */
    public String valueString() {
        return this.transformAssetRefsString();
    }

    /**
     * Transforms asset references.
     * @param ref Original asset reference
     * @return Transformed asset reference
     */
    abstract String transformRef(String ref);

    /**
     * Transforms package JSON using efficient string replacement.
     * This is O(n) where n is the string length, instead of O(v*p)
     * where v is versions and p is patch operations.
     * @return Transformed JSON as string
     */
    private String transformAssetRefsString() {
        final Matcher matcher = TARBALL_PATTERN.matcher(this.data);
        final StringBuilder result = new StringBuilder(this.data.length() + 1024);
        int lastEnd = 0;
        while (matcher.find()) {
            // Append everything before this match
            result.append(this.data, lastEnd, matcher.start());
            // Append transformed: "tarball":" + transformedUrl + "
            result.append(matcher.group(1));
            result.append(this.transformRef(matcher.group(2)));
            result.append(matcher.group(3));
            lastEnd = matcher.end();
        }
        // Append remainder
        result.append(this.data, lastEnd, this.data.length());
        return result.toString();
    }

    /**
     * Transforms package JSON and returns as JsonObject.
     * @return Transformed JSON
     */
    private JsonObject transformAssetRefs() {
        final String transformed = this.transformAssetRefsString();
        return Json.createReader(new StringReader(transformed)).readObject();
    }
}
