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
package com.auto1.pantera.backfill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the {@code .pantera-meta.json} sidecar file stored by
 * {@code CachedArtifactMetadataStore} alongside cached proxy artifacts
 * (Maven, PyPI).
 *
 * <p>Sidecar format:</p>
 * <pre>
 * {
 *   "size": 12345,
 *   "headers": [
 *     {"name": "Last-Modified", "value": "Mon, 05 Jul 2021 10:08:46 GMT"},
 *     ...
 *   ],
 *   "digests": {...}
 * }
 * </pre>
 *
 * <p>The {@code Last-Modified} value is the HTTP response header from the
 * upstream registry, which is the artifact publish date — the source of
 * {@code release_date} in production (via
 * {@code MavenProxyPackageProcessor.releaseMillis()}).</p>
 *
 * @since 1.20.13
 */
final class PanteraMetaSidecar {

    /**
     * Sidecar file suffix appended to the artifact path.
     */
    static final String SUFFIX = ".pantera-meta.json";

    /**
     * Logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(PanteraMetaSidecar.class);

    /**
     * Private ctor — utility class, not instantiated.
     */
    private PanteraMetaSidecar() {
    }

    /**
     * Read the release date (epoch millis) from the
     * {@code .pantera-meta.json} sidecar alongside the given artifact.
     *
     * <p>Returns empty if the sidecar is absent, the {@code headers}
     * array is missing, no {@code Last-Modified} entry is present, or
     * the date value cannot be parsed as RFC 1123.</p>
     *
     * @param artifactPath Path to the artifact file
     * @return Optional epoch millis, empty if sidecar is absent or
     *     unparseable
     */
    static Optional<Long> readReleaseDate(final Path artifactPath) {
        final Path sidecar = artifactPath.getParent()
            .resolve(artifactPath.getFileName().toString() + SUFFIX);
        if (!Files.isRegularFile(sidecar)) {
            return Optional.empty();
        }
        try (InputStream input = Files.newInputStream(sidecar);
            JsonReader reader = Json.createReader(input)) {
            final JsonObject json = reader.readObject();
            if (!json.containsKey("headers")
                || json.isNull("headers")
                || json.get("headers").getValueType()
                != JsonValue.ValueType.ARRAY) {
                return Optional.empty();
            }
            final JsonArray headers = json.getJsonArray("headers");
            for (final JsonValue entry : headers) {
                if (entry.getValueType() != JsonValue.ValueType.OBJECT) {
                    continue;
                }
                final JsonObject obj = (JsonObject) entry;
                final JsonValue nameVal = obj.get("name");
                final JsonValue valueVal = obj.get("value");
                if (nameVal == null
                    || nameVal.getValueType() != JsonValue.ValueType.STRING
                    || valueVal == null
                    || valueVal.getValueType()
                    != JsonValue.ValueType.STRING) {
                    continue;
                }
                if ("Last-Modified".equalsIgnoreCase(
                    ((JsonString) nameVal).getString())) {
                    final String lm = ((JsonString) valueVal).getString();
                    try {
                        return Optional.of(
                            Instant.from(
                                DateTimeFormatter.RFC_1123_DATE_TIME.parse(lm)
                            ).toEpochMilli()
                        );
                    } catch (final DateTimeParseException ex) {
                        LOG.debug(
                            "Cannot parse Last-Modified '{}' in {}: {}",
                            lm, sidecar, ex.getMessage()
                        );
                        return Optional.empty();
                    }
                }
            }
        } catch (final IOException | JsonException ex) {
            LOG.debug(
                "Cannot read pantera-meta sidecar {}: {}",
                sidecar, ex.getMessage()
            );
        }
        return Optional.empty();
    }
}
