/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.composer.http;

import com.auto1.pantera.asto.Content;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * TAR.GZ archive implementation for Composer packages.
 */
public final class TarArchive implements Archive {
    
    /**
     * Composer json file name.
     */
    private static final String COMPOSER_JSON = "composer.json";
    
    /**
     * Archive name.
     */
    private final Archive.Name archiveName;
    
    /**
     * Ctor.
     * @param name Archive name
     */
    public TarArchive(final Archive.Name name) {
        this.archiveName = name;
    }
    
    @Override
    public CompletionStage<JsonObject> composerFrom(final Content archive) {
        return archive.asBytesFuture()
            .thenApply(
                bytes -> {
                    try (
                        GzipCompressorInputStream gzip = new GzipCompressorInputStream(
                            new ByteArrayInputStream(bytes)
                        );
                        TarArchiveInputStream tar = new TarArchiveInputStream(gzip)
                    ) {
                        TarArchiveEntry entry;
                        while ((entry = tar.getNextTarEntry()) != null) {
                            if (!entry.isDirectory()) {
                                final String[] parts = entry.getName().split("/");
                                final String filename = parts[parts.length - 1];
                                if (COMPOSER_JSON.equals(filename)) {
                                    // Read the composer.json content
                                    final ByteArrayOutputStream out = new ByteArrayOutputStream();
                                    final byte[] buffer = new byte[8192];
                                    int len;
                                    while ((len = tar.read(buffer)) > 0) {
                                        out.write(buffer, 0, len);
                                    }
                                    return Json.createReader(
                                        new ByteArrayInputStream(out.toByteArray())
                                    ).readObject();
                                }
                            }
                        }
                        throw new IllegalStateException(
                            String.format("'%s' file was not found in TAR archive", COMPOSER_JSON)
                        );
                    } catch (final IOException exc) {
                        throw new UncheckedIOException(exc);
                    }
                }
            );
    }
    
    @Override
    public CompletionStage<Content> replaceComposerWith(
        final Content archive, final byte[] composer
    ) {
        return archive.asBytesFuture()
            .thenApply(
                bytes -> {
                    final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    try (
                        GzipCompressorOutputStream gzipOut = new GzipCompressorOutputStream(bos);
                        TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzipOut)
                    ) {
                        tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                        
                        try (
                            GzipCompressorInputStream gzipIn = new GzipCompressorInputStream(
                                new ByteArrayInputStream(bytes)
                            );
                            TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)
                        ) {
                            TarArchiveEntry entry;
                            while ((entry = tarIn.getNextTarEntry()) != null) {
                                if (!entry.isDirectory()) {
                                    final String[] parts = entry.getName().split("/");
                                    final String filename = parts[parts.length - 1];
                                    
                                    final TarArchiveEntry newEntry = new TarArchiveEntry(entry.getName());
                                    
                                    if (COMPOSER_JSON.equals(filename)) {
                                        // Replace composer.json
                                        newEntry.setSize(composer.length);
                                        tarOut.putArchiveEntry(newEntry);
                                        tarOut.write(composer);
                                    } else {
                                        // Copy other files as-is
                                        newEntry.setSize(entry.getSize());
                                        tarOut.putArchiveEntry(newEntry);
                                        final byte[] buffer = new byte[8192];
                                        int len;
                                        while ((len = tarIn.read(buffer)) > 0) {
                                            tarOut.write(buffer, 0, len);
                                        }
                                    }
                                    tarOut.closeArchiveEntry();
                                }
                            }
                        }
                        tarOut.finish();
                    } catch (final IOException exc) {
                        throw new UncheckedIOException(exc);
                    }
                    return bos.toByteArray();
                }
            ).thenApply(Content.From::new);
    }
    
    @Override
    public Archive.Name name() {
        return this.archiveName;
    }
}
