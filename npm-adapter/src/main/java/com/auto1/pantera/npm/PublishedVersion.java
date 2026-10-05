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
package com.auto1.pantera.npm;

import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;

/**
 * The single version an npm publish payload writes. Every write of a
 * publish — the per-version metadata file, the registry signature, the
 * provenance/attestation bundle, the tarball name check and the published
 * artifact event — derives the version from this class, so the immutable
 * guard and the write path can never disagree about it.
 *
 * <p>The target is the top-level {@code version}, else
 * {@code dist-tags.latest}, else the first key of {@code versions}. A
 * payload that carries a {@code versions} object without an entry for that
 * target is inconsistent (it declares one version while carrying another's
 * metadata) and is refused by {@link #validated()}. A real
 * {@code npm}/{@code yarn}/{@code pnpm} publish carries exactly one entry in
 * {@code versions} and points its dist-tag at it, so it is always
 * consistent.</p>
 *
 * @since 2.2.10
 */
public final class PublishedVersion {

    /**
     * Versions field.
     */
    private static final String VERSIONS = "versions";

    /**
     * Publish payload.
     */
    private final JsonObject json;

    /**
     * Ctor.
     * @param json Publish payload
     */
    public PublishedVersion(final JsonObject json) {
        this.json = json;
    }

    /**
     * The target version, without consistency checks.
     * @return Version, or {@code null} when the payload declares none
     */
    public String value() {
        String version = PublishedVersion.string(this.json.get("version"));
        if (version == null) {
            final JsonValue tags = this.json.get("dist-tags");
            if (tags instanceof JsonObject) {
                version = PublishedVersion.string(((JsonObject) tags).get("latest"));
            }
        }
        if (version == null) {
            final JsonValue versions = this.json.get(PublishedVersion.VERSIONS);
            if (versions instanceof JsonObject && !((JsonObject) versions).isEmpty()) {
                version = ((JsonObject) versions).keySet().iterator().next();
            }
        }
        return version;
    }

    /**
     * The target version of a consistent payload.
     * @return Version
     * @throws InvalidPublishException When the payload declares no version,
     *  or its {@code versions} object carries no entry for the declared one
     */
    public String validated() {
        final String version = this.value();
        if (version == null || version.isBlank()) {
            throw new InvalidPublishException("publish payload declares no version");
        }
        final JsonValue versions = this.json.get(PublishedVersion.VERSIONS);
        if (versions != null && versions.getValueType() != JsonValue.ValueType.NULL) {
            if (!(versions instanceof JsonObject)) {
                throw new InvalidPublishException("publish payload 'versions' is not an object");
            }
            final JsonObject entries = (JsonObject) versions;
            if (!entries.isEmpty() && !entries.containsKey(version)) {
                throw new InvalidPublishException(
                    String.format(
                        "publish payload targets version %s but carries metadata of %s",
                        version, entries.keySet()
                    )
                );
            }
        }
        return version;
    }

    /**
     * String content of a JSON value.
     * @param value Value, may be null
     * @return String, or null when the value is not a JSON string
     */
    private static String string(final JsonValue value) {
        final String res;
        if (value instanceof JsonString) {
            res = ((JsonString) value).getString();
        } else {
            res = null;
        }
        return res;
    }
}
