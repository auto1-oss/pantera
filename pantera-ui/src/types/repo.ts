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
}

export interface RepoConfigEnvelope {
  repo: RepoConfig
}
