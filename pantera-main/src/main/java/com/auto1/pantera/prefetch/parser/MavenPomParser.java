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
package com.auto1.pantera.prefetch.parser;

import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.prefetch.Coordinate;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Parses a Maven {@code pom.xml} and extracts the direct, non-test,
 * non-optional dependencies as {@link Coordinate}s.
 *
 * <p>This is intentionally narrow: it does NOT resolve parent POMs,
 * {@code <dependencyManagement>}, BOM imports, profiles, or properties.
 * The dispatcher only needs a best-effort warm-up list — anything we
 * miss will be fetched on-demand by the next consumer request.</p>
 *
 * <p>StAX configured for XXE safety (no DTD, no external entities,
 * entity references not replaced).</p>
 *
 * @since 2.2.0
 */
public final class MavenPomParser implements PrefetchParser {

    /**
     * Shared, thread-safe StAX input factory hardened against XXE.
     */
    private static final XMLInputFactory INPUT_FACTORY = createInputFactory();

    @Override
    public List<Coordinate> parse(final Path bytesOnDisk) {
        final List<Coordinate> result = new ArrayList<>();
        try (InputStream in = Files.newInputStream(bytesOnDisk)) {
            final XMLStreamReader reader = INPUT_FACTORY.createXMLStreamReader(in);
            try {
                this.readDependencies(reader, result);
            } finally {
                reader.close();
            }
        } catch (final Exception ex) {
            EcsLogger.warn("com.auto1.pantera.prefetch.parser")
                .message("Failed to parse pom.xml; returning empty coordinate list")
                .eventCategory("file")
                .eventAction("parse")
                .eventOutcome("failure")
                .field("file.path", bytesOnDisk.toString())
                .field("error.message", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())
                .log();
            return List.of();
        }
        return result;
    }

    /**
     * Drive the StAX state machine: walk to {@code <dependencies>}, then
     * collect each {@code <dependency>}.
     */
    private void readDependencies(final XMLStreamReader reader, final List<Coordinate> out) throws XMLStreamException {
        boolean inDependencies = false;
        DependencyAcc cur = null;
        StringBuilder text = null;
        String currentChild = null;
        while (reader.hasNext()) {
            final int event = reader.next();
            switch (event) {
                case XMLStreamConstants.START_ELEMENT -> {
                    final String name = reader.getLocalName();
                    if ("dependencies".equals(name)) {
                        inDependencies = true;
                    } else if (inDependencies && "dependency".equals(name)) {
                        cur = new DependencyAcc();
                    } else if (cur != null && isDepField(name)) {
                        currentChild = name;
                        text = new StringBuilder();
                    }
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                    if (text != null) {
                        text.append(reader.getText());
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    final String name = reader.getLocalName();
                    if (cur != null && currentChild != null && currentChild.equals(name)) {
                        cur.set(currentChild, text == null ? "" : text.toString().trim());
                        currentChild = null;
                        text = null;
                    } else if ("dependency".equals(name) && cur != null) {
                        if (cur.shouldInclude()) {
                            out.add(Coordinate.maven(cur.groupId, cur.artifactId, cur.version));
                        }
                        cur = null;
                    } else if ("dependencies".equals(name)) {
                        // Stop after the first <dependencies> block — the top-level one.
                        // Anything deeper (e.g. inside <profile>) is ignored intentionally.
                        return;
                    }
                }
                default -> {
                    // ignore comments, PIs, whitespace etc.
                }
            }
        }
    }

    private static boolean isDepField(final String name) {
        return "groupId".equals(name)
            || "artifactId".equals(name)
            || "version".equals(name)
            || "scope".equals(name)
            || "optional".equals(name);
    }

    private static XMLInputFactory createInputFactory() {
        final XMLInputFactory factory = XMLInputFactory.newInstance();
        // XXE hardening: pom.xml is consumer-provided, so be paranoid.
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        return factory;
    }

    /**
     * Mutable accumulator for one {@code <dependency>} block while parsing.
     */
    private static final class DependencyAcc {
        private String groupId;
        private String artifactId;
        private String version;
        private String scope;
        private String optional;

        void set(final String field, final String value) {
            switch (field) {
                case "groupId" -> this.groupId = value;
                case "artifactId" -> this.artifactId = value;
                case "version" -> this.version = value;
                case "scope" -> this.scope = value;
                case "optional" -> this.optional = value;
                default -> {
                    // unreachable: caller guarantees a known field name
                }
            }
        }

        boolean shouldInclude() {
            if (this.groupId == null || this.artifactId == null || this.version == null) {
                return false;
            }
            if ("true".equalsIgnoreCase(this.optional)) {
                return false;
            }
            // Include compile + runtime + unspecified scope (which defaults to compile).
            return this.scope == null
                || "compile".equals(this.scope)
                || "runtime".equals(this.scope);
        }
    }
}
