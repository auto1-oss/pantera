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

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

/**
 * OCI 1.1 referrers listing for a subject digest: the descriptors of every
 * manifest whose {@code subject} field points at that digest, assembled
 * into the OCI Image Index served by {@code GET .../referrers/<digest>}.
 *
 * <p>Per the OCI Distribution Spec, a registry that advertises referrers
 * support MUST always answer 200 with a (possibly empty) index — never
 * 404 — so {@link #EMPTY} is a legitimate, common result: no referrers
 * indexed for the digest, or the underlying {@code Manifests}
 * implementation doesn't index referrers at all (proxy/cache/group —
 * hosted-registry-only per WS4-docker.2).
 */
public final class Referrers {

    /**
     * OCI Image Index media type.
     */
    public static final String MEDIA_TYPE = "application/vnd.oci.image.index.v1+json";

    /**
     * Empty referrers listing.
     */
    public static final Referrers EMPTY = new Referrers(List.of());

    /**
     * Referrer descriptors, in listing order.
     */
    private final List<ReferrerDescriptor> descriptors;

    /**
     * @param descriptors Referrer descriptors.
     */
    public Referrers(final Collection<ReferrerDescriptor> descriptors) {
        this.descriptors = List.copyOf(descriptors);
    }

    /**
     * Number of referrers in this listing.
     *
     * @return Referrer count.
     */
    public int size() {
        return this.descriptors.size();
    }

    /**
     * Renders this listing as an OCI Image Index.
     *
     * @return JSON content.
     */
    public Content json() {
        final JsonArrayBuilder manifests = Json.createArrayBuilder();
        this.descriptors.forEach(descriptor -> manifests.add(descriptor.json()));
        return new Content.From(
            Json.createObjectBuilder()
                .add("schemaVersion", 2)
                .add("mediaType", MEDIA_TYPE)
                .add("manifests", manifests)
                .build()
                .toString()
                .getBytes(StandardCharsets.UTF_8)
        );
    }
}
