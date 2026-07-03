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
  storage?: string | RepoStorageFs | RepoStorageS3
  remotes?: RepoRemote[]
  /** Group members — array of repo name strings */
  members?: string[]
  cooldown?: RepoCooldown
  /** Allow unauthenticated reads. Default true for proxy/group, false for hosted. */
  anonymous_read?: boolean
  /** Allow unauthenticated writes. Default false everywhere. */
  anonymous_write?: boolean
}

export interface RepoConfigEnvelope {
  repo: RepoConfig
}
