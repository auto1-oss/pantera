/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.metadata;

import com.artipie.asto.Content;
import com.jcabi.log.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * Merges multiple Maven metadata XML files from group members.
 * Implements Nexus-style metadata merging for correct version resolution.
 * 
 * <p>PMD suppressions: merge() method is intentionally complex for comprehensive metadata merging.
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
     * @return CompletableFuture with merged metadata as Content
     */
    @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.NPathComplexity", "PMD.AvoidCatchingGenericException"})
    public CompletableFuture<Content> merge() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (metadataContents.isEmpty()) {
                    throw new IllegalArgumentException("No metadata to merge");
                }

                if (metadataContents.size() == 1) {
                    // Single metadata - no merging needed
                    return new Content.From(metadataContents.get(0));
                }

                final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                final DocumentBuilder builder = factory.newDocumentBuilder();
                
                // Parse all metadata documents
                final List<Document> documents = new ArrayList<>();
                for (byte[] content : metadataContents) {
                    documents.add(builder.parse(new ByteArrayInputStream(content)));
                }

                // Create merged document
                final Document merged = builder.newDocument();
                final Element root = merged.createElement("metadata");
                merged.appendChild(root);

                // Get groupId, artifactId, version from first document
                final Document first = documents.get(0);
                copyElement(first, merged, root, "groupId");
                copyElement(first, merged, root, "artifactId");
                copyElement(first, merged, root, "version");

                // Merge versioning section
                final Element versioning = merged.createElement("versioning");
                root.appendChild(versioning);

                // Collect all versions from all documents
                final Set<String> allVersions = new TreeSet<>(new VersionComparator());
                final Set<String> allPlugins = new TreeSet<>();
                
                for (Document doc : documents) {
                    // Extract versions
                    final NodeList versionNodes = doc.getElementsByTagName("version");
                    for (int i = 0; i < versionNodes.getLength(); i++) {
                        final String version = versionNodes.item(i).getTextContent().trim();
                        if (!version.isEmpty()) {
                            allVersions.add(version);
                        }
                    }
                    
                    // Extract plugins
                    final NodeList pluginNodes = doc.getElementsByTagName("plugin");
                    for (int i = 0; i < pluginNodes.getLength(); i++) {
                        final Element plugin = (Element) pluginNodes.item(i);
                        final String prefix = getElementText(plugin, "prefix");
                        final String artifactId = getElementText(plugin, "artifactId");
                        final String name = getElementText(plugin, "name");
                        if (prefix != null && artifactId != null) {
                            allPlugins.add(prefix + ":" + artifactId + ":" + (name != null ? name : ""));
                        }
                    }
                }

                // Compute latest and release versions
                String latest = null;
                String release = null;
                
                if (!allVersions.isEmpty()) {
                    // Latest is highest version overall
                    latest = allVersions.stream()
                        .max(new VersionComparator())
                        .orElse(null);
                    
                    // Release is highest non-SNAPSHOT version
                    release = allVersions.stream()
                        .filter(v -> !v.contains("SNAPSHOT"))
                        .max(new VersionComparator())
                        .orElse(null);
                }

                // Add latest
                if (latest != null) {
                    final Element latestElem = merged.createElement("latest");
                    latestElem.setTextContent(latest);
                    versioning.appendChild(latestElem);
                }

                // Add release
                if (release != null) {
                    final Element releaseElem = merged.createElement("release");
                    releaseElem.setTextContent(release);
                    versioning.appendChild(releaseElem);
                }

                // Add versions
                if (!allVersions.isEmpty()) {
                    final Element versionsElem = merged.createElement("versions");
                    versioning.appendChild(versionsElem);
                    for (String version : allVersions) {
                        final Element versionElem = merged.createElement("version");
                        versionElem.setTextContent(version);
                        versionsElem.appendChild(versionElem);
                    }
                }

                // Add plugins if any
                if (!allPlugins.isEmpty()) {
                    final Element pluginsElem = merged.createElement("plugins");
                    versioning.appendChild(pluginsElem);
                    for (String pluginStr : allPlugins) {
                        final String[] parts = pluginStr.split(":", 3);
                        
                        // Skip malformed plugin data (must have at least prefix:artifactId)
                        if (parts.length < 2) {
                            Logger.warn(
                                this,
                                "Skipping malformed plugin data: %s (expected format: prefix:artifactId:name)",
                                pluginStr
                            );
                            continue;
                        }
                        
                        final Element pluginElem = merged.createElement("plugin");
                        
                        final Element prefixElem = merged.createElement("prefix");
                        prefixElem.setTextContent(parts[0]);
                        pluginElem.appendChild(prefixElem);
                        
                        final Element artifactIdElem = merged.createElement("artifactId");
                        artifactIdElem.setTextContent(parts[1]);
                        pluginElem.appendChild(artifactIdElem);
                        
                        if (parts.length > 2 && !parts[2].isEmpty()) {
                            final Element nameElem = merged.createElement("name");
                            nameElem.setTextContent(parts[2]);
                            pluginElem.appendChild(nameElem);
                        }
                        
                        pluginsElem.appendChild(pluginElem);
                    }
                }

                // Add lastUpdated
                final Element lastUpdated = merged.createElement("lastUpdated");
                lastUpdated.setTextContent(String.valueOf(Instant.now().toEpochMilli()));
                versioning.appendChild(lastUpdated);

                // Convert merged document to bytes
                final TransformerFactory transformerFactory = TransformerFactory.newInstance();
                final Transformer transformer = transformerFactory.newTransformer();
                transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
                
                final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                transformer.transform(new DOMSource(merged), new StreamResult(outputStream));
                
                return new Content.From(outputStream.toByteArray());
                
            } catch (Exception e) {
                throw new IllegalStateException("Failed to merge metadata", e);
            }
        });
    }

    /**
     * Copy an element from source document to target.
     */
    private void copyElement(
        final Document source,
        final Document target,
        final Element targetParent,
        final String tagName
    ) {
        final NodeList nodes = source.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            final Element elem = target.createElement(tagName);
            elem.setTextContent(nodes.item(0).getTextContent());
            targetParent.appendChild(elem);
        }
    }

    /**
     * Get text content of a child element.
     */
    private String getElementText(final Element parent, final String tagName) {
        final NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return null;
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
