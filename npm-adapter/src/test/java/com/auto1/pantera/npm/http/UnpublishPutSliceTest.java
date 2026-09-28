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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.npm.JsonFromMeta;
import com.auto1.pantera.npm.PerVersionLayout;
import com.auto1.pantera.npm.misc.PackumentRevision;
import com.auto1.pantera.scheduling.ArtifactEvent;
import javax.json.Json;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletionException;

/**
 * Test cases for {@link UnpublishPutSlice}.
 */
final class UnpublishPutSliceTest {

    /**
     * Test repo name.
     */
    static final String REPO = "test-npm";

    /**
     * Test project name.
     */
    private static final String PROJ = "@hello/simple-npm-project";

    /**
     * Test storage.
     */
    private Storage storage;

    /**
     * Meta file key.
     */
    private Key meta;

    /**
     * Test artifact events.
     */
    private Queue<ArtifactEvent> events;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.meta = new Key.From(UnpublishPutSliceTest.PROJ, "meta.json");
        this.events = new LinkedList<>();
    }

    @Test
    void returnsNotFoundIfMetaIsNotFound() {
        MatcherAssert.assertThat(
            new UnpublishPutSlice(
                this.storage, Optional.of(this.events), UnpublishPutSliceTest.REPO
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NOT_FOUND),
                new RequestLine(RqMethod.PUT, "/some/project/-rev/undefined"),
                Headers.from("referer", "unpublish"),
                Content.EMPTY
            )
        );
        MatcherAssert.assertThat("Events queue is empty", this.events.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"time", "versions", "dist-tags"})
    void removeVersionFromAllEntries(final String entry) {
        this.saveSourceMeta();
        MatcherAssert.assertThat(
            "Response status is OK",
            new UnpublishPutSlice(
                this.storage, Optional.of(this.events), UnpublishPutSliceTest.REPO
            ),
            this.responseMatcher()
        );
        MatcherAssert.assertThat(
            "Meta.json is updated",
            new JsonFromMeta(
                this.storage, new Key.From(UnpublishPutSliceTest.PROJ)
            ).json()
                .getJsonObject(entry)
                .keySet(),
            new IsNot<>(Matchers.hasItem("1.0.2"))
        );
        MatcherAssert.assertThat("Events queue has one item", this.events.size() == 1);
    }

    @Test
    void decreaseLatestVersion() {
        this.saveSourceMeta();
        MatcherAssert.assertThat(
            "Response status is OK",
            new UnpublishPutSlice(
                this.storage, Optional.of(this.events), UnpublishPutSliceTest.REPO
            ),
            this.responseMatcher()
        );
        MatcherAssert.assertThat(
            "Meta.json `dist-tags` are updated",
            new JsonFromMeta(
                this.storage, new Key.From(UnpublishPutSliceTest.PROJ)
            ).json()
                .getJsonObject("dist-tags")
                .getString("latest"),
            new IsEqual<>("1.0.1")
        );
        MatcherAssert.assertThat("Events queue has one item", this.events.size() == 1);
    }

    @Test
    void legacyUnpublishOfLastStableMovesLatestToRemainingPrerelease() {
        this.storage.save(
            new Key.From("rc-only", "meta.json"),
            new Content.From(
                Json.createObjectBuilder()
                    .add("name", "rc-only")
                    .add(
                        "versions",
                        Json.createObjectBuilder()
                            .add("1.0.0", Json.createObjectBuilder().add("version", "1.0.0"))
                            .add(
                                "1.1.0-beta.1",
                                Json.createObjectBuilder().add("version", "1.1.0-beta.1")
                            )
                    )
                    .add(
                        "time",
                        Json.createObjectBuilder()
                            .add("1.0.0", "2026-01-01T00:00:00Z")
                            .add("1.1.0-beta.1", "2026-01-02T00:00:00Z")
                    )
                    .add("dist-tags", Json.createObjectBuilder().add("latest", "1.0.0"))
                    .build().toString().getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        MatcherAssert.assertThat(
            "Response status is OK",
            new UnpublishPutSlice(
                this.storage, Optional.of(this.events), UnpublishPutSliceTest.REPO
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(RqMethod.PUT, "/rc-only/-rev/" + this.rev("rc-only")),
                Headers.from("referer", "unpublish"),
                new Content.From(
                    Json.createObjectBuilder()
                        .add("name", "rc-only")
                        .add(
                            "versions",
                            Json.createObjectBuilder().add(
                                "1.1.0-beta.1",
                                Json.createObjectBuilder().add("version", "1.1.0-beta.1")
                            )
                        )
                        .build().toString().getBytes(StandardCharsets.UTF_8)
                )
            )
        );
        MatcherAssert.assertThat(
            "latest moves to the remaining prerelease",
            new JsonFromMeta(this.storage, new Key.From("rc-only")).json()
                .getJsonObject("dist-tags").getString("latest"),
            new IsEqual<>("1.1.0-beta.1")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"undefined", "", "garbage"})
    void refusesAnUnusableRevision(final String sent) {
        // R24: the PUT leg of unpublish was accepted whatever the revision.
        this.saveSourceMeta();
        MatcherAssert.assertThat(
            "an unusable revision answers 428",
            new UnpublishPutSlice(
                this.storage, Optional.of(this.events), UnpublishPutSliceTest.REPO
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.PRECONDITION_REQUIRED),
                new RequestLine(
                    RqMethod.PUT, "/@hello%2fsimple-npm-project/-rev/" + sent
                ),
                Headers.from("referer", "unpublish"),
                new Content.From(
                    new TestResource(
                        String.format("storage/%s/meta.json", UnpublishPutSliceTest.PROJ)
                    ).asBytes()
                )
            )
        );
        MatcherAssert.assertThat(
            "no version is removed",
            new JsonFromMeta(this.storage, new Key.From(UnpublishPutSliceTest.PROJ)).json()
                .getJsonObject("versions").keySet().contains("1.0.2"),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat("Events queue is empty", this.events.isEmpty());
    }

    @Test
    void refusesAStaleRevision() {
        // R24: a client holding a stale packument must not drop versions
        // published after it read the packument.
        this.saveSourceMeta();
        MatcherAssert.assertThat(
            "a stale revision answers 409",
            new UnpublishPutSlice(
                this.storage, Optional.of(this.events), UnpublishPutSliceTest.REPO
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.CONFLICT),
                new RequestLine(
                    RqMethod.PUT,
                    "/@hello%2fsimple-npm-project/-rev/1-deadbeefdeadbeefdeadbeefdeadbeef"
                ),
                Headers.from("referer", "unpublish"),
                new Content.From(
                    new TestResource(
                        String.format("storage/%s/meta.json", UnpublishPutSliceTest.PROJ)
                    ).asBytes()
                )
            )
        );
        MatcherAssert.assertThat(
            "no version is removed",
            new JsonFromMeta(this.storage, new Key.From(UnpublishPutSliceTest.PROJ)).json()
                .getJsonObject("versions").keySet().contains("1.0.2"),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat("Events queue is empty", this.events.isEmpty());
    }

    @Test
    void failsToDeleteMoreThanOneVersion() {
        this.saveSourceMeta();
        final Throwable thr = Assertions.assertThrows(
            CompletionException.class,
            () -> new UnpublishPutSlice(
                this.storage, Optional.of(this.events), UnpublishPutSliceTest.REPO
            ).response(
                RequestLine.from(
                    String.format(
                        "PUT /@hello%%2fsimple-npm-project/-rev/%s HTTP/1.1",
                        this.rev(UnpublishPutSliceTest.PROJ)
                    )
                ),
                Headers.from("referer", "unpublish"),
                new Content.From(new TestResource("json/dist-tags.json").asBytes())
            ).join()
        );
        MatcherAssert.assertThat(
            thr.getCause(),
            new IsInstanceOf(PanteraException.class)
        );
        MatcherAssert.assertThat("Events queue is empty", this.events.isEmpty());
    }

    /**
     * Split-brain regression guard (WS4-npm.3): single-version unpublish must
     * be <em>effective</em> for a package published purely through the
     * per-version layout — the removed version's {@code .versions/<v>.json}
     * file must be genuinely deleted (not merely absent from a regenerated
     * meta.json that would re-derive it from a surviving file), and the
     * {@code latest} dist-tag must recompute once its target is gone.
     */
    @Test
    void unpublishSingleVersionDeletesPerVersionFileAndRecomputesLatest() {
        final Key pkg = new Key.From(UnpublishPutSliceTest.PROJ);
        final PerVersionLayout layout = new PerVersionLayout(this.storage);
        layout.addVersion(
            pkg, "1.0.1",
            Json.createObjectBuilder()
                .add("name", UnpublishPutSliceTest.PROJ).add("version", "1.0.1").build()
        ).toCompletableFuture().join();
        layout.addVersion(
            pkg, "1.0.2",
            Json.createObjectBuilder()
                .add("name", UnpublishPutSliceTest.PROJ).add("version", "1.0.2").build()
        ).toCompletableFuture().join();
        layout.mergeDistTags(
            pkg, Json.createObjectBuilder().add("latest", "1.0.2").build()
        ).toCompletableFuture().join();
        final String current = this.rev(UnpublishPutSliceTest.PROJ);
        MatcherAssert.assertThat(
            "Response status is OK",
            new UnpublishPutSlice(
                this.storage, Optional.of(this.events), UnpublishPutSliceTest.REPO
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(RqMethod.PUT, "/@hello%2fsimple-npm-project/-rev/" + current),
                Headers.from("referer", "unpublish"),
                new Content.From(
                    Json.createObjectBuilder()
                        .add(
                            "versions",
                            Json.createObjectBuilder().add(
                                "1.0.1",
                                Json.createObjectBuilder()
                                    .add("name", UnpublishPutSliceTest.PROJ)
                                    .add("version", "1.0.1")
                            )
                        ).build().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
            )
        );
        MatcherAssert.assertThat(
            "No meta.json crutch is written",
            this.storage.exists(new Key.From(pkg, "meta.json")).join(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "1.0.2 per-version file is genuinely deleted",
            layout.listVersions(pkg).toCompletableFuture().join().contains("1.0.2"),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "1.0.1 survives",
            layout.listVersions(pkg).toCompletableFuture().join().contains("1.0.1"),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "latest recomputes to the remaining version now that its target is gone",
            layout.generateMetaJson(pkg).toCompletableFuture().join()
                .getJsonObject("dist-tags").getString("latest"),
            new IsEqual<>("1.0.1")
        );
        MatcherAssert.assertThat("Events queue has one item", this.events.size() == 1);
    }

    /**
     * The package's current revision, as served in its packument's _rev.
     * @param pkg Package name
     * @return Revision
     */
    private String rev(final String pkg) {
        return new PackumentRevision(this.storage, pkg).value().join();
    }

    private void saveSourceMeta() {
        this.storage.save(
            this.meta,
            new Content.From(
                new TestResource("json/unpublish.json").asBytes()
            )
        ).join();
    }

    private SliceHasResponse responseMatcher() {
        return new SliceHasResponse(
            new RsHasStatus(RsStatus.OK),
            new RequestLine(
                RqMethod.PUT,
                "/@hello%2fsimple-npm-project/-rev/" + this.rev(UnpublishPutSliceTest.PROJ)
            ),
            Headers.from("referer", "unpublish"),
            new Content.From(
                new TestResource(
                    String.format("storage/%s/meta.json", UnpublishPutSliceTest.PROJ)
                ).asBytes()
            )
        );
    }
}
