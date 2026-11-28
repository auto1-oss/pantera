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

/**
 * The sign-in failures report: who fails to sign in, with what, and from
 * where. Rejected API keys, failed password logins and unconfirmed
 * second factors are noted in memory on the request path and written to
 * the store once a minute.
 *
 * @since 2.2.15
 */
package com.auto1.pantera.auth.signin;
