package keycloak;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.core.Response;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * Keycloak docker initializer.
 * Initializes docker image: quay.io/keycloak/keycloak:26.0.2
 * As follows:
 * 1. Creates new realm
 * 2. Creates new role
 * 3. Creates new client application
 * 4. Creates new client's application role.
 * 5. Creates new user with realm role and client application role.
 */
public class KeycloakDockerInitializer {
    /**
     * Keycloak url.
     */
    private static final String KEYCLOAK_URL = "https://localhost:8443";

    /**
     * Keycloak admin login.
     */
    private final static String KEYCLOAK_ADMIN_LOGIN = "admin";

    /**
     * Keycloak admin password.
     */
    private final static String KEYCLOAK_ADMIN_PASSWORD = KEYCLOAK_ADMIN_LOGIN;

    /**
     * Realm name.
     */
    private final static String REALM = "test_realm";

    /**
     * Realm role name.
     */
    private final static String REALM_ROLE = "role_realm";

    /**
     * Client role.
     */
    private final static String CLIENT_ROLE = "client_role";

    /**
     * Client application id.
     */
    private final static String CLIENT_ID = "test_client";

    /**
     * Client application password.
     */
    private final static String CLIENT_PASSWORD = "secret";

    /**
     * Test user id.
     */
    private final static String USER_ID = "user1";

    /**
     * Test user password.
     */
    private final static String USER_PASSWORD = "password";

    /**
     * Keycloak server url.
     */
    private final String url;

    /**
     * Path to truststore with Keycloak certificate.
     */
    private final String truststorePath;

    /**
     * Truststore password.
     */
    private final String truststorePassword;

    /**
     * Start point of application.
     * @param args Arguments, can contains keycloak server url
     */
    public static void main(final String[] args) {
        final String url;
        final String truststore;
        final String password;
        if (!Objects.isNull(args) && args.length >= 3) {
            url = args[0];
            truststore = args[1];
            password = args[2];
        } else {
            url = KEYCLOAK_URL;
            truststore = System.getProperty("javax.net.ssl.trustStore");
            password = System.getProperty("javax.net.ssl.trustStorePassword", "");
        }
        new KeycloakDockerInitializer(url, truststore, password).init();
    }

    public KeycloakDockerInitializer(final String url, final String truststore, final String password) {
        this.url = url;
        this.truststorePath = truststore;
        this.truststorePassword = password;
    }

    /**
     * Using admin connection to keycloak server initializes keycloak instance.
     * Includes retry logic and better error handling for connection issues.
     */
    public void init() {
        Keycloak keycloak = null;
        try {
            keycloak = adminSessionWithRetry();
            createRealm(keycloak);
            createRealmRole(keycloak);
            createClient(keycloak);
            createClientRole(keycloak);
            createUserNew(keycloak);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for Keycloak", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Keycloak: " + e.getMessage(), e);
        } finally {
            if (keycloak != null) {
                keycloak.close();
            }
        }
    }

    private Keycloak adminSessionWithRetry() throws InterruptedException {
        final int maxAttempts = 6;
        final long delayMs = 5_000L;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Keycloak keycloak = null;
            try {
                Thread.sleep(2_000L);
                keycloak = buildKeycloakClient();
                keycloak.serverInfo().getInfo();
                return keycloak;
            } catch (ForbiddenException | ProcessingException ex) {
                if (keycloak != null) {
                    keycloak.close();
                }
                if (attempt == maxAttempts) {
                    throw new RuntimeException("Unable to obtain admin session from Keycloak after "
                        + maxAttempts + " attempts", ex);
                }
            } catch (RuntimeException ex) {
                if (keycloak != null) {
                    keycloak.close();
                }
                if (attempt == maxAttempts) {
                    throw ex;
                }
            }
            Thread.sleep(delayMs);
        }
        throw new IllegalStateException("Failed to obtain Keycloak admin session");
    }

    private Keycloak buildKeycloakClient() {
        try {
            final ResteasyClientBuilder builder = (ResteasyClientBuilder) ResteasyClientBuilder.newBuilder();
            builder.sslContext(sslContext());
            builder.hostnameVerification(ResteasyClientBuilder.HostnameVerificationPolicy.ANY);
            return KeycloakBuilder.builder()
                .serverUrl(this.url)
                .realm("master")
                .username(KEYCLOAK_ADMIN_LOGIN)
                .password(KEYCLOAK_ADMIN_PASSWORD)
                .clientId("admin-cli")
                .resteasyClient(builder.build())
                .build();
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new RuntimeException("Unable to build Keycloak admin client", ex);
        }
    }

    private SSLContext sslContext() throws Exception {
        if (this.truststorePath == null || this.truststorePath.isEmpty()) {
            return SSLContext.getDefault();
        }
        final Path path = Paths.get(this.truststorePath);
        final KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, this.truststorePassword.toCharArray());
        }
        final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), new SecureRandom());
        return context;
    }

    /**
     * Creates new realm 'test_realm'.
     * @param keycloak Keycloak instance.
     */
    private void createRealm(final Keycloak keycloak) {
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm(REALM);
        realm.setEnabled(true);
        keycloak.realms().create(realm);
    }

    /**
     * Creates new role 'role_realm' in realm 'test_realm'
     * @param keycloak Keycloak instance.
     */
    private void createRealmRole(final Keycloak keycloak) {
        keycloak.realm(REALM).roles().create(new RoleRepresentation(REALM_ROLE, null, false));
    }

    /**
     * Creates new client application with ID 'test_client' and password 'secret'.
     * @param keycloak Keycloak instance.
     */
    private void createClient(final Keycloak keycloak) {
        ClientRepresentation client = new ClientRepresentation();
        client.setEnabled(true);
        client.setPublicClient(false);
        client.setDirectAccessGrantsEnabled(true);
        client.setStandardFlowEnabled(false);
        client.setClientId(CLIENT_ID);
        client.setProtocol("openid-connect");
        client.setSecret(CLIENT_PASSWORD);
        client.setAuthorizationServicesEnabled(true);
        client.setServiceAccountsEnabled(true);
        keycloak.realm(REALM).clients().create(client);
    }

    /**
     * Creates new client's application role 'client_role' for client application.
     * @param keycloak Keycloak instance.
     */
    private void createClientRole(final Keycloak keycloak) {
        RoleRepresentation clientRoleRepresentation = new RoleRepresentation();
        clientRoleRepresentation.setName(CLIENT_ROLE);
        clientRoleRepresentation.setClientRole(true);
        keycloak.realm(REALM)
            .clients()
            .findByClientId(CLIENT_ID)
            .forEach(clientRepresentation ->
                keycloak.realm(REALM)
                    .clients()
                    .get(clientRepresentation.getId())
                    .roles()
                    .create(clientRoleRepresentation)
            );
    }

    /**
     * Creates new user with realm role and client application role.
     * @param keycloak
     */
    private void createUserNew(final Keycloak keycloak) {
        // Define user
        UserRepresentation user = new UserRepresentation();
        user.setEnabled(true);
        user.setUsername(USER_ID);
        user.setFirstName("First");
        user.setLastName("Last");
        user.setEmail(USER_ID + "@localhost");

        // Get realm
        RealmResource realmResource = keycloak.realm(REALM);
        UsersResource usersRessource = realmResource.users();

        // Create user (requires manage-users role)
        Response response = usersRessource.create(user);
        String userId = response.getLocation().getPath().substring(response.getLocation().getPath().lastIndexOf('/') + 1);

        // Define password credential
        CredentialRepresentation passwordCred = new CredentialRepresentation();
        passwordCred.setTemporary(false);
        passwordCred.setType(CredentialRepresentation.PASSWORD);
        passwordCred.setValue(USER_PASSWORD);

        UserResource userResource = usersRessource.get(userId);

        // Set password credential
        userResource.resetPassword(passwordCred);

        // Get realm role "tester" (requires view-realm role)
        RoleRepresentation testerRealmRole = realmResource
            .roles()
            .get(REALM_ROLE)
            .toRepresentation();

        // Assign realm role tester to user
        userResource.roles().realmLevel().add(Collections.singletonList(testerRealmRole));

        // Get client
        ClientRepresentation appClient = realmResource
            .clients()
            .findByClientId(CLIENT_ID)
            .get(0);

        // Get client level role (requires view-clients role)
        RoleRepresentation userClientRole = realmResource
            .clients()
            .get(appClient.getId())
            .roles()
            .get(CLIENT_ROLE)
            .toRepresentation();

        // Assign client level role to user
        userResource
            .roles()
            .clientLevel(appClient.getId())
            .add(Collections.singletonList(userClientRole));
    }
}
