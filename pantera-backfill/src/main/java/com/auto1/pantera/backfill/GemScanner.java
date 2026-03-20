/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.backfill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scanner for Ruby gem repositories.
 *
 * <p>Walks the repository directory tree looking for {@code .gem} files.
 * If a {@code gems/} subdirectory exists under the root, only that
 * subdirectory is scanned; otherwise the root itself is scanned
 * (flat layout). Each {@code .gem} filename is parsed with a regex
 * to extract the gem name and version.</p>
 *
 * <p>The filename convention is
 * {@code {name}-{version}(-{platform}).gem}. Gem names may contain
 * hyphens (e.g. {@code net-http}, {@code ruby-ole}), so the version
 * is identified as the first hyphen-separated segment that starts
 * with a digit.</p>
 *
 * @since 1.20.13
 */
final class GemScanner implements Scanner {

    /**
     * Logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(GemScanner.class);

    /**
     * Pattern for gem filenames.
     * Captures the gem name (which may contain hyphens) and the
     * version (which starts with a digit). An optional platform
     * suffix (e.g. {@code -x86_64-linux}) is allowed but not
     * captured.
     * Examples:
     * <ul>
     *   <li>{@code rails-7.0.4.gem} -> name=rails, version=7.0.4</li>
     *   <li>{@code net-http-0.3.2.gem} -> name=net-http, version=0.3.2</li>
     *   <li>{@code nokogiri-1.13.8-x86_64-linux.gem} -> name=nokogiri, version=1.13.8</li>
     *   <li>{@code ruby-ole-1.2.12.7.gem} -> name=ruby-ole, version=1.2.12.7</li>
     * </ul>
     */
    private static final Pattern GEM_PATTERN = Pattern.compile(
        "^(?<name>.+?)-(?<version>\\d[A-Za-z0-9._]*)(?:-[A-Za-z0-9_]+(?:-[A-Za-z0-9_]+)*)?[.]gem$"
    );

    /**
     * Name of the standard gems subdirectory.
     */
    private static final String GEMS_DIR = "gems";

    @Override
    public Stream<ArtifactRecord> scan(final Path root, final String repoName)
        throws IOException {
        final Path base;
        if (Files.isDirectory(root.resolve(GemScanner.GEMS_DIR))) {
            base = root.resolve(GemScanner.GEMS_DIR);
        } else {
            base = root;
        }
        return Files.walk(base, 1)
            .filter(Files::isRegularFile)
            .filter(path -> !path.getFileName().toString().startsWith("."))
            .filter(path -> path.getFileName().toString().endsWith(".gem"))
            .flatMap(path -> this.tryParse(repoName, path));
    }

    /**
     * Attempt to parse a gem file path into an artifact record.
     *
     * @param repoName Logical repository name
     * @param path File path to parse
     * @return Stream with a single record, or empty if filename does not match
     */
    private Stream<ArtifactRecord> tryParse(final String repoName,
        final Path path) {
        final String filename = path.getFileName().toString();
        final Matcher matcher = GEM_PATTERN.matcher(filename);
        if (!matcher.matches()) {
            LOG.debug(
                "Skipping non-conforming gem filename: {}", filename
            );
            return Stream.empty();
        }
        final String name = matcher.group("name");
        final String version = matcher.group("version");
        try {
            final BasicFileAttributes attrs = Files.readAttributes(
                path, BasicFileAttributes.class
            );
            return Stream.of(
                new ArtifactRecord(
                    "gem",
                    repoName,
                    name,
                    version,
                    attrs.size(),
                    attrs.lastModifiedTime().toMillis(),
                    null,
                    "system",
                    null
                )
            );
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
