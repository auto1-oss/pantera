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
package com.auto1.pantera.maven.metadata;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reconciles artifact-level {@code maven-metadata.xml} with storage after
 * a delete: versions whose directory no longer holds any file are removed
 * from {@code <versions>}, {@code <latest>}/{@code <release>} are moved to
 * the highest remaining version, the checksum sidecars are rewritten, and a
 * metadata file left with no version at all is removed with its sidecars.
 *
 * <p>Without this a deleted version stayed listed, so a Maven version range
 * resolved to it and the build failed on the missing POM/JAR.</p>
 *
 * @since 2.2.9
 */
public final class MetadataVersionPruner {

    /**
     * Metadata file name.
     */
    private static final String METADATA = "maven-metadata.xml";

    /**
     * Checksum sidecars written next to metadata.
     */
    private static final List<String> CHECKSUMS = List.of("md5", "sha1", "sha256", "sha512");

    /**
     * Repository storage (repository-relative keys).
     */
    private final Storage storage;

    /**
     * Ctor.
     * @param storage Repository storage
     */
    public MetadataVersionPruner(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Reconcile the metadata that may list the deleted path: the metadata of
     * the parent directory (a deleted version directory) and of the
     * grand-parent directory (a file deleted inside a version directory).
     * @param deleted Deleted storage path, repository-relative
     * @return Dotted artifact names ({@code group.artifact}) whose metadata changed
     */
    public CompletableFuture<List<String>> afterDelete(final String deleted) {
        final List<String> dirs = MetadataVersionPruner.candidateDirs(deleted);
        CompletableFuture<List<String>> res = CompletableFuture.completedFuture(new ArrayList<>(2));
        for (final String dir : dirs) {
            res = res.thenCompose(
                changed -> this.reconcile(dir).thenApply(name -> {
                    name.ifPresent(changed::add);
                    return changed;
                })
            );
        }
        return res;
    }

    /**
     * Reconcile one artifact directory.
     * @param dir Artifact directory, e.g. {@code com/acme/lib}
     * @return Dotted artifact name when its metadata changed
     */
    private CompletableFuture<Optional<String>> reconcile(final String dir) {
        final Key meta = new Key.From(dir, METADATA);
        return this.storage.exists(meta).thenCompose(exists -> {
            if (!exists) {
                return CompletableFuture.completedFuture(Optional.<String>empty());
            }
            return this.storage.value(meta)
                .thenCompose(Content::asBytesFuture)
                .thenCompose(bytes -> this.prune(dir, meta, bytes));
        });
    }

    /**
     * Drop versions that no longer exist in storage.
     * @param dir Artifact directory
     * @param meta Metadata key
     * @param bytes Metadata bytes
     * @return Dotted artifact name when changed
     */
    private CompletableFuture<Optional<String>> prune(
        final String dir, final Key meta, final byte[] bytes
    ) {
        final Document doc;
        try {
            doc = MetadataVersionPruner.parse(bytes);
        } catch (final Exception unreadable) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        final NodeList nodes = doc.getElementsByTagName("version");
        final List<Element> listed = new ArrayList<>(nodes.getLength());
        for (int idx = 0; idx < nodes.getLength(); idx += 1) {
            final Node parent = nodes.item(idx).getParentNode();
            if (parent != null && "versions".equals(parent.getNodeName())) {
                listed.add((Element) nodes.item(idx));
            }
        }
        return this.existing(dir, listed).thenCompose(present -> {
            if (present.size() == listed.size()) {
                return CompletableFuture.completedFuture(Optional.<String>empty());
            }
            final String dotted = dir.replace('/', '.');
            if (present.isEmpty()) {
                return this.removeMetadata(meta).thenApply(nothing -> Optional.of(dotted));
            }
            final Set<String> kept = new LinkedHashSet<>();
            for (final Element element : listed) {
                final String version = element.getTextContent().trim();
                if (present.contains(version)) {
                    kept.add(version);
                } else {
                    element.getParentNode().removeChild(element);
                }
            }
            MetadataVersionPruner.pointers(doc, kept);
            final byte[] updated;
            try {
                updated = MetadataVersionPruner.serialize(doc);
            } catch (final Exception broken) {
                return CompletableFuture.failedFuture(broken);
            }
            return this.saveWithChecksums(meta, updated)
                .thenApply(nothing -> Optional.of(dotted));
        });
    }

    /**
     * Versions (of those listed) whose directory still holds a file.
     * @param dir Artifact directory
     * @param listed Listed version elements
     * @return Present versions
     */
    private CompletableFuture<Set<String>> existing(
        final String dir, final List<Element> listed
    ) {
        final Set<String> present = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final List<CompletableFuture<Void>> checks = new ArrayList<>(listed.size());
        for (final Element element : listed) {
            final String version = element.getTextContent().trim();
            final String prefix = dir + "/" + version + "/";
            checks.add(
                this.storage.list(new Key.From(dir, version)).thenAccept(keys -> {
                    for (final Key key : keys) {
                        if (key.string().startsWith(prefix)) {
                            present.add(version);
                            break;
                        }
                    }
                })
            );
        }
        return CompletableFuture.allOf(checks.toArray(new CompletableFuture[0]))
            .thenApply(nothing -> present);
    }

    /**
     * Remove a metadata file and its checksum sidecars.
     * @param meta Metadata key
     * @return Completion
     */
    private CompletableFuture<Void> removeMetadata(final Key meta) {
        CompletableFuture<Void> res = this.storage.delete(meta);
        for (final String alg : CHECKSUMS) {
            final Key sidecar = new Key.From(meta.string() + "." + alg);
            res = res.thenCompose(
                nothing -> this.storage.exists(sidecar).thenCompose(
                    exists -> exists ? this.storage.delete(sidecar)
                        : CompletableFuture.completedFuture(null)
                )
            );
        }
        return res;
    }

    /**
     * Save metadata and rewrite its checksum sidecars.
     * @param meta Metadata key
     * @param bytes Metadata bytes
     * @return Completion
     */
    private CompletableFuture<Void> saveWithChecksums(final Key meta, final byte[] bytes) {
        CompletableFuture<Void> res = this.storage.save(meta, new Content.From(bytes));
        for (final String alg : CHECKSUMS) {
            final byte[] hex = MetadataVersionPruner.digest(alg, bytes)
                .getBytes(StandardCharsets.UTF_8);
            res = res.thenCompose(
                nothing -> this.storage.save(
                    new Key.From(meta.string() + "." + alg), new Content.From(hex)
                )
            );
        }
        return res;
    }

    /**
     * Move {@code <latest>}/{@code <release>} to the highest remaining
     * versions and refresh {@code <lastUpdated>}.
     * @param doc Metadata document
     * @param kept Remaining versions
     */
    private static void pointers(final Document doc, final Set<String> kept) {
        final Optional<String> latest = kept.stream().max(Comparator.comparing(Version::new));
        final Optional<String> release = kept.stream()
            .filter(version -> !version.endsWith("SNAPSHOT"))
            .max(Comparator.comparing(Version::new));
        MetadataVersionPruner.setOrDrop(doc, "latest", latest);
        MetadataVersionPruner.setOrDrop(doc, "release", release);
        MetadataVersionPruner.setOrDrop(doc, "lastUpdated", Optional.of(MavenTimestamp.now()));
    }

    /**
     * Set the text of an existing element, or remove it when the value is
     * absent. A missing element is left missing.
     * @param doc Document
     * @param tag Element name
     * @param value New value
     */
    private static void setOrDrop(
        final Document doc, final String tag, final Optional<String> value
    ) {
        final NodeList found = doc.getElementsByTagName(tag);
        for (int idx = found.getLength() - 1; idx >= 0; idx -= 1) {
            final Node node = found.item(idx);
            if (value.isPresent()) {
                node.setTextContent(value.get());
            } else {
                node.getParentNode().removeChild(node);
            }
        }
    }

    /**
     * Artifact directories whose metadata may list the deleted path.
     * @param deleted Deleted path
     * @return Parent and grand-parent directories
     */
    private static List<String> candidateDirs(final String deleted) {
        String clean = deleted.trim();
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        final List<String> dirs = new ArrayList<>(2);
        final int last = clean.lastIndexOf('/');
        if (last > 0) {
            final String parent = clean.substring(0, last);
            dirs.add(parent);
            final int prev = parent.lastIndexOf('/');
            if (prev > 0) {
                dirs.add(parent.substring(0, prev));
            }
        }
        return dirs;
    }

    /**
     * Hex digest.
     * @param alg Algorithm sidecar extension (md5, sha1, sha256, sha512)
     * @param bytes Data
     * @return Lower-case hex
     */
    private static String digest(final String alg, final byte[] bytes) {
        final String name;
        switch (alg) {
            case "md5":
                name = "MD5";
                break;
            case "sha1":
                name = "SHA-1";
                break;
            default:
                name = "SHA-" + alg.substring(3).toUpperCase(Locale.ROOT);
                break;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(name).digest(bytes));
        } catch (final NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    /**
     * Parse metadata with DOCTYPE and external entities disabled.
     * @param bytes XML bytes
     * @return Document
     * @throws Exception On malformed XML
     */
    private static Document parse(final byte[] bytes) throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        final javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        return builder.parse(new ByteArrayInputStream(bytes));
    }

    /**
     * Serialize a document.
     * @param doc Document
     * @return UTF-8 bytes
     * @throws Exception On transformer failure
     */
    private static byte[] serialize(final Document doc) throws Exception {
        final TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        final javax.xml.transform.Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }
}
