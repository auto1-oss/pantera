/**
 * Canonical schemas for each auth provider type.
 *
 * These schemas drive both the "Add Provider" dialog and the per-card
 * Edit form in AuthProvidersView.vue. The user can only set fields that
 * are declared here — no free-form key/value entry — so a typo can't
 * silently break authentication.
 *
 * Field types:
 *   - text:        plain string input
 *   - password:    string input with masking + secret-strip-on-save
 *   - url:         text input with type=url for browser validation
 *   - chips:       string[]; rendered as a Chips widget (allowed-groups,
 *                  user-domains, scope when space-separated)
 *   - grouproles:  array of {idpGroup, panteraRole} pairs (group-roles)
 *
 * Backward compatibility: any keys present in the existing config that
 * are NOT declared here are preserved on save (the AuthProvidersView
 * merges them back in via a hidden "extras" map). They're displayed in
 * a collapsed "Custom fields" section so admins can see them but the
 * intended path is to migrate them into the schema.
 */

export type ProviderFieldType =
  | 'text'
  | 'password'
  | 'url'
  | 'chips'
  | 'grouproles'

export interface ProviderField {
  /** Config JSON key (e.g. "client-id") */
  key: string
  /** Human-readable label shown above the input */
  label: string
  /** Field renderer */
  type: ProviderFieldType
  /** Required for create — UI blocks submit if empty */
  required?: boolean
  /** Placeholder text */
  placeholder?: string
  /** Help text shown beneath the field */
  help?: string
}

export interface ProviderSchema {
  type: string
  /** Display name in the type select */
  label: string
  /** Short description shown in the create dialog */
  description: string
  /** Default priority assigned on creation */
  defaultPriority: number
  /** Whether this type can be created via the UI (false = bootstrap-only) */
  creatable: boolean
  /** Whether this type can be disabled or deleted (false = protected) */
  protected: boolean
  /** Whether the provider supports SSO group → role mapping */
  supportsGroupRoles: boolean
  /** Field schema for the create + edit forms */
  fields: ProviderField[]
}

const OKTA: ProviderSchema = {
  type: 'okta',
  label: 'Okta (OIDC SSO)',
  description: 'OpenID Connect single sign-on against Okta. Requires an Okta '
    + 'OIDC application with a client ID and secret.',
  defaultPriority: 10,
  creatable: true,
  protected: false,
  supportsGroupRoles: true,
  fields: [
    {
      key: 'issuer',
      label: 'Issuer URL',
      type: 'url',
      required: true,
      placeholder: 'https://your-org.okta.com',
      help: 'Your Okta org URL. Pantera appends /oauth2/v1/token automatically.',
    },
    {
      key: 'client-id',
      label: 'Client ID',
      type: 'text',
      required: true,
      placeholder: '0oa1abc2def3ghi4j5k6',
    },
    {
      key: 'client-secret',
      label: 'Client Secret',
      type: 'password',
      required: true,
      help: 'From the Okta application General tab. Stored encrypted at rest.',
    },
    {
      key: 'redirect-uri',
      label: 'Redirect URI',
      type: 'url',
      required: true,
      placeholder: 'http://localhost:8090/auth/callback',
      help: 'Must match exactly the URI configured in your Okta app.',
    },
    {
      key: 'scope',
      label: 'Scopes',
      type: 'text',
      placeholder: 'openid email profile groups',
      help: 'Space-separated OIDC scopes. "groups" is required for group-based access control.',
    },
    {
      key: 'groups-claim',
      label: 'Groups Claim',
      type: 'text',
      placeholder: 'groups',
      help: 'JWT claim name carrying the user\'s group memberships.',
    },
    {
      key: 'allowed-groups',
      label: 'Allowed Groups (access gate)',
      type: 'chips',
      help: 'If set, the id_token must carry at least one of these groups or '
        + 'login is rejected. Leave empty to allow any authenticated Okta user '
        + '(insecure for production).',
    },
    {
      key: 'group-roles',
      label: 'Group → Role Mapping',
      type: 'grouproles',
      help: 'Maps an Okta group to a Pantera role. The Pantera role must already exist.',
    },
  ],
}

const KEYCLOAK: ProviderSchema = {
  type: 'keycloak',
  label: 'Keycloak (OIDC SSO)',
  description: 'OpenID Connect single sign-on against Keycloak. Requires a '
    + 'Keycloak realm and client.',
  defaultPriority: 20,
  creatable: true,
  protected: false,
  supportsGroupRoles: true,
  fields: [
    {
      key: 'url',
      label: 'Keycloak URL',
      type: 'url',
      required: true,
      placeholder: 'http://keycloak:8080',
    },
    {
      key: 'realm',
      label: 'Realm',
      type: 'text',
      required: true,
      placeholder: 'pantera',
    },
    {
      key: 'client-id',
      label: 'Client ID',
      type: 'text',
      required: true,
      placeholder: 'pantera',
    },
    {
      key: 'client-password',
      label: 'Client Secret',
      type: 'password',
      required: true,
      help: 'From the Keycloak client Credentials tab.',
    },
    {
      key: 'redirect-uri',
      label: 'Redirect URI',
      type: 'url',
      placeholder: 'http://localhost:8090/auth/callback',
    },
    {
      key: 'scope',
      label: 'Scopes',
      type: 'text',
      placeholder: 'openid email profile groups',
    },
    {
      key: 'groups-claim',
      label: 'Groups Claim',
      type: 'text',
      placeholder: 'groups',
    },
    {
      key: 'allowed-groups',
      label: 'Allowed Groups (access gate)',
      type: 'chips',
      help: 'If set, the id_token must carry at least one of these groups or '
        + 'login is rejected. Leave empty to allow any authenticated Keycloak user.',
    },
    {
      key: 'group-roles',
      label: 'Group → Role Mapping',
      type: 'grouproles',
      help: 'Maps a Keycloak group to a Pantera role. The Pantera role must already exist.',
    },
    {
      key: 'user-domains',
      label: 'User Domains',
      type: 'chips',
      help: 'Email domain suffixes accepted from this provider (e.g. @example.com).',
    },
  ],
}

const LOCAL: ProviderSchema = {
  type: 'local',
  label: 'Local (username/password)',
  description: 'Built-in username/password authentication against the users '
    + 'table. Required for fallback access; cannot be disabled or deleted.',
  defaultPriority: 0,
  creatable: false,
  protected: true,
  supportsGroupRoles: false,
  fields: [],
}

const JWT_PASSWORD: ProviderSchema = {
  type: 'jwt-password',
  label: 'JWT API Token',
  description: 'API token verification (Bearer JWT). Required for the REST '
    + 'API; cannot be disabled or deleted.',
  defaultPriority: 1,
  creatable: false,
  protected: true,
  supportsGroupRoles: false,
  fields: [],
}

export const PROVIDER_SCHEMAS: Record<string, ProviderSchema> = {
  okta: OKTA,
  keycloak: KEYCLOAK,
  local: LOCAL,
  'jwt-password': JWT_PASSWORD,
}

/** Schemas that can be created via the UI (excludes bootstrap-only types). */
export const CREATABLE_SCHEMAS: ProviderSchema[] = Object.values(PROVIDER_SCHEMAS)
  .filter(s => s.creatable)

/** Set of provider types that cannot be disabled or deleted. */
export const PROTECTED_TYPES: Set<string> = new Set(
  Object.values(PROVIDER_SCHEMAS).filter(s => s.protected).map(s => s.type)
)

/** Look up a schema by type, returning a permissive default if unknown. */
export function schemaFor(type: string): ProviderSchema {
  return PROVIDER_SCHEMAS[type] ?? {
    type,
    label: type,
    description: `Unknown provider type: ${type}`,
    defaultPriority: 100,
    creatable: false,
    protected: false,
    supportsGroupRoles: false,
    fields: [],
  }
}
