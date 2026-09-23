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
package com.auto1.pantera.cooldown;

import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.response.CooldownResponseFactory;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.http.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * A file-proxy cooldown block resolves a 403 factory instead of throwing.
 *
 * @since 2.2.9
 */
final class CooldownWiringFileProxyTest {

    @Test
    void fileProxyBlockAnswersCooldownForbidden() {
        CooldownWiring.registerAllAdapters();
        final Response resp = CooldownResponseRegistry.instance().getOrThrow("file-proxy")
            .forbidden(
                new CooldownBlock(
                    "file-proxy", "files_proxy", "/tools/cli-1.0.tar.gz", "latest",
                    CooldownReason.FRESH_RELEASE, Instant.now(),
                    Instant.now().plus(Duration.ofHours(5)), List.of()
                )
            );
        MatcherAssert.assertThat(
            "403",
            resp.status().code(),
            new IsEqual<>(403)
        );
        MatcherAssert.assertThat(
            "cooldown marker",
            resp.headers().values(CooldownResponseFactory.HEADER),
            new IsEqual<>(List.of("blocked"))
        );
        MatcherAssert.assertThat(
            "Retry-After present",
            resp.headers().values("Retry-After").size(),
            new IsEqual<>(1)
        );
    }
}
