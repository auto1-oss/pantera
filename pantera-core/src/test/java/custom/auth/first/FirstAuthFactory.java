/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package custom.auth.first;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.http.auth.PanteraAuthFactory;
import com.auto1.pantera.http.auth.AuthFactory;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;

import java.util.Optional;

/**
 * Test auth.
 * @since 1.3
 */
@PanteraAuthFactory("first")
public final class FirstAuthFactory implements AuthFactory {

    @Override
    public Authentication getAuthentication(final YamlMapping conf) {
        return new FirstAuth();
    }

    public static class FirstAuth implements Authentication {
        @Override
        public Optional<AuthUser> user(String username, String password) {
            return Optional.empty();
        }
    }
}
