/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.timeout;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable timeout configuration with hierarchical override support.
 * Resolution order: per-remote > per-repo > global > defaults.
 *
 * @since 1.20.13
 */
public final class TimeoutSettings {

    public static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final Duration connectionTimeout;
    private final Duration idleTimeout;
    private final Duration requestTimeout;

    public TimeoutSettings(
        final Duration connectionTimeout,
        final Duration idleTimeout,
        final Duration requestTimeout
    ) {
        this.connectionTimeout = Objects.requireNonNull(connectionTimeout);
        this.idleTimeout = Objects.requireNonNull(idleTimeout);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
    }

    public static TimeoutSettings defaults() {
        return new TimeoutSettings(
            DEFAULT_CONNECTION_TIMEOUT, DEFAULT_IDLE_TIMEOUT, DEFAULT_REQUEST_TIMEOUT
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public Duration connectionTimeout() {
        return this.connectionTimeout;
    }

    public Duration idleTimeout() {
        return this.idleTimeout;
    }

    public Duration requestTimeout() {
        return this.requestTimeout;
    }

    public static final class Builder {
        private Duration connectionTimeout;
        private Duration idleTimeout;
        private Duration requestTimeout;

        public Builder connectionTimeout(final Duration val) {
            this.connectionTimeout = val;
            return this;
        }

        public Builder idleTimeout(final Duration val) {
            this.idleTimeout = val;
            return this;
        }

        public Builder requestTimeout(final Duration val) {
            this.requestTimeout = val;
            return this;
        }

        public TimeoutSettings buildWithParent(final TimeoutSettings parent) {
            return new TimeoutSettings(
                this.connectionTimeout != null
                    ? this.connectionTimeout : parent.connectionTimeout(),
                this.idleTimeout != null
                    ? this.idleTimeout : parent.idleTimeout(),
                this.requestTimeout != null
                    ? this.requestTimeout : parent.requestTimeout()
            );
        }

        public TimeoutSettings build() {
            return buildWithParent(TimeoutSettings.defaults());
        }
    }
}
