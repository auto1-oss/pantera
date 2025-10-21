/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.RepositorySlices;
import com.artipie.importer.ImportService;
import com.artipie.importer.ImportSessionStore;
import com.artipie.importer.http.ImportSlice;
import com.artipie.http.rt.MethodRule;
import com.artipie.http.rt.RtPath;
import com.artipie.http.rt.RtRule;
import com.artipie.http.rt.RtRulePath;
import com.artipie.http.rt.SliceRoute;
import com.artipie.misc.ArtipieProperties;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.scheduling.MetadataEventQueues;
import com.artipie.settings.Settings;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Slice Artipie serves on it's main port.
 * The slice handles `/.health`, `/.version` and repositories requests
 * extracting repository name from URI path.
 */
public final class MainSlice extends Slice.Wrap {

    /**
     * Route path returns {@code NO_CONTENT} status if path is empty.
     */
    private static final RtPath EMPTY_PATH = (line, headers, body) -> {
        final String path = line.uri().getPath();
        if (path.equals("*") || path.equals("/")
            || path.replaceAll("^/+", "").split("/").length == 0) {
            return Optional.of(CompletableFuture.completedFuture(
                ResponseBuilder.noContent().build()
            ));
        }
        return Optional.empty();
    };

    /**
     * Artipie entry point.
     *
     * @param settings Artipie settings.
     */
    public MainSlice(final Settings settings, final RepositorySlices slices) {
        super(MainSlice.buildMainSlice(settings, slices));
    }

    private static Slice buildMainSlice(final Settings settings, final RepositorySlices slices) {
        final Optional<ImportSessionStore> sessions = settings.artifactsDatabase()
            .map(ImportSessionStore::new);
        final Optional<Queue<ArtifactEvent>> events = settings.artifactMetadata()
            .map(MetadataEventQueues::eventQueue);
        final ImportService imports = new ImportService(
            slices.repositories(),
            sessions,
            events
        );
        return new SliceRoute(
            MainSlice.EMPTY_PATH,
            new RtRulePath(
                new RtRule.ByPath(Pattern.compile("/\\.health")),
                new HealthSlice(settings)
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.GET,
                    new RtRule.ByPath("/.version")
                ),
                new VersionSlice(new ArtipieProperties())
            ),
            new RtRulePath(
                new RtRule.All(
                    new RtRule.ByPath("/\\.import/.*"),
                    new RtRule.Any(MethodRule.PUT, MethodRule.POST)
                ),
                new ImportSlice(imports)
            ),
            new RtRulePath(
                RtRule.FALLBACK,
                new DockerRoutingSlice(
                    settings, 
                    new ComposerRoutingSlice(
                        new SliceByPath(slices, settings.prefixes())
                    )
                )
            )
        );
    }
}
