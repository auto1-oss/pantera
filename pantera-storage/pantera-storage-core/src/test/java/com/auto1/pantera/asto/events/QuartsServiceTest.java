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
package com.auto1.pantera.asto.events;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.SchedulerException;
import org.awaitility.Awaitility;

/**
 * Test for {@link QuartsService}.
 * @since 1.17
 */
class QuartsServiceTest {

    /**
     * Quartz service to test.
     */
    private QuartsService service;

    @BeforeEach
    void init() {
        this.service = new QuartsService();
    }

    @Test
    void runsQuartsJobs() throws SchedulerException, InterruptedException {
        final TestConsumer consumer = new TestConsumer();
        final EventQueue<Character> queue = this.service.addPeriodicEventsProcessor(consumer, 3, 1);
        this.service.start();
        for (char sym = 'a'; sym <= 'z'; sym++) {
            queue.put(sym);
            if ((int) sym % 5 == 0) {
                Thread.sleep(1500);
            }
        }
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> consumer.cnt.get() == 26);
    }

    /**
     * Test consumer.
     * @since 1.17
     */
    static final class TestConsumer implements Consumer<Character> {

        /**
         * Count for accept method call.
         */
        private final AtomicInteger cnt = new AtomicInteger();

        @Override
        public void accept(final Character strings) {
            this.cnt.incrementAndGet();
        }
    }

}
