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
package com.auto1.pantera.composer.http.proxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.MapMessage;

/**
 * Test appender capturing the fields one logger emits (the MapMessage
 * payload plus the thread context), so a test can assert on the ECS fields
 * of a log line.
 *
 * @since 2.2.9
 */
final class LogCapture extends AbstractAppender implements AutoCloseable {

    /**
     * Captured payloads.
     */
    private final List<Map<String, Object>> events =
        Collections.synchronizedList(new ArrayList<>());

    /**
     * The captured logger, as the code under test obtains it.
     */
    private final Logger logger;

    /**
     * Level of the logger before the capture.
     */
    private final Level before;

    /**
     * Ctor.
     * @param logger Captured logger
     */
    private LogCapture(final Logger logger) {
        super("LogCapture-" + logger.getName(), null, null, true, Property.EMPTY_ARRAY);
        this.logger = logger;
        this.before = logger.getLevel();
    }

    /**
     * Start capturing a logger at DEBUG. The appender is attached to the
     * very logger instance the code under test logs through, so another
     * test reconfiguring log4j cannot detach it.
     * @param name Logger name
     * @return Capture; close it to stop
     */
    static LogCapture of(final String name) {
        final LogCapture capture = new LogCapture((Logger) LogManager.getLogger(name));
        capture.start();
        capture.logger.addAppender(capture);
        capture.logger.setLevel(Level.DEBUG);
        return capture;
    }

    /**
     * Payloads whose {@code event.action} equals the given action.
     * @param action ECS event.action
     * @return Payloads
     */
    List<Map<String, Object>> action(final String action) {
        final List<Map<String, Object>> out = new ArrayList<>();
        synchronized (this.events) {
            for (final Map<String, Object> event : this.events) {
                if (action.equals(String.valueOf(event.get("event.action")))) {
                    out.add(event);
                }
            }
        }
        return out;
    }

    @Override
    public void append(final LogEvent event) {
        if (event.getMessage() instanceof MapMessage<?, ?> map) {
            final Map<String, Object> data = new HashMap<>();
            map.getData().forEach((key, value) -> data.put(String.valueOf(key), value));
            // The ECS layout writes MDC-owned fields (trace.id, ...) from the
            // context data, which wins over a payload field of the same name.
            data.putAll(event.getContextData().toMap());
            this.events.add(data);
        }
    }

    @Override
    public void close() {
        this.logger.removeAppender(this);
        this.logger.setLevel(this.before);
        this.stop();
    }
}
