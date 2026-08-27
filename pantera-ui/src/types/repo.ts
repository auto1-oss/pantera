export type RepoStorageFs = { type: 'fs'; path?: string }
export type RepoStorageS3 = { type: 's3'; bucket?: string; region?: string; endpoint?: string }

export interface RepoRemote {
  url: string
  username?: string
  password?: string
}

export interface RepoCooldown {
  duration?: string
}

export interface RepoConfig {
  type: string
  /**
   * Client-facing base URL of this repository — the absolute prefix Pantera
   * embeds in links it emits (npm `dist.tarball`, Composer provider URLs,
   * Helm chart URLs). Distinct from `remotes[].url`, which is the UPSTREAM a
   * proxy fetches from. When set it wins over the `client_base_url` admin
   * setting and over `Host`/`X-Forwarded-*` derivation for this repository.
   */
  url?: string
  storage?: string | RepoStorageFs | RepoStorageS3
  remotes?: RepoRemote[]
  /** Group members — array of repo name strings */
  members?: string[]
  cooldown?: RepoCooldown
  /** Allow unauthenticated reads. Default true for proxy/group, false for hosted. */
  anonymous_read?: boolean
  /** Allow unauthenticated writes. Default false everywhere. */
  anonymous_write?: boolean
  /**
   * Keys this UI does not model (e.g. `path`, the deb/rpm `settings` block).
   * They are round-tripped verbatim by RepoConfigForm: the server stores the
   * config as one JSONB document and PUT replaces it wholesale, so any key
   * the form dropped would be permanently lost on save.
   */
  [key: string]: unknown
}

export interface RepoConfigEnvelope {
  repo: RepoConfig
}
