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

import com.auto1.pantera.asto.Content;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The cooldown verdict on a packument, read from its filtered
 * {@link PackumentSkeleton}: which versions and {@code time} entries
 * survive, and the {@code dist-tags} to serve.
 *
 * <p>Survivors are an allow-list: a version present in the stored document
 * but absent from the skeleton (the document was refreshed after the
 * skeleton was cached) is withheld until the next evaluation, never served
 * unevaluated.</p>
 *
 * @since 2.2.11
 */
public final class PackumentDecision {

    /**
     * Mapper.
     */
    private static final ObjectMapper MAPPER = PackumentJson.SKELETON;

    /**
     * Filtered skeleton.
     */
    private final JsonNode skeleton;

    /**
     * Ctor.
     *
     * @param skeleton Filtered skeleton bytes
     */
    public PackumentDecision(final byte[] skeleton) {
        this(PackumentDecision.parse(skeleton));
    }

    /**
     * Ctor.
     *
     * @param skeleton Filtered skeleton
     */
    public PackumentDecision(final JsonNode skeleton) {
        this.skeleton = skeleton;
    }

    /**
     * Whether a version survives.
     *
     * @param version Version
     * @return True when the version may be served
     */
    public boolean allows(final String version) {
        final JsonNode versions = this.skeleton.get("versions");
        return versions != null && versions.has(version);
    }

    /**
     * Resolve a version or dist-tag against the surviving versions.
     *
     * @param ref Version or dist-tag
     * @return Surviving version it names
     */
    public Optional<String> resolve(final String ref) {
        Optional<String> result = Optional.empty();
        if (this.allows(ref)) {
            result = Optional.of(ref);
        } else {
            final JsonNode tags = this.skeleton.get("dist-tags");
            if (tags != null && tags.has(ref) && this.allows(tags.get(ref).asText())) {
                result = Optional.of(tags.get(ref).asText());
            }
        }
        return result;
    }

    /**
     * Digest of the verdict that does not depend on the member order of the
     * document the skeleton was extracted from: the stored {@code meta.json}
     * ({@code name, dist-tags, versions, time}) and the abbreviated document
     * ({@code name, modified, time, dist-tags, versions}) give the same
     * verdict the same digest, whichever of them filled the shared verdict
     * cache. Object members are hashed sorted by key, so the digest changes
     * exactly when a surviving version, a {@code time} entry, a dist-tag or
     * the name does.
     *
     * @return SHA-256 hex of the canonical skeleton
     */
    public String fingerprint() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator gen = MAPPER.getFactory().createGenerator(out)) {
            PackumentDecision.canonical(gen, this.skeleton);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new MetadataETag(out.toByteArray()).calculate();
    }

    /**
     * Streamed packument with cooldown applied: withheld versions and their
     * {@code time} entries dropped (every other {@code time} entry is served
     * with its stored value, also when that is not a string),
     * {@code dist-tags} replaced by the filtered ones, relative tarball URLs
     * prefixed.
     *
     * @param packument Stored packument
     * @param prefix Tarball URL prefix
     * @return Filtered body
     */
    public Content apply(final Content packument, final String prefix) {
        final Map<String, PackumentTransform.Section> sections = new HashMap<>();
        final Map<String, Predicate<String>> keep = new HashMap<>();
        final Map<String, JsonNode> replacements = new HashMap<>();
        sections.put("versions", PackumentTransform.Section.MEMBERS);
        keep.put("versions", this::allows);
        final JsonNode time = this.skeleton.get("time");
        if (time != null && time.isObject()) {
            sections.put("time", PackumentTransform.Section.MEMBERS);
            keep.put("time", time::has);
        }
        final JsonNode tags = this.skeleton.get("dist-tags");
        if (tags != null && tags.isObject()) {
            sections.put("dist-tags", PackumentTransform.Section.REPLACE);
            replacements.put("dist-tags", tags);
        }
        return new PackumentTransform(
            packument,
            new PackumentTransform.Rules(
                sections, PackumentTransform.Section.COPY, keep, replacements,
                Optional.of(prefix)
            )
        );
    }

    /**
     * Write a tree with object members sorted by key. Recursion is bounded:
     * a skeleton is at most three levels deep, and the {@link PackumentJson#SKELETON}
     * generator refuses anything deeper than its nesting limit.
     *
     * @param gen Generator
     * @param node Tree
     * @throws IOException On a generator failure
     */
    private static void canonical(final JsonGenerator gen, final JsonNode node) throws IOException {
        if (node.isObject()) {
            final List<String> names = new ArrayList<>(node.size());
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            gen.writeStartObject();
            for (final String name : names) {
                gen.writeFieldName(name);
                PackumentDecision.canonical(gen, node.get(name));
            }
            gen.writeEndObject();
        } else if (node.isArray()) {
            gen.writeStartArray();
            for (final JsonNode element : node) {
                PackumentDecision.canonical(gen, element);
            }
            gen.writeEndArray();
        } else {
            MAPPER.writeTree(gen, node);
        }
    }

    private static JsonNode parse(final byte[] bytes) {
        try {
            return MAPPER.readTree(bytes);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
