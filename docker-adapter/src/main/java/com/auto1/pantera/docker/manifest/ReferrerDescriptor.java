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
package com.auto1.pantera.docker.manifest;

import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.error.InvalidManifestException;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * OCI descriptor for a single referrer entry — the record persisted per
 * referrer under a subject's referrers index (see {@link Referrers}) and
 * the shape assembled into the served OCI Image Index {@code manifests[]}
 * array.
 *
 * <p>Wraps the raw {@link JsonObject} rather than flattening every field
 * into record components, mirroring {@link ManifestLayer}.
 */
public record ReferrerDescriptor(JsonObject json) {

    /**
     * Builds the descriptor for a manifest that carries a {@code subject} —
     * this is the entry written to the referrers index on push.
     *
     * @param manifest Referring manifest (the one being pushed).
     * @return Descriptor for {@code manifest}.
     */
    public static ReferrerDescriptor of(final Manifest manifest) {
        final JsonObjectBuilder builder = Json.createObjectBuilder()
            .add("mediaType", manifest.mediaType())
            .add("digest", manifest.digest().string())
            .add("size", manifest.size());
        manifest.artifactType().ifPresent(type -> builder.add("artifactType", type));
        manifest.annotations().filter(map -> !map.isEmpty()).ifPresent(
            map -> builder.add("annotations", annotationsJson(map))
        );
        return new ReferrerDescriptor(builder.build());
    }

    /**
     * Parses a descriptor from its stored JSON bytes.
     *
     * @param bytes JSON bytes as written by {@link #toBytes()}.
     * @return Parsed descriptor.
     */
    public static ReferrerDescriptor fromBytes(final byte[] bytes) {
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(bytes))) {
            return new ReferrerDescriptor(reader.readObject());
        } catch (final JsonException ex) {
            throw new InvalidManifestException("Referrer descriptor JSON reading error", ex);
        }
    }

    private static JsonObjectBuilder annotationsJson(final Map<String, String> annotations) {
        final JsonObjectBuilder result = Json.createObjectBuilder();
        annotations.forEach(result::add);
        return result;
    }

    /**
     * Referring manifest digest.
     *
     * @return Digest.
     */
    public Digest digest() {
        return new Digest.FromString(this.json.getString("digest"));
    }

    /**
     * Referring manifest media type.
     *
     * @return Media type.
     */
    public String mediaType() {
        return this.json.getString("mediaType");
    }

    /**
     * Referring manifest size in bytes.
     *
     * @return Size, or {@code 0} when absent.
     */
    public long size() {
        final JsonNumber size = this.json.getJsonNumber("size");
        return size != null ? size.longValue() : 0L;
    }

    /**
     * Artifact type used by the {@code ?artifactType=} referrers filter.
     *
     * @return Artifact type, or empty when absent.
     */
    public Optional<String> artifactType() {
        return Optional.ofNullable(this.json.getString("artifactType", null))
            .filter(type -> !type.isEmpty());
    }

    /**
     * Serializes this descriptor to UTF-8 JSON bytes for storage.
     *
     * @return JSON bytes.
     */
    public byte[] toBytes() {
        return this.json.toString().getBytes(StandardCharsets.UTF_8);
    }
}
