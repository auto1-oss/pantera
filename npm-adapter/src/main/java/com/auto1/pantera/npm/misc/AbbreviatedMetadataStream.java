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
package com.auto1.pantera.npm.misc;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Streaming counterpart of {@link AbbreviatedMetadata#generate()} for
 * packuments too large to hold as a DOM.
 *
 * <p>Reads the full packument several times from a re-openable source with a
 * Jackson token parser (one validating scan, then one pass per copied
 * section) and writes the abbreviated document with a token generator:
 * {@code name}, {@code modified}, {@code time}, {@code dist-tags},
 * {@code versions} trimmed to {@link AbbreviatedMetadata#ESSENTIAL_FIELDS}
 * in that field order — the same logical content and order as the DOM
 * implementation. Memory is one parser buffer plus the essential fields of
 * one version; strings outside the kept fields (readmes) are skipped
 * without being materialised.</p>
 *
 * <p>Fails (throws) exactly where the DOM implementation throws for a
 * well-formed document: a root that is not an object, a non-string
 * {@code name} or {@code time.modified}, a non-object {@code time},
 * {@code dist-tags}, {@code versions} or version entry. Bytes differ from
 * {@code JsonObject.toString()} (escaping, number spelling), the JSON value
 * does not.</p>
 *
 * <p>Blocking: never call it on an event loop.</p>
 *
 * @since 2.2.11
 */
public final class AbbreviatedMetadataStream {

    /**
     * Parser/generator factory without Jackson's default size and depth
     * guards: the DOM implementation had none, and an upstream packument
     * that parsed before must still parse.
     */
    private static final JsonFactory FACTORY = JsonFactory.builder()
        .streamReadConstraints(
            StreamReadConstraints.builder()
                .maxStringLength(Integer.MAX_VALUE)
                .maxNumberLength(Integer.MAX_VALUE)
                .maxNameLength(Integer.MAX_VALUE)
                .maxNestingDepth(Integer.MAX_VALUE)
                .build()
        )
        .streamWriteConstraints(
            StreamWriteConstraints.builder().maxNestingDepth(Integer.MAX_VALUE).build()
        )
        .build();

    /**
     * Version fields kept, for membership tests.
     */
    private static final Set<String> ESSENTIAL = Set.of(AbbreviatedMetadata.ESSENTIAL_FIELDS);

    /**
     * Top-level key of the publish timestamps.
     */
    private static final String TIME = "time";

    /**
     * Top-level key of the dist-tags.
     */
    private static final String TAGS = "dist-tags";

    /**
     * Top-level key of the versions.
     */
    private static final String VERSIONS = "versions";

    /**
     * Full packument, opened once per pass.
     */
    private final Callable<InputStream> source;

    /**
     * Ctor.
     *
     * @param source Opens a fresh stream over the full packument; called
     *  several times
     */
    public AbbreviatedMetadataStream(final Callable<InputStream> source) {
        this.source = source;
    }

    /**
     * Write the abbreviated packument. The target is flushed, not closed.
     *
     * @param out Target
     * @throws Exception When the packument cannot be read or has a shape the
     *  DOM implementation rejects
     */
    public void writeTo(final OutputStream out) throws Exception {
        final Summary summary = this.scan();
        try (JsonGenerator gen = FACTORY.createGenerator(out, JsonEncoding.UTF8)) {
            gen.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            gen.writeStartObject();
            if (summary.name != null) {
                gen.writeStringField("name", summary.name);
            }
            gen.writeStringField(
                "modified",
                summary.modified == null ? Instant.now().toString() : summary.modified
            );
            this.section(gen, AbbreviatedMetadataStream.TIME, summary.time);
            this.section(gen, AbbreviatedMetadataStream.TAGS, summary.tags);
            this.section(gen, AbbreviatedMetadataStream.VERSIONS, summary.versions);
            gen.writeEndObject();
        }
    }

    /**
     * Validating pass: the top-level values the output is derived from.
     *
     * @return Summary
     * @throws Exception On unreadable or rejected input
     */
    private Summary scan() throws Exception {
        final Summary summary = new Summary();
        try (InputStream input = this.source.call(); JsonParser parser = FACTORY.createParser(input)) {
            AbbreviatedMetadataStream.require(
                parser.nextToken() == JsonToken.START_OBJECT, "packument is not a JSON object"
            );
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                final String key = parser.currentName();
                final JsonToken token = parser.nextToken();
                if ("name".equals(key)) {
                    AbbreviatedMetadataStream.require(token == JsonToken.VALUE_STRING, "name");
                    summary.name = parser.getText();
                } else if (AbbreviatedMetadataStream.TIME.equals(key)) {
                    AbbreviatedMetadataStream.require(token == JsonToken.START_OBJECT, key);
                    summary.time += 1;
                    summary.modified = AbbreviatedMetadataStream.modified(parser);
                } else if (AbbreviatedMetadataStream.TAGS.equals(key)) {
                    AbbreviatedMetadataStream.require(token == JsonToken.START_OBJECT, key);
                    summary.tags += 1;
                    parser.skipChildren();
                } else if (AbbreviatedMetadataStream.VERSIONS.equals(key)) {
                    AbbreviatedMetadataStream.require(token == JsonToken.START_OBJECT, key);
                    summary.versions += 1;
                    AbbreviatedMetadataStream.checkVersions(parser);
                } else {
                    parser.skipChildren();
                }
            }
        }
        return summary;
    }

    /**
     * Copy one top-level section into the output, if the packument has it.
     * With duplicate keys the last occurrence wins, as in a DOM.
     *
     * @param gen Output
     * @param key Top-level key
     * @param occurrences How many times the scan saw the key
     * @throws Exception On unreadable input
     */
    private void section(final JsonGenerator gen, final String key, final int occurrences)
        throws Exception {
        if (occurrences == 0) {
            return;
        }
        try (InputStream input = this.source.call(); JsonParser parser = FACTORY.createParser(input)) {
            parser.nextToken();
            int seen = 0;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                final boolean wanted = key.equals(parser.currentName());
                parser.nextToken();
                if (wanted) {
                    seen += 1;
                }
                if (wanted && seen == occurrences) {
                    gen.writeFieldName(key);
                    if (AbbreviatedMetadataStream.VERSIONS.equals(key)) {
                        AbbreviatedMetadataStream.versions(parser, gen);
                    } else {
                        AbbreviatedMetadataStream.copy(parser, gen);
                    }
                    break;
                }
                parser.skipChildren();
            }
        }
    }

    /**
     * Read {@code time.modified}; the parser is on the {@code time} object.
     *
     * @param parser Parser
     * @return Modified timestamp or null when absent
     * @throws IOException On a non-string value or unreadable input
     */
    private static String modified(final JsonParser parser) throws IOException {
        String result = null;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            final boolean wanted = "modified".equals(parser.currentName());
            final JsonToken token = parser.nextToken();
            if (wanted) {
                AbbreviatedMetadataStream.require(token == JsonToken.VALUE_STRING, "time.modified");
                result = parser.getText();
            } else {
                parser.skipChildren();
            }
        }
        return result;
    }

    /**
     * Check every version entry is an object; the parser is on
     * {@code versions}.
     *
     * @param parser Parser
     * @throws IOException On a non-object entry or unreadable input
     */
    private static void checkVersions(final JsonParser parser) throws IOException {
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            AbbreviatedMetadataStream.require(
                parser.nextToken() == JsonToken.START_OBJECT, "versions entry"
            );
            parser.skipChildren();
        }
    }

    /**
     * Write the abbreviated versions object; the parser is on {@code versions}.
     *
     * @param parser Parser
     * @param gen Output
     * @throws IOException On unreadable input
     */
    private static void versions(final JsonParser parser, final JsonGenerator gen)
        throws IOException {
        gen.writeStartObject();
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            gen.writeFieldName(parser.currentName());
            parser.nextToken();
            AbbreviatedMetadataStream.version(parser, gen);
        }
        gen.writeEndObject();
    }

    /**
     * Write one version trimmed to the essential fields, in
     * {@link AbbreviatedMetadata#ESSENTIAL_FIELDS} order; the parser is on
     * the version object.
     *
     * @param parser Parser
     * @param gen Output
     * @throws IOException On unreadable input
     */
    private static void version(final JsonParser parser, final JsonGenerator gen)
        throws IOException {
        final Map<String, String> kept = new HashMap<>();
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            final String field = parser.currentName();
            parser.nextToken();
            if (AbbreviatedMetadataStream.ESSENTIAL.contains(field)) {
                final StringWriter raw = new StringWriter();
                try (JsonGenerator sub = FACTORY.createGenerator(raw)) {
                    AbbreviatedMetadataStream.copy(parser, sub);
                }
                kept.put(field, raw.toString());
            } else {
                parser.skipChildren();
            }
        }
        gen.writeStartObject();
        for (final String field : AbbreviatedMetadata.ESSENTIAL_FIELDS) {
            final String value = kept.get(field);
            if (value != null) {
                gen.writeFieldName(field);
                gen.writeRawValue(value);
            }
        }
        gen.writeEndObject();
    }

    /**
     * Copy the value the parser is on, keeping numbers as spelled.
     *
     * @param parser Parser on the first token of the value
     * @param gen Output
     * @throws IOException On unreadable input
     */
    private static void copy(final JsonParser parser, final JsonGenerator gen)
        throws IOException {
        int depth = 0;
        JsonToken token = parser.currentToken();
        do {
            if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                depth += 1;
            } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                depth -= 1;
            }
            if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                gen.writeNumber(parser.getText());
            } else {
                gen.copyCurrentEvent(parser);
            }
        } while (depth > 0 && (token = parser.nextToken()) != null);
    }

    /**
     * Reject a packument shape the DOM implementation cannot abbreviate.
     *
     * @param condition Expected condition
     * @param what What was checked
     * @throws IOException When the condition does not hold
     */
    private static void require(final boolean condition, final String what) throws IOException {
        if (!condition) {
            throw new IOException("Unexpected packument shape: " + what);
        }
    }

    /**
     * Result of the validating scan.
     */
    private static final class Summary {

        /**
         * Last top-level {@code name}, null when absent.
         */
        private String name;

        /**
         * {@code modified} of the last {@code time} object, null when absent.
         */
        private String modified;

        /**
         * Occurrences of {@code time}.
         */
        private int time;

        /**
         * Occurrences of {@code dist-tags}.
         */
        private int tags;

        /**
         * Occurrences of {@code versions}.
         */
        private int versions;
    }
}
