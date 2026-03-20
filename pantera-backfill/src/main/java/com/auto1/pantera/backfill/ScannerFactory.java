/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.backfill;

/**
 * Factory that maps repository type strings to {@link Scanner} implementations.
 *
 * @since 1.20.13
 */
public final class ScannerFactory {

    /**
     * Private ctor to prevent instantiation.
     */
    private ScannerFactory() {
    }

    /**
     * Create a scanner for the given repository type.
     *
     * <p>Accepts both plain types (e.g. {@code "maven"}) and proxy variants
     * (e.g. {@code "maven-proxy"}). The raw type string is passed through to
     * the scanner so that the correct {@code repo_type} value is stored in
     * the database (matching production).</p>
     *
     * @param type Repository type string, raw from YAML
     *     (e.g. "maven", "docker-proxy", "php")
     * @return Scanner implementation for the given type
     * @throws IllegalArgumentException If the type is not recognized
     */
    public static Scanner create(final String type) {
        final String lower = type.toLowerCase(java.util.Locale.ROOT);
        final Scanner scanner;
        switch (lower) {
            case "maven":
            case "maven-proxy":
                scanner = new MavenScanner(lower);
                break;
            case "gradle":
            case "gradle-proxy":
                scanner = new MavenScanner(lower);
                break;
            case "docker":
                scanner = new DockerScanner(lower, false);
                break;
            case "docker-proxy":
                scanner = new DockerScanner(lower, true);
                break;
            case "npm":
                scanner = new NpmScanner(false);
                break;
            case "npm-proxy":
                scanner = new NpmScanner(true);
                break;
            case "pypi":
            case "pypi-proxy":
                scanner = new PypiScanner(lower);
                break;
            case "go":
            case "go-proxy":
                scanner = new GoScanner(lower);
                break;
            case "helm":
            case "helm-proxy":
                scanner = new HelmScanner();
                break;
            case "composer":
            case "composer-proxy":
            case "php":
            case "php-proxy":
                scanner = new ComposerScanner(lower);
                break;
            case "file":
            case "file-proxy":
                scanner = new FileScanner(lower);
                break;
            case "deb":
            case "deb-proxy":
            case "debian":
            case "debian-proxy":
                scanner = new DebianScanner();
                break;
            case "gem":
            case "gem-proxy":
            case "gems":
                scanner = new GemScanner();
                break;
            default:
                throw new IllegalArgumentException(
                    String.format("Unknown repository type: %s", type)
                );
        }
        return scanner;
    }
}
