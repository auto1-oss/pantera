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
package com.auto1.pantera.maven.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maven metadata parser implementing cooldown SPI.
 * Parses {@code maven-metadata.xml} via DOM and extracts version information.
 *
 * <p>Maven metadata structure:</p>
 * <pre>
 * &lt;metadata&gt;
 *   &lt;groupId&gt;com.example&lt;/groupId&gt;
 *   &lt;artifactId&gt;my-lib&lt;/artifactId&gt;
 *   &lt;versioning&gt;
 *     &lt;latest&gt;3.0.0&lt;/latest&gt;
 *     &lt;release&gt;3.0.0&lt;/release&gt;
 *     &lt;versions&gt;
 *       &lt;version&gt;1.0.0&lt;/version&gt;
 *       &lt;version&gt;2.0.0&lt;/version&gt;
 *     &lt;/versions&gt;
 *     &lt;lastUpdated&gt;20260401120000&lt;/lastUpdated&gt;
 *   &lt;/versioning&gt;
 * &lt;/metadata&gt;
 * </pre>
 *
 * @since 2.2.0
 */
public final class MavenMetadataParser implements MetadataParser<Document> {

    /**
     * Content type for Maven metadata.
     */
    private static final String CONTENT_TYPE = "application/xml";

    @Override
    public Document parse(final byte[] bytes) {
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", true
            );
            final DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (final SAXException | IOException | ParserConfigurationException ex) {
            throw new MetadataParseException(
                "Failed to parse Maven metadata XML", ex
            );
        }
    }

    @Override
    public List<String> extractVersions(final Document metadata) {
        final NodeList versionNodes = metadata.getElementsByTagName("version");
        if (versionNodes.getLength() == 0) {
            return Collections.emptyList();
        }
        final List<String> result = new ArrayList<>(versionNodes.getLength());
        for (int idx = 0; idx < versionNodes.getLength(); idx++) {
            final String text = versionNodes.item(idx).getTextContent();
            if (text != null && !text.isBlank()) {
                result.add(text.trim());
            }
        }
        return result;
    }

    @Override
    public Optional<String> getLatestVersion(final Document metadata) {
        final NodeList latestNodes = metadata.getElementsByTagName("latest");
        if (latestNodes.getLength() > 0) {
            final String text = latestNodes.item(0).getTextContent();
            if (text != null && !text.isBlank()) {
                return Optional.of(text.trim());
            }
        }
        return Optional.empty();
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public Map<String, Instant> extractReleaseDates(final Document metadata) {
        return Map.of();
    }
}
