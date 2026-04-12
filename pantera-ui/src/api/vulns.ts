import { getApiClient } from './client'
import type {
  PaginatedResponse,
  VulnerabilitySummary,
  VulnerabilityReport,
  VulnerabilityFindingRow,
} from '@/types'

/**
 * GET /api/v1/vulnerabilities/summary
 * Returns one aggregated row per repository.
 */
export async function getVulnerabilitySummary(): Promise<VulnerabilitySummary[]> {
  const { data } = await getApiClient().get<{ items: VulnerabilitySummary[] }>(
    '/vulnerabilities/summary',
  )
  return data.items ?? []
}

/**
 * GET /api/v1/vulnerabilities/findings
 * Paginated list of individual CVE findings across all repositories.
 */
export async function getVulnerabilityFindings(
  params: {
    page?: number
    size?: number
    search?: string
    repo?: string
    severity?: string
    sort_by?: string
    sort_dir?: 'asc' | 'desc'
  } = {},
  signal?: AbortSignal,
): Promise<PaginatedResponse<VulnerabilityFindingRow>> {
  const { data } = await getApiClient().get<PaginatedResponse<VulnerabilityFindingRow>>(
    '/vulnerabilities/findings',
    { params, signal },
  )
  return data
}

/**
 * GET /api/v1/repositories/:name/vulnerabilities
 * All cached scan reports for a specific repository.
 */
export async function getRepoVulnerabilities(name: string): Promise<VulnerabilityReport[]> {
  const { data } = await getApiClient().get<{ items: VulnerabilityReport[] }>(
    `/repositories/${name}/vulnerabilities`,
  )
  return data.items ?? []
}

/**
 * GET /api/v1/repositories/:name/vulnerabilities/artifact?path=…
 * Cached scan report for a specific artifact.
 * Returns null when the artifact has not been scanned yet (404).
 */
export async function getArtifactVulnerabilities(
  name: string,
  path: string,
): Promise<VulnerabilityReport | null> {
  try {
    const { data } = await getApiClient().get<VulnerabilityReport>(
      `/repositories/${name}/vulnerabilities/artifact`,
      { params: { path } },
    )
    return data
  } catch (err: unknown) {
    if ((err as { response?: { status?: number } })?.response?.status === 404) {
      return null
    }
    throw err
  }
}

/**
 * POST /api/v1/repositories/:name/vulnerabilities/scan?path=…
 * Trigger (or force-refresh) a vulnerability scan for a specific artifact.
 * Returns the fresh scan report.
 */
export async function triggerVulnerabilityScan(
  name: string,
  path: string,
): Promise<VulnerabilityReport> {
  const { data } = await getApiClient().post<VulnerabilityReport>(
    `/repositories/${name}/vulnerabilities/scan`,
    null,
    { params: { path } },
  )
  return data
}

export interface ScanAllResponse {
  repo_name: string
  enqueued: number
  message: string
}

/**
 * POST /api/v1/repositories/:name/vulnerabilities/scan-all
 * Enqueue a background scan for every artifact in the repository.
 * Returns 202 immediately with { enqueued, message }.
 * Poll getRepoVulnerabilities() or getVulnerabilitySummary() for progress.
 */
export async function triggerRepoScan(name: string): Promise<ScanAllResponse> {
  const { data } = await getApiClient().post<ScanAllResponse>(
    `/repositories/${name}/vulnerabilities/scan-all`,
  )
  return data
}
