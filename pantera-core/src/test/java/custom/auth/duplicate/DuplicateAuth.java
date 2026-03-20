/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package custom.auth.duplicate;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.http.auth.PanteraAuthFactory;
import com.auto1.pantera.http.auth.AuthFactory;
import com.auto1.pantera.http.auth.Authentication;

import java.util.Optional;

/**
 * Test auth.
 */
@PanteraAuthFactory("first")
public final class DuplicateAuth implements AuthFactory {

    @Override
    public Authentication getAuthentication(final YamlMapping conf) {
        return (username, password) -> Optional.empty();
    }
}
