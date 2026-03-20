/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import com.auto1.pantera.RepositorySlices;
import com.auto1.pantera.importer.ImportService;
import com.auto1.pantera.importer.ImportSessionStore;
import com.auto1.pantera.importer.http.ImportSlice;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtPath;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.misc.PanteraProperties;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.MetadataEventQueues;
import com.auto1.pantera.settings.Settings;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Slice Pantera serves on it's main port.
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
     * Pantera entry point.
     *
     * @param settings Pantera settings.
     * @param slices Repository slices.
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
            events,
            true
        );
        // No wall-clock timeout here — idle-based timeout is handled by Vert.x
        // (HttpServerOptions.setIdleTimeout). A global wall-clock timeout kills
        // legitimate large transfers (multi-GB Docker blobs, Maven artifacts).
        return new SliceRoute(
            MainSlice.EMPTY_PATH,
            new RtRulePath(
                new RtRule.ByPath(Pattern.compile("/\\.health")),
                new HealthSlice()
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.GET,
                    new RtRule.ByPath("/.version")
                ),
                new VersionSlice(new PanteraProperties())
            ),
            new RtRulePath(
                new RtRule.All(
                    new RtRule.ByPath("/\\.import/.*"),
                    new RtRule.Any(MethodRule.PUT, MethodRule.POST)
                ),
                new ImportSlice(imports)
            ),
            new RtRulePath(
                new RtRule.All(
                    new RtRule.ByPath("/\\.merge/.*"),
                    MethodRule.POST
                ),
                new MergeShardsSlice(slices)
            ),
            new RtRulePath(
                RtRule.FALLBACK,
                new DockerRoutingSlice(
                    settings,
                    new ApiRoutingSlice(
                        new SliceByPath(slices, settings.prefixes()),
                        slices.repositories()
                    )
                )
            )
        );
    }
}
