/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package  com.auto1.pantera.conan;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.conan.http.ConanSlice;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.slice.LoggingSlice;
import com.auto1.pantera.security.policy.PolicyByUsername;
import com.auto1.pantera.vertx.VertxSliceServer;
import io.vertx.reactivex.core.Vertx;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Main class.
 * @since 0.1
 */
public final class Cli {

    /**
     * Pantera conan username for basic auth.
     */
    public static final String USERNAME = "demo_login";

    /**
     * Pantera conan password for basic auth.
     */
    public static final String PASSWORD = "demo_password";

    /**
     * Fake demo auth token.
     */
    public static final String DEMO_TOKEN = "fake_demo_token";

    /**
     * TCP Port for Conan server. Default is 9300.
     */
    private static final int CONAN_PORT = 9300;

    /**
     * Private constructor for main class.
     */
    private Cli() {
    }

    /**
     * Entry point.
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        final Path path = Paths.get("/home/user/.conan_server/data");
        final Storage storage = new FileStorage(path);
        final ConanRepo repo = new ConanRepo(storage);
        repo.batchUpdateIncrementally(Key.ROOT);
        final Vertx vertx = Vertx.vertx();
        final ItemTokenizer tokenizer = new ItemTokenizer(vertx.getDelegate());
        try (VertxSliceServer server =
            new VertxSliceServer(
                vertx, new LoggingSlice(
                    new ConanSlice(storage, new PolicyByUsername(Cli.USERNAME),
                    new Authentication.Single(Cli.USERNAME, Cli.PASSWORD),
                    new ConanSlice.FakeAuthTokens(Cli.DEMO_TOKEN, Cli.USERNAME), tokenizer, "*")
            ), Cli.CONAN_PORT)) {
            server.start();
        }
    }
}
