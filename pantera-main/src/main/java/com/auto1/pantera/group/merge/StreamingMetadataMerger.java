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
package com.auto1.pantera.group.merge;

import com.auto1.pantera.http.log.EcsLogger;
import org.apache.maven.artifact.versioning.ComparableVersion;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Streaming merger for Maven {@code maven-metadata.xml} across group members.
 *
 * <p>Uses StAX to parse each member's response body incrementally;
 * accumulates only the deduplicated {@code <version>} set and the
 * compare-and-keep-newest scalars ({@code <latest>}, {@code <release>},
 * {@code <lastUpdated>}, {@code <snapshot>}). Peak memory is
 * O(unique versions), not O(sum of body sizes). At 1000 req/s with
 * modest distinct-version cardinality this is a fixed-size buffer
 * regardless of per-member body size.
 *
 * <p>Malformed or empty member bodies are skipped and logged at WARN
 * with {@code event.reason=member_metadata_parse}; the merge of
 * remaining members succeeds (partial-tolerance).
 *
 * <p>Maven version ordering uses {@link ComparableVersion}, the same
 * algorithm Maven CLI uses for dependency resolution.
 *
 * <p>Not thread-safe — instantiate one merger per merge operation.
 *
 * @since 2.2.0
 */
public final class StreamingMetadataMerger {

    /**
     * Logger category — matches existing maven group slice events.
     */
    private static final String LOG = "com.auto1.pantera.maven";

    /**
     * Shared, thread-safe StAX input factory.
     * External entities disabled to defend against XXE.
     */
    private static final XMLInputFactory INPUT_FACTORY = createInputFactory();

    /**
     * Shared, thread-safe StAX output factory.
     */
    private static final XMLOutputFactory OUTPUT_FACTORY = XMLOutputFactory.newInstance();

    /**
     * Comparator that delegates to Maven's {@link ComparableVersion} —
     * the same algorithm the Maven CLI uses for dependency resolution.
     */
    private static final Comparator<String> VERSION_CMP = mavenVersionComparator();

    /**
     * Deduplicated set of every {@code <version>} discovered, sorted by
     * Maven version semantics.
     */
    private final TreeSet<String> versions = new TreeSet<>(VERSION_CMP);

    /**
     * {@code <groupId>} from the first member that supplied one.
     */
    private String groupId;

    /**
     * {@code <artifactId>} from the first member that supplied one.
     */
    private String artifactId;

    /**
     * {@code <latest>} — kept as the max across members per
     * {@link #VERSION_CMP}.
     */
    private String latest;

    /**
     * {@code <release>} — kept as the max across members per
     * {@link #VERSION_CMP}.
     */
    private String release;

    /**
     * {@code <lastUpdated>} in {@code yyyyMMddHHmmss} — string compare
     * sorts lexicographically the same as time, so we just keep the max.
     */
    private String lastUpdated;

    /**
     * Latest {@code <snapshot>} block discovered; {@code null} until
     * the first member with a snapshot timestamp parses.
     */
    private SnapshotInfo snapshot;

    /**
     * Number of members whose body was successfully parsed (for diagnostics).
     */
    private int membersMerged;

    /**
     * Number of members whose body parse failed (for diagnostics).
     */
    private int membersSkipped;

    /**
     * Merge a single member's metadata body into the accumulated state.
     *
     * <p>On any parse error the member is skipped and a WARN is logged;
     * the merger remains usable for other members. The caller-supplied
     * stream is closed.
     *
     * @param body Member response body, in {@code maven-metadata.xml}
     *             format. May be empty / null — both are no-ops.
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.CognitiveComplexity"})
    public void mergeMember(final InputStream body) {
        if (body == null) {
            this.membersSkipped++;
            return;
        }
        XMLStreamReader reader = null;
        try {
            reader = INPUT_FACTORY.createXMLStreamReader(body, "UTF-8");
            this.parse(reader);
            this.membersMerged++;
        } catch (final XMLStreamException | RuntimeException ex) {
            this.membersSkipped++;
            EcsLogger.warn(LOG)
                .message("Skipping malformed member metadata during streaming merge")
                .eventCategory("web")
                .eventAction("metadata_merge")
                .eventOutcome("failure")
                .field("event.reason", "member_metadata_parse")
                .error(ex)
                .log();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (final XMLStreamException ignore) {
                    // best effort
                }
            }
            try {
                body.close();
            } catch (final java.io.IOException ignore) {
                // best effort
            }
        }
    }

    /**
     * Emit the merged metadata XML using a streaming writer.
     *
     * <p>If no members were ever successfully merged, a minimal valid
     * {@code <metadata/>} document is returned so downstream Maven
     * clients see a parseable response rather than an empty body.
     *
     * @return Bytes of the merged {@code maven-metadata.xml} (UTF-8).
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public byte[] toXml() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        XMLStreamWriter writer = null;
        try {
            writer = OUTPUT_FACTORY.createXMLStreamWriter(out, "UTF-8");
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeCharacters("\n");
            writer.writeStartElement("metadata");
            writer.writeCharacters("\n");
            if (this.groupId != null) {
                writeLeaf(writer, "  ", "groupId", this.groupId);
            }
            if (this.artifactId != null) {
                writeLeaf(writer, "  ", "artifactId", this.artifactId);
            }
            final boolean hasVersioning =
                this.latest != null
                    || this.release != null
                    || this.lastUpdated != null
                    || this.snapshot != null
                    || !this.versions.isEmpty();
            if (hasVersioning) {
                writer.writeCharacters("  ");
                writer.writeStartElement("versioning");
                writer.writeCharacters("\n");
                if (this.latest != null) {
                    writeLeaf(writer, "    ", "latest", this.latest);
                }
                if (this.release != null) {
                    writeLeaf(writer, "    ", "release", this.release);
                }
                if (this.snapshot != null) {
                    writer.writeCharacters("    ");
                    writer.writeStartElement("snapshot");
                    writer.writeCharacters("\n");
                    if (this.snapshot.timestamp() != null) {
                        writeLeaf(writer, "      ", "timestamp", this.snapshot.timestamp());
                    }
                    if (this.snapshot.buildNumber() != null) {
                        writeLeaf(
                            writer, "      ", "buildNumber", this.snapshot.buildNumber()
                        );
                    }
                    writer.writeCharacters("    ");
                    writer.writeEndElement();
                    writer.writeCharacters("\n");
                }
                if (!this.versions.isEmpty()) {
                    writer.writeCharacters("    ");
                    writer.writeStartElement("versions");
                    writer.writeCharacters("\n");
                    for (final String v : this.versions) {
                        writeLeaf(writer, "      ", "version", v);
                    }
                    writer.writeCharacters("    ");
                    writer.writeEndElement();
                    writer.writeCharacters("\n");
                }
                if (this.lastUpdated != null) {
                    writeLeaf(writer, "    ", "lastUpdated", this.lastUpdated);
                }
                writer.writeCharacters("  ");
                writer.writeEndElement();
                writer.writeCharacters("\n");
            }
            writer.writeEndElement();
            writer.writeCharacters("\n");
            writer.writeEndDocument();
            writer.flush();
        } catch (final XMLStreamException ex) {
            throw new IllegalStateException("Failed to emit merged metadata XML", ex);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (final XMLStreamException ignore) {
                    // best effort
                }
            }
        }
        return out.toByteArray();
    }

    /**
     * @return Number of members whose body was successfully parsed.
     */
    public int membersMerged() {
        return this.membersMerged;
    }

    /**
     * @return Number of members whose body parse failed (and were skipped).
     */
    public int membersSkipped() {
        return this.membersSkipped;
    }

    // ===== internals =====

    @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.CyclomaticComplexity", "PMD.NPathComplexity"})
    private void parse(final XMLStreamReader reader) throws XMLStreamException {
        final Deque<String> stack = new ArrayDeque<>();
        String snapshotTimestamp = null;
        String snapshotBuildNumber = null;
        boolean inSnapshot = false;
        while (reader.hasNext()) {
            final int event = reader.next();
            switch (event) {
                case XMLStreamConstants.START_ELEMENT -> {
                    final String name = reader.getLocalName();
                    stack.push(name);
                    if ("snapshot".equals(name) && inVersioning(stack)) {
                        inSnapshot = true;
                        snapshotTimestamp = null;
                        snapshotBuildNumber = null;
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    final String name = stack.isEmpty() ? null : stack.pop();
                    if ("snapshot".equals(name) && inSnapshot) {
                        inSnapshot = false;
                        if (snapshotTimestamp != null) {
                            this.maybeUpdateSnapshot(snapshotTimestamp, snapshotBuildNumber);
                        }
                    }
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                    if (stack.isEmpty()) {
                        continue;
                    }
                    final String tag = stack.peek();
                    if ("groupId".equals(tag) && parentIs(stack, "metadata")) {
                        this.acceptGroupId(reader.getText());
                    } else if ("artifactId".equals(tag) && parentIs(stack, "metadata")) {
                        this.acceptArtifactId(reader.getText());
                    } else if ("version".equals(tag) && inVersionsList(stack)) {
                        final String v = trimToNull(reader.getText());
                        if (v != null) {
                            this.versions.add(v);
                        }
                    } else if ("latest".equals(tag) && parentIs(stack, "versioning")) {
                        this.latest = pickNewerVersion(this.latest, reader.getText());
                    } else if ("release".equals(tag) && parentIs(stack, "versioning")) {
                        this.release = pickNewerVersion(this.release, reader.getText());
                    } else if ("lastUpdated".equals(tag) && parentIs(stack, "versioning")) {
                        final String v = trimToNull(reader.getText());
                        if (v != null
                            && (this.lastUpdated == null || v.compareTo(this.lastUpdated) > 0)) {
                            this.lastUpdated = v;
                        }
                    } else if (inSnapshot && "timestamp".equals(tag)) {
                        final String t = trimToNull(reader.getText());
                        if (t != null) {
                            snapshotTimestamp = t;
                        }
                    } else if (inSnapshot && "buildNumber".equals(tag)) {
                        final String b = trimToNull(reader.getText());
                        if (b != null) {
                            snapshotBuildNumber = b;
                        }
                    }
                }
                default -> {
                    // ignore comments, PIs, whitespace
                }
            }
        }
    }

    private void acceptGroupId(final String text) {
        final String v = trimToNull(text);
        if (v == null) {
            return;
        }
        if (this.groupId == null) {
            this.groupId = v;
        } else if (!this.groupId.equals(v)) {
            EcsLogger.warn(LOG)
                .message("Member metadata groupId mismatch — keeping first"
                    + " (kept=" + this.groupId + ", other=" + v + ")")
                .eventCategory("web")
                .eventAction("metadata_merge")
                .field("event.reason", "groupid_mismatch")
                .log();
        }
    }

    private void acceptArtifactId(final String text) {
        final String v = trimToNull(text);
        if (v == null) {
            return;
        }
        if (this.artifactId == null) {
            this.artifactId = v;
        } else if (!this.artifactId.equals(v)) {
            EcsLogger.warn(LOG)
                .message("Member metadata artifactId mismatch — keeping first"
                    + " (kept=" + this.artifactId + ", other=" + v + ")")
                .eventCategory("web")
                .eventAction("metadata_merge")
                .field("event.reason", "artifactid_mismatch")
                .log();
        }
    }

    private void maybeUpdateSnapshot(final String ts, final String build) {
        if (this.snapshot == null || ts.compareTo(this.snapshot.timestamp()) > 0) {
            this.snapshot = new SnapshotInfo(ts, build);
        }
    }

    private static String pickNewerVersion(final String current, final String candidate) {
        final String c = trimToNull(candidate);
        if (c == null) {
            return current;
        }
        if (current == null) {
            return c;
        }
        return VERSION_CMP.compare(c, current) > 0 ? c : current;
    }

    private static boolean parentIs(final Deque<String> stack, final String parent) {
        if (stack.size() < 2) {
            return false;
        }
        final var it = stack.iterator();
        it.next();
        return parent.equals(it.next());
    }

    /**
     * @return {@code true} if current open element chain contains
     *         {@code versioning} below the current top.
     */
    private static boolean inVersioning(final Deque<String> stack) {
        for (final String name : stack) {
            if ("versioning".equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return {@code true} if the current element is a {@code <version>}
     *         child of {@code <versions>} child of {@code <versioning>}.
     */
    private static boolean inVersionsList(final Deque<String> stack) {
        if (stack.size() < 3) {
            return false;
        }
        final var it = stack.iterator();
        // top is the open <version>, next must be <versions>, next <versioning>
        it.next();
        return "versions".equals(it.next()) && "versioning".equals(it.next());
    }

    private static String trimToNull(final String raw) {
        if (raw == null) {
            return null;
        }
        final String t = raw.trim();
        return t.isEmpty() ? null : t;
    }

    private static void writeLeaf(
        final XMLStreamWriter writer,
        final String indent,
        final String name,
        final String value
    ) throws XMLStreamException {
        writer.writeCharacters(indent);
        writer.writeStartElement(name);
        writer.writeCharacters(value);
        writer.writeEndElement();
        writer.writeCharacters("\n");
    }

    private static Comparator<String> mavenVersionComparator() {
        return (a, b) -> new ComparableVersion(Objects.requireNonNullElse(a, ""))
            .compareTo(new ComparableVersion(Objects.requireNonNullElse(b, "")));
    }

    private static XMLInputFactory createInputFactory() {
        final XMLInputFactory factory = XMLInputFactory.newInstance();
        // XXE hardening — Maven metadata never references external entities.
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory;
    }

    /**
     * Convenience: serialise to UTF-8 string (for tests / debugging).
     *
     * @return Merged document as a UTF-8 string.
     */
    public String toXmlString() {
        return new String(this.toXml(), StandardCharsets.UTF_8);
    }

    /**
     * Snapshot-block contents.
     *
     * @param timestamp   {@code <timestamp>} value; may not be {@code null}.
     * @param buildNumber {@code <buildNumber>} value; may be {@code null}.
     */
    private record SnapshotInfo(String timestamp, String buildNumber) {
    }
}
