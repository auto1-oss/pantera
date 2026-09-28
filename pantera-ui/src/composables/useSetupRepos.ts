import { listRepos } from '@/api/repos'
import type { RepoListItem } from '@/types'

const PAGE_SIZE = 100
const MAX_PAGES = 50

/** Every repository the caller can read, across all pages. */
export async function listAllRepos(): Promise<RepoListItem[]> {
  const all: RepoListItem[] = []
  for (let page = 0; page < MAX_PAGES; page++) {
    const resp = await listRepos({ page, size: PAGE_SIZE })
    all.push(...resp.items)
    if (!resp.hasMore) break
  }
  return all
}
