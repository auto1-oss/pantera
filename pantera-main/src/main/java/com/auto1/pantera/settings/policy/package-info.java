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
 * DB-backed, admin-editable security policy settings (request limits,
 * outbound egress, login throttling): loaders resolving {@code auth_settings}
 * rows, then {@code PANTERA_*} environment variables, then documented
 * defaults, exposed to consumers through live suppliers.
 *
 * @since 2.2.9
 */
package com.auto1.pantera.settings.policy;
