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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.error.InvalidManifestException;
import com.auto1.pantera.http.log.EcsLogger;
import com.google.common.base.Strings;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Image manifest in JSON format.
 */
public final class Manifest {

    /**
     * New image manifest format (schemaVersion = 2).
     */
    public static final String MANIFEST_SCHEMA2 = "application/vnd.docker.distribution.manifest.v2+json";

    /**
     * Image Manifest OCI Specification.
     */
    public static final String MANIFEST_OCI_V1 = "application/vnd.oci.image.manifest.v1+json";

    /**
     * Docker manifest list media type (schemaVersion = 2).
     */
    public static final String MANIFEST_LIST_SCHEMA2 = "application/vnd.docker.distribution.manifest.list.v2+json";

    /**
     * OCI image index media type.
     */
    public static final String MANIFEST_OCI_INDEX = "application/vnd.oci.image.index.v1+json";

    /**
     * Manifest digest.
     */
    private final Digest manifestDigest;

    /**
     * JSON bytes.
     */
    private final byte[] source;

    private final JsonObject json;

    /**
     * @param manifestDigest Manifest digest.
     * @param source JSON bytes.
     */
    public Manifest(final Digest manifestDigest, final byte[] source) {
        this.manifestDigest = manifestDigest;
        this.source = Arrays.copyOf(source, source.length);
        this.json = readJson(this.source);
    }

    private static JsonObject readJson(final byte[] data) {
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(data))) {
            return reader.readObject();
        } catch (JsonException e){
            throw new InvalidManifestException("JSON reading error", e);
        }
    }

    /**
     * The MIME type of the manifest.
     *
     * @return The MIME type.
     */
    public String mediaType() {
        String res = this.json.getString("mediaType", null);
        if (Strings.isNullOrEmpty(res)) {
            res = this.inferMediaType();
        }
        if (Strings.isNullOrEmpty(res)) {
            throw new InvalidManifestException(
                "Cannot determine mediaType: field absent and unrecognizable structure"
            );
        }
        return res;
    }

    /**
     * Infer media type from manifest structure when the mediaType field is absent.
     * Per the OCI Image Spec, the mediaType field is OPTIONAL. DHI and OCI-compliant
     * registries often omit it.
     *
     * @return Inferred media type, or null if structure is unrecognizable.
     */
    private String inferMediaType() {
        if (this.json.containsKey("manifests")) {
            return MANIFEST_OCI_INDEX;
        }
        if (this.json.containsKey("config") && this.json.containsKey("layers")) {
            return MANIFEST_OCI_V1;
        }
        return null;
    }

    /**
     * Read config digest.
     *
     * @return Config digests.
     */
    public Digest config() {
        JsonObject config = this.json.getJsonObject("config");
        if (config == null) {
            throw new InvalidManifestException("Required field `config` is absent");
        }
        return new Digest.FromString(config.getString("digest"));
    }

    /**
     * Read layer digests.
     *
     * @return Layer digests.
     */
    public Collection<ManifestLayer> layers() {
        JsonArray array = this.json.getJsonArray("layers");
        if (array == null) {
            if (this.isManifestList()) {
                return Collections.emptyList();
            }
            throw new InvalidManifestException("Required field `layers` is absent");
        }
        return array.getValuesAs(JsonValue::asJsonObject)
                .stream()
                .map(ManifestLayer::new)
                .collect(Collectors.toList());
    }

    /**
     * Indicates whether manifest is a manifest list or OCI index (multi-platform).
     *
     * @return {@code true} when manifest represents a list/index document.
     */
    public boolean isManifestList() {
        final String media = this.json.getString("mediaType", "");
        return MANIFEST_LIST_SCHEMA2.equals(media)
            || MANIFEST_OCI_INDEX.equals(media)
            || (media.isEmpty() && this.json.containsKey("manifests"));
    }

    /**
     * Get child manifest digests from a manifest list (fat manifest).
     * For multi-platform images, this returns the digests of platform-specific manifests.
     *
     * <p>This enables proper caching of multi-arch images by allowing the cache
     * to fetch and store each platform-specific manifest and its associated blobs.</p>
     *
     * @return Collection of child manifest digests, empty if not a manifest list
     */
    public Collection<Digest> manifestListChildren() {
        if (!this.isManifestList()) {
            return Collections.emptyList();
        }
        final JsonArray manifests = this.json.getJsonArray("manifests");
        if (manifests == null) {
            return Collections.emptyList();
        }
        return manifests.getValuesAs(JsonValue::asJsonObject)
            .stream()
            .map(obj -> obj.getString("digest", null))
            .filter(digest -> digest != null && !digest.isEmpty())
            .map(Digest.FromString::new)
            .collect(Collectors.toList());
    }

    /**
     * OCI 1.1 {@code subject} descriptor digest — present when this manifest
     * refers to another manifest (a signature, SBOM, or other attachment
     * pushed via {@code oras attach} / {@code cosign} OCI-mode). Absent for
     * an ordinary image manifest.
     *
     * @return Subject digest, or empty if the {@code subject} field is absent.
     */
    public Optional<Digest> subject() {
        final JsonObject subj = this.json.getJsonObject("subject");
        if (subj == null) {
            return Optional.empty();
        }
        final String digest = subj.getString("digest", null);
        if (Strings.isNullOrEmpty(digest)) {
            return Optional.empty();
        }
        return Optional.of(new Digest.FromString(digest));
    }

    /**
     * OCI 1.1 {@code artifactType} — identifies the type of artifact this
     * manifest represents (e.g. a cosign signature, an SBOM). Falls back to
     * the {@code config.mediaType} per the OCI referrers algorithm when the
     * top-level field is absent, since most referrer producers (cosign,
     * oras) set one or the other.
     *
     * @return Artifact type, or empty when neither is present.
     */
    public Optional<String> artifactType() {
        final String direct = this.json.getString("artifactType", null);
        if (!Strings.isNullOrEmpty(direct)) {
            return Optional.of(direct);
        }
        final JsonObject config = this.json.getJsonObject("config");
        if (config != null) {
            final String configType = config.getString("mediaType", null);
            if (!Strings.isNullOrEmpty(configType)) {
                return Optional.of(configType);
            }
        }
        return Optional.empty();
    }

    /**
     * Top-level manifest {@code annotations} — copied verbatim into the
     * referrer descriptor served by {@code GET .../referrers/<digest>} so
     * consumers (e.g. cosign) can inspect signature metadata without
     * fetching the full referring manifest.
     *
     * @return Annotations map, or empty when absent.
     */
    public Optional<Map<String, String>> annotations() {
        final JsonObject fields = this.json.getJsonObject("annotations");
        if (fields == null || fields.isEmpty()) {
            return Optional.empty();
        }
        final Map<String, String> result = new LinkedHashMap<>();
        for (final String key : fields.keySet()) {
            result.put(key, fields.getString(key, ""));
        }
        return Optional.of(Collections.unmodifiableMap(result));
    }

    /**
     * Manifest digest.
     *
     * @return Digest.
     */
    public Digest digest() {
        return this.manifestDigest;
    }

    /**
     * Read manifest binary content.
     *
     * @return Manifest binary content.
     */
    public Content content() {
        return new Content.From(this.source);
    }

    /**
     * Manifest size.
     *
     * @return Size of the manifest.
     */
    public long size() {
        long size = this.source.length;
        EcsLogger.debug("com.auto1.pantera.docker")
            .message("Manifest size calculated")
            .eventCategory("web")
            .eventAction("manifest_size")
            .field("package.checksum", this.manifestDigest.string())
            .field("package.size", size)
            .field("log.source", "application")
            .log();
        return size;
    }
}
