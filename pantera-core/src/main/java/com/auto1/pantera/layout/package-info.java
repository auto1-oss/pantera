/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */

/**
 * Storage layout implementations for different repository types.
 * This package provides a unified way to organize artifacts in storage
 * across all backend types (FS, S3, etc.).
 *
 * <p>Each repository type has its own layout implementation that defines
 * how artifacts and metadata should be organized in storage.</p>
 *
 * <h2>Supported Layouts:</h2>
 * <ul>
 *   <li><b>Maven:</b> {@code <repo>/<groupId>/<artifactId>/<version>/}</li>
 *   <li><b>Python:</b> {@code <repo>/<name>/<version>/}</li>
 *   <li><b>Helm:</b> {@code <repo>/<chart_name>/}</li>
 *   <li><b>File:</b> {@code <repo>/<upload_path>/}</li>
 *   <li><b>NPM:</b> {@code <repo>/<name>/-/} or {@code <repo>/@<scope>/<name>/-/}</li>
 *   <li><b>Gradle:</b> {@code <repo>/<groupId>/<artifactId>/<version>/}</li>
 *   <li><b>Composer:</b> {@code <repo>/<vendor>/<package>/<version>/}</li>
 * </ul>
 *
 * @since 1.0
 */
package com.artipie.layout;
