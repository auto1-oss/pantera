/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.maven.metadata;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.trace.TraceContextExecutor;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Merges multiple Maven metadata XML files from group members.
 * Implements Nexus-style metadata merging for correct version resolution.
 *
 * <p>Uses SAX parser for streaming XML parsing to minimize memory usage.
 * Memory usage: ~10-50 KB per operation (vs ~50-200 KB with DOM parser).
 *
 * <p>Concurrency safety:
 * <ul>
 *   <li>Dedicated thread pool: Isolates merging from other async operations</li>
 *   <li>Semaphore rate limiting: Max 250 concurrent operations (safe for high load)</li>
 *   <li>Thread-safe: No shared mutable state</li>
 *   <li>No resource leaks: All operations in-memory</li>
 * </ul>
 *
 * <p>Merging rules:
 * <ul>
 *   <li>Versions: Union of all versions from all members</li>
 *   <li>Latest: Highest version (semantically) across all members</li>
 *   <li>Release: Highest non-SNAPSHOT version across all members</li>
 *   <li>Plugins: Union of all plugins</li>
 *   <li>GroupId/ArtifactId: Must match, taken from first member</li>
 *   <li>LastUpdated: Current timestamp</li>
 * </ul>
 *
 * @since 1.0
 */
public final class MetadataMerger {

    /**
     * Pool name for metrics identification.
     */
    public static final String POOL_NAME = "artipie.maven.merger";

    /**
     * Dedicated thread pool for metadata merging operations.
     * Sized to half of available processors to avoid saturating the system.
     * Pool name: {@value #POOL_NAME} (visible in thread dumps and metrics).
     * Wrapped with TraceContextExecutor to propagate MDC (trace.id, user, etc.) to merge threads.
     */
    private static final ExecutorService MERGE_EXECUTOR = TraceContextExecutor.wrap(
        Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors() / 2),
            new ThreadFactoryBuilder()
                .setNameFormat(POOL_NAME + ".worker-%d")
                .setDaemon(true)
                .build()
        )
    );

    /**
     * Semaphore for rate limiting concurrent merge operations.
     * Limit: 250 concurrent operations (safe with SAX parser's low memory footprint).
     * Higher than 100 because SAX uses ~10-50 KB vs DOM's ~50-200 KB per operation.
     */
    private static final Semaphore MERGE_SEMAPHORE = new Semaphore(250);

    /**
     * List of metadata contents to merge.
     */
    private final List<byte[]> metadataContents;

    /**
     * Constructor.
     * @param metadataContents List of metadata XML contents as byte arrays
     */
    public MetadataMerger(final List<byte[]> metadataContents) {
        this.metadataContents = metadataContents;
    }

    /**
     * Merge all metadata files into a single result.
     *
     * <p>Uses SAX parser for streaming XML parsing (low memory usage).
     * Uses dedicated thread pool to avoid ForkJoinPool.commonPool() contention.
     * Uses semaphore for rate limiting to prevent resource exhaustion.
     *
     * @return CompletableFuture with merged metadata as Content
     */
    @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.NPathComplexity", "PMD.AvoidCatchingGenericException"})
    public CompletableFuture<Content> merge() {
        // Rate limiting: Try to acquire semaphore permit
        if (!MERGE_SEMAPHORE.tryAcquire()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException(
                    "Too many concurrent metadata merge operations (limit: 250). "
                    + "This protects system stability under extreme load."
                )
            );
        }

        // Execute on dedicated thread pool (not ForkJoinPool.commonPool())
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (metadataContents.isEmpty()) {
                    throw new IllegalArgumentException("No metadata to merge");
                }

                if (metadataContents.size() == 1) {
                    // Single metadata - no merging needed
                    return new Content.From(metadataContents.get(0));
                }

                // Parse all metadata files using SAX parser (streaming, low memory)
                final SAXParserFactory factory = SAXParserFactory.newInstance();
                final SAXParser parser = factory.newSAXParser();

                // Collect data from all metadata files
                final Set<String> allVersions = new TreeSet<>(new VersionComparator());
                final Set<Plugin> allPlugins = new TreeSet<>();
                String groupId = null;
                String artifactId = null;
                String version = null;
                boolean isGroupLevelMetadata = false;

                for (byte[] content : metadataContents) {
                    final MetadataHandler handler = new MetadataHandler();
                    parser.parse(new ByteArrayInputStream(content), handler);

                    // Get metadata from first document
                    if (groupId == null) {
                        groupId = handler.groupId;
                        artifactId = handler.artifactId;
                        version = handler.version;
                        isGroupLevelMetadata = !handler.plugins.isEmpty();
                    }

                    // Collect versions and plugins from all documents
                    allVersions.addAll(handler.versions);
                    allPlugins.addAll(handler.plugins);
                }

                // Compute latest and release versions
                final String latest = allVersions.stream()
                    .max(new VersionComparator())
                    .orElse(null);

                final String release = allVersions.stream()
                    .filter(v -> !v.contains("SNAPSHOT"))
                    .max(new VersionComparator())
                    .orElse(null);

                // Generate merged XML using streaming writer (low memory)
                final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                final Writer writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);

                writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                writer.write("<metadata>\n");

                // Write groupId (always present)
                if (groupId != null) {
                    writer.write("  <groupId>");
                    writer.write(escapeXml(groupId));
                    writer.write("</groupId>\n");
                }

                // Write artifactId and version (only for artifact-level metadata)
                if (!isGroupLevelMetadata) {
                    if (artifactId != null) {
                        writer.write("  <artifactId>");
                        writer.write(escapeXml(artifactId));
                        writer.write("</artifactId>\n");
                    }
                    if (version != null) {
                        writer.write("  <version>");
                        writer.write(escapeXml(version));
                        writer.write("</version>\n");
                    }
                }

                // Write versioning section (only for artifact-level metadata)
                if (!isGroupLevelMetadata && !allVersions.isEmpty()) {
                    writer.write("  <versioning>\n");

                    if (latest != null) {
                        writer.write("    <latest>");
                        writer.write(escapeXml(latest));
                        writer.write("</latest>\n");
                    }

                    if (release != null) {
                        writer.write("    <release>");
                        writer.write(escapeXml(release));
                        writer.write("</release>\n");
                    }

                    writer.write("    <versions>\n");
                    for (String ver : allVersions) {
                        writer.write("      <version>");
                        writer.write(escapeXml(ver));
                        writer.write("</version>\n");
                    }
                    writer.write("    </versions>\n");

                    writer.write("    <lastUpdated>");
                    writer.write(MavenTimestamp.now());
                    writer.write("</lastUpdated>\n");

                    writer.write("  </versioning>\n");
                }

                // Write plugins section (for group-level metadata or if plugins exist)
                if (!allPlugins.isEmpty()) {
                    final String indent = isGroupLevelMetadata ? "  " : "    ";
                    writer.write(indent);
                    writer.write("<plugins>\n");

                    for (Plugin plugin : allPlugins) {
                        writer.write(indent);
                        writer.write("  <plugin>\n");

                        writer.write(indent);
                        writer.write("    <prefix>");
                        writer.write(escapeXml(plugin.prefix));
                        writer.write("</prefix>\n");

                        writer.write(indent);
                        writer.write("    <artifactId>");
                        writer.write(escapeXml(plugin.artifactId));
                        writer.write("</artifactId>\n");

                        if (plugin.name != null && !plugin.name.isEmpty()) {
                            writer.write(indent);
                            writer.write("    <name>");
                            writer.write(escapeXml(plugin.name));
                            writer.write("</name>\n");
                        }

                        writer.write(indent);
                        writer.write("  </plugin>\n");
                    }

                    writer.write(indent);
                    writer.write("</plugins>\n");
                }

                writer.write("</metadata>\n");
                writer.flush();

                return new Content.From(outputStream.toByteArray());

            } catch (Exception e) {
                throw new IllegalStateException("Failed to merge metadata", e);
            } finally {
                // CRITICAL: Always release semaphore permit
                MERGE_SEMAPHORE.release();
            }
        }, MERGE_EXECUTOR);  // Use dedicated thread pool
    }

    /**
     * Escape XML special characters.
     * @param text Text to escape
     * @return Escaped text
     */
    private String escapeXml(final String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    /**
     * SAX handler for parsing Maven metadata XML files.
     * Extracts versions, plugins, groupId, artifactId, and version.
     * Uses streaming parsing for low memory usage.
     *
     * <p>PMD suppressions:
     * <ul>
     *   <li>AvoidStringBufferField: currentText is intentionally a field for SAX parsing state</li>
     *   <li>NullAssignment: Null assignments are intentional for resetting plugin parsing state</li>
     *   <li>CognitiveComplexity: endElement() is complex due to XML structure handling</li>
     *   <li>CyclomaticComplexity: endElement() has multiple branches for different XML elements</li>
     * </ul>
     */
    @SuppressWarnings({
        "PMD.AvoidStringBufferField",
        "PMD.NullAssignment",
        "PMD.CognitiveComplexity",
        "PMD.CyclomaticComplexity"
    })
    private static class MetadataHandler extends DefaultHandler {
        private final Set<String> versions = new TreeSet<>(new VersionComparator());
        private final Set<Plugin> plugins = new TreeSet<>();
        private String groupId;
        private String artifactId;
        private String version;

        // Current parsing state
        private String currentElement;
        private final StringBuilder currentText = new StringBuilder();
        private String currentPluginPrefix;
        private String currentPluginArtifactId;
        private String currentPluginName;
        private boolean inPlugin;
        private boolean inVersionsSection;

        @Override
        public void startElement(
            final String uri,
            final String localName,
            final String qName,
            final Attributes attributes
        ) throws SAXException {
            this.currentElement = qName;
            this.currentText.setLength(0);

            if ("plugin".equals(qName)) {
                this.inPlugin = true;
                this.currentPluginPrefix = null;
                this.currentPluginArtifactId = null;
                this.currentPluginName = null;
            } else if ("versions".equals(qName)) {
                this.inVersionsSection = true;
            }
        }

        @Override
        public void endElement(
            final String uri,
            final String localName,
            final String qName
        ) throws SAXException {
            final String text = this.currentText.toString().trim();

            if ("groupId".equals(qName) && this.groupId == null) {
                this.groupId = text;
            } else if ("artifactId".equals(qName) && !this.inPlugin && this.artifactId == null) {
                this.artifactId = text;
            } else if ("version".equals(qName) && !this.inPlugin && !this.inVersionsSection && this.version == null) {
                this.version = text;
            } else if ("version".equals(qName) && this.inVersionsSection && !text.isEmpty()) {
                this.versions.add(text);
            } else if ("plugin".equals(qName)) {
                if (this.currentPluginPrefix != null && this.currentPluginArtifactId != null) {
                    this.plugins.add(new Plugin(
                        this.currentPluginPrefix,
                        this.currentPluginArtifactId,
                        this.currentPluginName
                    ));
                }
                this.inPlugin = false;
            } else if ("prefix".equals(qName) && this.inPlugin) {
                this.currentPluginPrefix = text;
            } else if ("artifactId".equals(qName) && this.inPlugin) {
                this.currentPluginArtifactId = text;
            } else if ("name".equals(qName) && this.inPlugin) {
                this.currentPluginName = text;
            } else if ("versions".equals(qName)) {
                this.inVersionsSection = false;
            }

            this.currentElement = null;
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) {
            if (this.currentElement != null) {
                this.currentText.append(ch, start, length);
            }
        }
    }

    /**
     * Plugin data structure.
     * Implements Comparable for sorted output.
     */
    private static class Plugin implements Comparable<Plugin> {
        private final String prefix;
        private final String artifactId;
        private final String name;

        Plugin(final String prefix, final String artifactId, final String name) {
            this.prefix = prefix;
            this.artifactId = artifactId;
            this.name = name;
        }

        @Override
        public int compareTo(final Plugin other) {
            int cmp = this.prefix.compareTo(other.prefix);
            if (cmp != 0) {
                return cmp;
            }
            cmp = this.artifactId.compareTo(other.artifactId);
            if (cmp != 0) {
                return cmp;
            }
            if (this.name == null && other.name == null) {
                return 0;
            }
            if (this.name == null) {
                return -1;
            }
            if (other.name == null) {
                return 1;
            }
            return this.name.compareTo(other.name);
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Plugin)) {
                return false;
            }
            final Plugin other = (Plugin) obj;
            return this.prefix.equals(other.prefix)
                && this.artifactId.equals(other.artifactId)
                && (this.name == null ? other.name == null : this.name.equals(other.name));
        }

        @Override
        public int hashCode() {
            int result = prefix.hashCode();
            result = 31 * result + artifactId.hashCode();
            result = 31 * result + (name != null ? name.hashCode() : 0);
            return result;
        }
    }

    /**
     * Comparator for Maven versions (semantic versioning with SNAPSHOT support).
     */
    private static class VersionComparator implements Comparator<String> {
        @Override
        public int compare(final String v1, final String v2) {
            return new Version(v1).compareTo(new Version(v2));
        }
    }
}
