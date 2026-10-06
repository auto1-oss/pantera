// Mirrors RepositoryHandler.validRepoName on the server so the create form
// can refuse a name before the round-trip. Keep the two in sync.
const PATTERN = /^[A-Za-z0-9][A-Za-z0-9._/-]*$/

/** Null when the name is acceptable to the server, else the reason. */
export function validateRepoName(name: string): string | null {
  if (!name || !name.trim()) return 'Name is required'
  if (name.length > 200) return 'Name must be at most 200 characters'
  if (!/^[A-Za-z0-9]/.test(name)) return 'Name must start with a letter or digit'
  if (!PATTERN.test(name)) return 'Only letters, digits, ".", "_", "-" and "/" are allowed'
  if (name.includes('..')) return 'Name must not contain ".."'
  if (name.endsWith('/')) return 'Name must not end with a slash'
  return null
}
