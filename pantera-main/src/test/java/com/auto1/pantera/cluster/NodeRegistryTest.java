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
package com.auto1.pantera.cluster;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;

/**
 * Tests for {@link NodeRegistry}.
 */
class NodeRegistryTest {

    @Test
    void registersSelfOnConstruction() {
        final NodeRegistry reg = new NodeRegistry("node-1", "localhost");
        assertThat(reg.size(), equalTo(1));
        assertThat(reg.nodeId(), equalTo("node-1"));
    }

    @Test
    void autoGeneratesNodeId() {
        final NodeRegistry reg = new NodeRegistry("localhost");
        assertThat(reg.nodeId(), not(emptyString()));
        assertThat(reg.size(), equalTo(1));
    }

    @Test
    void heartbeatUpdatesTimestamp() {
        final NodeRegistry reg = new NodeRegistry("node-1", "localhost");
        reg.heartbeat();
        assertThat(reg.activeNodes(), hasSize(1));
    }

    @Test
    void activeNodesReturnsSelf() {
        final NodeRegistry reg = new NodeRegistry("node-1", "localhost");
        assertThat(reg.activeNodes(), hasSize(1));
        assertThat(reg.activeNodes().get(0).nodeId(), equalTo("node-1"));
        assertThat(reg.activeNodes().get(0).hostname(), equalTo("localhost"));
    }
}
