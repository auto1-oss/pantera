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
package com.auto1.pantera.settings;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PrefixesConfig}.
 */
class PrefixesConfigTest {

    @Test
    void startsWithEmptyPrefixes() {
        final PrefixesConfig config = new PrefixesConfig();
        assertTrue(config.prefixes().isEmpty());
        assertEquals(0L, config.version());
    }

    @Test
    void initializesWithPrefixes() {
        final List<String> initial = Arrays.asList("p1", "p2");
        final PrefixesConfig config = new PrefixesConfig(initial);
        assertEquals(initial, config.prefixes());
        assertEquals(0L, config.version());
    }

    @Test
    void updatesAtomically() {
        final PrefixesConfig config = new PrefixesConfig();
        final List<String> newPrefixes = Arrays.asList("prefix1", "prefix2");
        
        config.update(newPrefixes);
        
        assertEquals(newPrefixes, config.prefixes());
        assertEquals(1L, config.version());
    }

    @Test
    void incrementsVersionOnUpdate() {
        final PrefixesConfig config = new PrefixesConfig();
        
        config.update(Arrays.asList("p1"));
        assertEquals(1L, config.version());
        
        config.update(Arrays.asList("p1", "p2"));
        assertEquals(2L, config.version());
        
        config.update(Arrays.asList("p1", "p2", "p3"));
        assertEquals(3L, config.version());
    }

    @Test
    void checksIfStringIsPrefix() {
        final PrefixesConfig config = new PrefixesConfig(Arrays.asList("p1", "p2", "migration"));
        
        assertTrue(config.isPrefix("p1"));
        assertTrue(config.isPrefix("p2"));
        assertTrue(config.isPrefix("migration"));
        assertFalse(config.isPrefix("p3"));
        assertFalse(config.isPrefix("unknown"));
        assertFalse(config.isPrefix(""));
    }

    @Test
    void returnsImmutableList() {
        final List<String> initial = Arrays.asList("p1", "p2");
        final PrefixesConfig config = new PrefixesConfig(initial);
        
        final List<String> retrieved = config.prefixes();
        assertEquals(initial, retrieved);
        
        // Verify immutability
        try {
            retrieved.add("p3");
            throw new AssertionError("Expected UnsupportedOperationException");
        } catch (final UnsupportedOperationException ex) {
            // Expected
        }
    }
}
