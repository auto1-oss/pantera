/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.rpm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.rpm.RepoConfig;
import com.auto1.pantera.rpm.asto.AstoRepoRemove;
import com.auto1.pantera.rpm.meta.PackageInfo;
import com.auto1.pantera.scheduling.ArtifactEvent;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Rpm endpoint to remove packages accepts file checksum of the package to remove
 * in the X-Checksum-ALG header, where ALG is checksum algorithm. Header may be skipped with the
 * help of `force=true` request parameter.
 * The slice validates request data, saves file name to temp location and, if update
 * mode is {@link RepoConfig.UpdateMode#UPLOAD} and `skip_update` parameter is false (or absent),
 * initiates removing files process.
 * If request is not valid (see {@link RpmRemove#validate(Key, Pair)}),
 * `BAD_REQUEST` status is returned.
 */
public final class RpmRemove implements Slice {

    /**
     * Temp key for the packages to remove.
     */
    public static final Key TO_RM = new Key.From(".remove");

    /**
     * Asto storage.
     */
    private final Storage asto;

    /**
     * Repository config.
     */
    private final RepoConfig cnfg;

    /**
     * Artifact upload/remove events.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Ctor.
     * @param asto Asto storage
     * @param cnfg Repo config
     * @param events Artifact events
     */
    public RpmRemove(final Storage asto, final RepoConfig cnfg,
        final Optional<Queue<ArtifactEvent>> events) {
        this.asto = asto;
        this.cnfg = cnfg;
        this.events = events;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers,
                                                Content body) {
        final RpmUpload.Request request = new RpmUpload.Request(line);
        final Key temp = new Key.From(RpmRemove.TO_RM, request.file());
        return this.asto.save(temp, Content.EMPTY).thenApply(nothing -> RpmRemove.checksum(headers))
            .thenCompose(
                checksum -> checksum.map(sum -> this.validate(request.file(), sum))
                    .orElse(CompletableFuture.completedFuture(request.force())).thenCompose(
                        valid -> {
                            CompletionStage<RsStatus> res = CompletableFuture
                                .completedFuture(RsStatus.ACCEPTED);
                            if (valid && this.cnfg.mode() == RepoConfig.UpdateMode.UPLOAD
                                && !request.skipUpdate()) {
                                res = this.events.map(
                                    queue -> {
                                        final Collection<PackageInfo> infos =
                                            new ArrayList<>(1);
                                        return new AstoRepoRemove(this.asto, this.cnfg, infos)
                                            .perform().thenAccept(
                                                nothing -> infos.forEach(
                                                    item -> queue.add(
                                                        new ArtifactEvent(
                                                            RpmUpload.REPO_TYPE,
                                                            this.cnfg.name(), item.name(),
                                                            item.version()
                                                        )
                                                    )
                                                )
                                            );
                                    }
                                ).orElseGet(
                                    () -> new AstoRepoRemove(this.asto, this.cnfg).perform()
                                ).thenApply(ignored -> RsStatus.ACCEPTED);
                            } else if (!valid) {
                                res = this.asto.delete(temp)
                                    .thenApply(nothing -> RsStatus.BAD_REQUEST);
                            }
                            return res.thenApply(s -> ResponseBuilder.from(s).build());
                        }
                    )
            );
    }

    /**
     * Validate rpm package to remove. Valid if:
     * a) package exists,
     * b) checksums (checksum of the existing package = checksum from request header) are equal.
     * @param file File key
     * @param checksum Accepted checksum to compare
     * @return True is package is valid
     */
    private CompletionStage<Boolean> validate(final Key file, final Pair<String, String> checksum) {
        return this.asto.exists(file).thenCompose(
            exists -> {
                CompletionStage<Boolean> res = CompletableFuture.completedFuture(false);
                if (exists) {
                    res = this.asto.value(file).thenCompose(
                        val -> new ContentDigest(
                            val, () -> new Digests.FromString(checksum.getKey()).get().get()
                        ).hex().thenApply(pkg -> pkg.equals(checksum.getValue()))
                    );
                }
                return res;
            }
        );
    }

    /**
     * Obtain algorithm and checksum from headers.
     * @param headers Headers
     * @return Pair of algorithm and checksum if header was found
     */
    private static Optional<Pair<String, String>> checksum(Headers headers) {
        final String name = "x-checksum-";
        return headers.stream()
            .map(hdr -> new ImmutablePair<>(hdr.getKey().toLowerCase(Locale.US), hdr.getValue()))
            .filter(hdr -> hdr.getKey().startsWith(name))
            .findFirst().map(
                hdr -> new ImmutablePair<>(hdr.getKey().substring(name.length()), hdr.getValue())
            );
    }

}
