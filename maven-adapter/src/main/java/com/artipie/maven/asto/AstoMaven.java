/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.asto;

import com.artipie.ArtipieException;
import com.artipie.asto.Content;
import com.artipie.asto.Copy;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.SubStorage;
import com.artipie.asto.ext.KeyLastPart;
import com.artipie.maven.Maven;
import com.artipie.maven.http.PutMetadataSlice;
import com.artipie.maven.metadata.MavenMetadata;
import com.jcabi.log.Logger;
import com.jcabi.xml.XMLDocument;
import org.xembly.Directives;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * Maven front for artipie maven adaptor.
 */
public final class AstoMaven implements Maven {

    /**
     * Maven metadata xml name.
     */
    private static final String MAVEN_META = "maven-metadata.xml";

    /**
     * Repository storage.
     */
    private final Storage storage;

    /**
     * Constructor.
     * @param storage Storage used by this class.
     */
    public AstoMaven(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletionStage<Void> update(final Key upload, final Key artifact) {
        Logger.info(this, "Starting Maven artifact update: upload=%s, artifact=%s", upload, artifact);
        return this.storage.exclusively(
            artifact,
            target -> {
                Logger.info(this, "Acquired exclusive lock for artifact: %s", artifact);
                return target.list(artifact).thenApply(
                    items -> items.stream()
                        .map(
                            item -> item.string()
                                .replaceAll(String.format("%s/", artifact.string()), "")
                                .split("/")[0]
                        )
                        .filter(item -> !item.startsWith("maven-metadata"))
                        .collect(Collectors.toSet())
                ).thenCompose(
                    versions -> {
                        Logger.info(this, "Found existing versions: %s", versions);
                        return this.storage.value(
                            new Key.From(upload, PutMetadataSlice.SUB_META, AstoMaven.MAVEN_META)
                        ).thenCompose(Content::asStringFuture)
                            .thenCompose(
                                str -> {
                                    versions.add(new KeyLastPart(upload).get());
                                    Logger.info(this, "Updated versions list: %s", versions);
                                    return new MavenMetadata(
                                        Directives.copyOf(new XMLDocument(str).node())
                                    ).versions(versions).save(
                                        this.storage, new Key.From(upload, PutMetadataSlice.SUB_META)
                                    );
                                }
                            );
                    }
                )
                    .thenCompose(meta -> {
                        Logger.info(this, "Generating checksums for metadata");
                        return new RepositoryChecksums(this.storage).generate(meta);
                    })
                    .thenCompose(nothing -> {
                        Logger.info(this, "Moving artifacts to repository");
                        return this.moveToTheRepository(upload, target, artifact);
                    })
                    .thenCompose(nothing -> {
                        Logger.info(this, "Cleaning up upload directory: %s", upload);
                        return this.storage.deleteAll(upload);
                    })
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            Logger.error(
                                this,
                                "Failed to update Maven artifact: upload=%s, artifact=%s, error=%[exception]s",
                                upload, artifact, error
                            );
                        } else {
                            Logger.info(this, "Successfully updated Maven artifact: %s", artifact);
                        }
                    });
            }
        ).exceptionally(error -> {
            Logger.error(
                this,
                "Failed to acquire exclusive lock or update artifact: upload=%s, artifact=%s, error=%[exception]s",
                upload, artifact, error
            );
            throw new ArtipieException(
                String.format("Failed to update Maven artifact %s: %s", artifact, error.getMessage()),
                error
            );
        });
    }

    /**
     * Moves artifacts from temp location to repository.
     * @param upload Upload temp location
     * @param target Repository
     * @param artifact Artifact repository location
     * @return Completion action
     */
    private CompletableFuture<Void> moveToTheRepository(
        final Key upload, final Storage target, final Key artifact
    ) {
        Logger.info(this, "moveToTheRepository: upload=%s, artifact=%s", upload, artifact);
        
        // Verify upload has a parent
        if (upload.parent().isEmpty()) {
            final String error = String.format("Upload key has no parent: %s", upload);
            Logger.error(this, error);
            return CompletableFuture.failedFuture(new IllegalArgumentException(error));
        }
        
        final Storage sub = new SubStorage(
            new Key.From(upload, PutMetadataSlice.SUB_META), this.storage
        );
        final Storage subversion = new SubStorage(upload.parent().get(), this.storage);
        
        return sub.list(Key.ROOT).thenCompose(
            list -> {
                Logger.info(this, "Copying metadata files from upload/meta to repository, count=%d", list.size());
                return new Copy(
                    sub,
                    list.stream().filter(key -> key.string().contains(AstoMaven.MAVEN_META))
                        .collect(Collectors.toList())
                ).copy(new SubStorage(artifact, target));
            }
        ).thenCompose(
            nothing -> subversion.list(Key.ROOT).thenCompose(
                list -> {
                    Logger.info(this, "Copying artifact files to repository, count=%d", list.size());
                    return new Copy(
                        subversion,
                        list.stream()
                            .filter(
                                key -> !key.string().contains(
                                    String.format("/%s/", PutMetadataSlice.SUB_META)
                                )
                            ).collect(Collectors.toList())
                    ).copy(new SubStorage(artifact, target));
                }
            )
        ).exceptionally(error -> {
            Logger.error(
                this,
                "Failed to move artifacts to repository: upload=%s, artifact=%s, error=%[exception]s",
                upload, artifact, error
            );
            throw new ArtipieException(
                String.format("Failed to move artifacts: %s", error.getMessage()),
                error
            );
        });
    }
}
