/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.composite;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.fake.FakeCatalogDocker;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.docker.proxy.ProxyDocker;
import com.auto1.pantera.http.ResponseBuilder;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReadWriteDocker}.
 *
 * @since 0.3
 */
final class ReadWriteDockerTest {

    @Test
    void createsReadWriteRepo() {
        final ReadWriteDocker docker = new ReadWriteDocker(
            new ProxyDocker("test_registry", (line, headers, body) -> ResponseBuilder.ok().completedFuture()),
            new AstoDocker("test_registry", new InMemoryStorage())
        );
        MatcherAssert.assertThat(
            docker.repo("test"),
            new IsInstanceOf(ReadWriteRepo.class)
        );
    }

    @Test
    void delegatesCatalog() {
        final int limit = 123;
        final Catalog catalog = () -> new Content.From("{...}".getBytes());
        final FakeCatalogDocker fake = new FakeCatalogDocker(catalog);
        final ReadWriteDocker docker = new ReadWriteDocker(
            fake,
            new AstoDocker("test_registry", new InMemoryStorage())
        );
        final Catalog result = docker.catalog(Pagination.from("foo", limit)).join();
        MatcherAssert.assertThat(
            "Forwards from", fake.from(), Matchers.is("foo")
        );
        MatcherAssert.assertThat(
            "Forwards limit", fake.limit(), Matchers.is(limit)
        );
        MatcherAssert.assertThat(
            "Returns catalog", result, Matchers.is(catalog)
        );
    }

}
