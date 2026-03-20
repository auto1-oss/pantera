/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.adapters.maven;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.auth.GenericAuthenticator;
import com.auto1.pantera.cooldown.CooldownService;
import com.auto1.pantera.http.group.GroupSlice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.maven.http.MavenProxySlice;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import com.auto1.pantera.settings.repo.RepoConfig;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Maven proxy slice created from config.
 */
public final class MavenProxy implements Slice {

    private final Slice slice;

    /**
     * @param client HTTP client.
     * @param cfg Repository configuration.
     * @param queue Artifact events queue
     */
    public MavenProxy(
        ClientSlices client, RepoConfig cfg, Optional<Queue<ProxyArtifactEvent>> queue,
        CooldownService cooldown
    ) {
        final Optional<Storage> asto = cfg.storageOpt();
        slice = new GroupSlice(
            cfg.remotes().stream().map(
                remote -> new MavenProxySlice(
                    client, remote.uri(),
                    GenericAuthenticator.create(client, remote.username(), remote.pwd()),
                    asto.<Cache>map(FromStorageCache::new).orElse(Cache.NOP),
                    asto.flatMap(ignored -> queue),
                    cfg.name(),
                    cfg.type(),
                    cooldown,
                    asto  // Pass storage for checksum persistence
                )
            ).collect(Collectors.toList())
        );
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line,
        Headers headers,
        Content body
    ) {
        return slice.response(line, headers, body);
    }
}
