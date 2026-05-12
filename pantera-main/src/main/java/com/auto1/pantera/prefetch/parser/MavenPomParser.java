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

    /**
     * Only POMs are parseable as Maven XML. Pre-Track-5 the dispatcher
     * routed every cached primary here — including {@code .jar},
     * {@code .war}, {@code .aar}, {@code .module} etc. — and each one
     * burned a temp-file snapshot copy + an XML parse attempt that
     * failed at byte 1 with "Unexpected character 'P'" (the ZIP magic
     * {@code PK}). One cold {@code mvn dependency:resolve -U} produced
     * 80+ identical WARNs and the matching CPU/disk waste. Filtering
     * here turns the dispatcher into a no-op for non-POM Maven writes.
     */
    @Override
    public boolean appliesTo(final String urlPath) {
        return urlPath != null && urlPath.endsWith(".pom");
    }

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
            EcsLogger.warn("com.auto1.pantera.prefetch")
                .message("Maven POM parse failed: " + ex.getMessage())
                .eventCategory("file")
                .eventAction("parse")
                .eventOutcome("failure")
                .field("file.path", bytesOnDisk.toString())
                .field("error.type", ex.getClass().getName())
                .log();
            return List.of();
        }
        return result;
    }

    /**
     * Drive the StAX state machine: walk to {@code <dependencies>}, then
     * collect each {@code <dependency>}.
     *
     * <p>Top-level {@code <dependencies>} only: {@code <dependencyManagement>}
     * subtrees are skipped via a depth counter so we don't emit managed deps
     * as if they were runtime deps. {@code <profiles>} are reached after
     * {@code <dependencies>} in the natural document order, so the parser
     * exits cleanly at EOF.</p>
     */
    private void readDependencies(final XMLStreamReader reader, final List<Coordinate> out) throws XMLStreamException {
        boolean inDependencies = false;
        int skipDepth = 0;
        DependencyAcc cur = null;
        StringBuilder text = null;
        String currentChild = null;
        while (reader.hasNext()) {
            final int event = reader.next();
            switch (event) {
                case XMLStreamConstants.START_ELEMENT -> {
                    final String name = reader.getLocalName();
                    if ("dependencyManagement".equals(name)) {
                        skipDepth++;
                    } else if (skipDepth > 0) {
                        // Inside a <dependencyManagement> subtree — ignore everything.
                    } else if ("dependencies".equals(name)) {
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
                    if ("dependencyManagement".equals(name) && skipDepth > 0) {
                        skipDepth--;
                    } else if (skipDepth > 0) {
                        // Still inside <dependencyManagement> — skip closing tags too.
                    } else if (cur != null && currentChild != null && currentChild.equals(name)) {
                        cur.set(currentChild, text == null ? "" : text.toString().trim());
                        currentChild = null;
                        text = null;
                    } else if ("dependency".equals(name) && cur != null) {
                        if (cur.shouldInclude()) {
                            out.add(Coordinate.maven(cur.groupId, cur.artifactId, cur.version));
                        }
                        cur = null;
                    } else if ("dependencies".equals(name)) {
                        inDependencies = false;
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
