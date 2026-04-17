/**
 * Single source of truth for repository type display.
 *
 * Each technology gets a unique color derived from its real logo/brand:
 *   Maven   — #C71A36 (red, from Maven logo)
 *   Docker  — #2496ED (blue, from Docker logo)
 *   npm     — #CB3837 (dark red, from npm logo) → shifted to #CC4444 to differ from Maven
 *   PyPI    — #3775A9 (blue) → shifted to #0C7C59 teal-green to differ from Docker
 *   Helm    — #0F1689 (navy, from Helm logo) → #277CC5 medium blue
 *   Go      — #00ADD8 (cyan, from Go logo)
 *   NuGet   — #6F42C1 (purple, from NuGet logo)
 *   Debian  — #D70751 (magenta, from Debian logo)
 *   RPM     — #EE0000 (red) → #E06020 to differ
 *   Conda   — #44A833 (green, from Anaconda logo)
 *   RubyGems— #E9573F (ruby red, from RubyGems logo) → #CC6699 pink
 *   Conan   — #6699CB (steel blue, from Conan logo)
 *   Hex     — #6E4A7E (purple, from Hex logo)
 *   PHP     — #777BB4 (lavender, from PHP logo)
 *   File    — #8B8B8B (gray)
 *   Binary  — #6B7280 (gray)
 */

interface TechInfo {
  label: string
  icon: string
  /** Hex color for the technology — used for dot/bar/accent */
  color: string
  /** Tailwind bg for icon container */
  bgClass: string
  /** Tailwind text for icon */
  textClass: string
}

const TECH_MAP: Record<string, TechInfo> = {
  maven:    { label: 'Maven',    icon: 'pi pi-box',    color: '#C71A36', bgClass: 'bg-[#C71A36]/10', textClass: 'text-[#C71A36]' },
  gradle:   { label: 'Gradle',   icon: 'pi pi-box',    color: '#02303A', bgClass: 'bg-[#02303A]/15', textClass: 'text-[#5A9A8C]' },
  docker:   { label: 'Docker',   icon: 'pi pi-server', color: '#2496ED', bgClass: 'bg-[#2496ED]/10', textClass: 'text-[#2496ED]' },
  npm:      { label: 'npm',      icon: 'pi pi-box',    color: '#CC4444', bgClass: 'bg-[#CC4444]/10', textClass: 'text-[#CC4444]' },
  pypi:     { label: 'PyPI',     icon: 'pi pi-box',    color: '#0C7C59', bgClass: 'bg-[#0C7C59]/10', textClass: 'text-[#0C7C59]' },
  helm:     { label: 'Helm',     icon: 'pi pi-box',    color: '#277CC5', bgClass: 'bg-[#277CC5]/10', textClass: 'text-[#277CC5]' },
  go:       { label: 'Go',       icon: 'pi pi-box',    color: '#00ADD8', bgClass: 'bg-[#00ADD8]/10', textClass: 'text-[#00ADD8]' },
  nuget:    { label: 'NuGet',    icon: 'pi pi-box',    color: '#6F42C1', bgClass: 'bg-[#6F42C1]/10', textClass: 'text-[#6F42C1]' },
  debian:   { label: 'Debian',   icon: 'pi pi-box',    color: '#D70751', bgClass: 'bg-[#D70751]/10', textClass: 'text-[#D70751]' },
  deb:      { label: 'Debian',   icon: 'pi pi-box',    color: '#D70751', bgClass: 'bg-[#D70751]/10', textClass: 'text-[#D70751]' },
  rpm:      { label: 'RPM',      icon: 'pi pi-box',    color: '#E06020', bgClass: 'bg-[#E06020]/10', textClass: 'text-[#E06020]' },
  conda:    { label: 'Conda',    icon: 'pi pi-box',    color: '#44A833', bgClass: 'bg-[#44A833]/10', textClass: 'text-[#44A833]' },
  gem:      { label: 'RubyGems', icon: 'pi pi-box',    color: '#CC6699', bgClass: 'bg-[#CC6699]/10', textClass: 'text-[#CC6699]' },
  conan:    { label: 'Conan',    icon: 'pi pi-box',    color: '#6699CB', bgClass: 'bg-[#6699CB]/10', textClass: 'text-[#6699CB]' },
  hex:      { label: 'Hex',      icon: 'pi pi-box',    color: '#6E4A7E', bgClass: 'bg-[#6E4A7E]/10', textClass: 'text-[#6E4A7E]' },
  hexpm:    { label: 'Hex',      icon: 'pi pi-box',    color: '#6E4A7E', bgClass: 'bg-[#6E4A7E]/10', textClass: 'text-[#6E4A7E]' },
  php:      { label: 'PHP',      icon: 'pi pi-box',    color: '#777BB4', bgClass: 'bg-[#777BB4]/10', textClass: 'text-[#777BB4]' },
  file:     { label: 'File',     icon: 'pi pi-folder', color: '#8B8B8B', bgClass: 'bg-gray-500/10',  textClass: 'text-gray-400' },
  binary:   { label: 'Binary',   icon: 'pi pi-file',   color: '#6B7280', bgClass: 'bg-gray-500/10',  textClass: 'text-gray-400' },
}

const SUBTYPE_LABELS: Record<string, string> = {
  proxy: 'Proxy',
  group: 'Group',
}

const DEFAULT_TECH: TechInfo = {
  label: 'Unknown', icon: 'pi pi-box', color: '#6B7280',
  bgClass: 'bg-gray-500/10', textClass: 'text-gray-400',
}

/** Extract base type and subtype from raw repo type.
 *  Handles both hyphens (npm-proxy) and underscores (npm_proxy). */
function parseType(raw: string): { base: string; subtype: string | null } {
  const lower = (raw ?? 'unknown').toLowerCase()
  for (const sfx of Object.keys(SUBTYPE_LABELS)) {
    if (lower.endsWith(`-${sfx}`) || lower.endsWith(`_${sfx}`)) {
      return { base: lower.slice(0, -(sfx.length + 1)), subtype: sfx }
    }
  }
  return { base: lower, subtype: null }
}

/** Get technology info. */
export function getTechInfo(raw: string): TechInfo {
  const { base } = parseType(raw)
  return TECH_MAP[base] ?? DEFAULT_TECH
}

/** Technology label (e.g. "npm", "Maven", "Docker"). */
export function techLabel(raw: string): string {
  return getTechInfo(raw).label
}

/** Subtype label (e.g. "Proxy", "Group", "Local"). */
export function subtypeLabel(raw: string): string {
  const { subtype } = parseType(raw)
  return subtype ? SUBTYPE_LABELS[subtype] ?? 'Local' : 'Local'
}

/** Full label (e.g. "Maven Proxy"). */
export function repoTypeLabel(raw: string): string {
  const tech = techLabel(raw)
  const sub = subtypeLabel(raw)
  return sub ? `${tech} ${sub}` : tech
}

/** Base label only (for filters). */
export function repoTypeBaseLabel(raw: string): string {
  return techLabel(raw)
}

/** Icon class. */
export function repoTypeIcon(raw: string): string {
  return getTechInfo(raw).icon
}

/** Tailwind color classes for icon container: "bg-xxx/10 text-xxx". */
export function repoTypeColorClass(raw: string): string {
  const info = getTechInfo(raw)
  return `${info.bgClass} ${info.textClass}`
}

/** Hex color for the technology (for bars, dots, inline styles). */
export function repoTypeColor(raw: string): string {
  return getTechInfo(raw).color
}

/** PrimeVue Tag severity — not ideal for brand colors, use sparingly. */
export function repoTypeSeverity(raw: string): string {
  const { subtype } = parseType(raw)
  if (subtype === 'proxy') return 'info'
  if (subtype === 'group') return 'warn'
  return 'secondary'
}

function capitalize(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1)
}

/** Filter dropdown options. */
export const REPO_TYPE_FILTERS = [
  { label: 'All Types', value: null },
  { label: 'Maven',    value: 'maven' },
  { label: 'Docker',   value: 'docker' },
  { label: 'npm',      value: 'npm' },
  { label: 'PyPI',     value: 'pypi' },
  { label: 'Helm',     value: 'helm' },
  { label: 'Go',       value: 'go' },
  { label: 'Gradle',   value: 'gradle' },
  { label: 'NuGet',    value: 'nuget' },
  { label: 'Debian',   value: 'debian' },
  { label: 'RPM',      value: 'rpm' },
  { label: 'Conda',    value: 'conda' },
  { label: 'RubyGems', value: 'gem' },
  { label: 'Conan',    value: 'conan' },
  { label: 'Hex',      value: 'hex' },
  { label: 'PHP',      value: 'php' },
  { label: 'File',     value: 'file' },
  { label: 'Binary',   value: 'binary' },
] as const

/** Create form options. */
export const REPO_TYPE_CREATE_OPTIONS = [
  { label: 'Maven (Local)',    value: 'maven' },
  { label: 'Maven (Proxy)',    value: 'maven-proxy' },
  { label: 'Maven (Group)',    value: 'maven-group' },
  { label: 'Docker (Local)',   value: 'docker' },
  { label: 'Docker (Proxy)',   value: 'docker-proxy' },
  { label: 'Docker (Group)',   value: 'docker-group' },
  { label: 'npm (Local)',      value: 'npm' },
  { label: 'npm (Proxy)',      value: 'npm-proxy' },
  { label: 'npm (Group)',      value: 'npm-group' },
  { label: 'PyPI (Local)',     value: 'pypi' },
  { label: 'PyPI (Proxy)',     value: 'pypi-proxy' },
  { label: 'PyPI (Group)',     value: 'pypi-group' },
  { label: 'Go (Local)',        value: 'go' },
  { label: 'Go (Proxy)',        value: 'go-proxy' },
  { label: 'Go (Group)',        value: 'go-group' },
  { label: 'Gradle (Local)',    value: 'gradle' },
  { label: 'Gradle (Proxy)',    value: 'gradle-proxy' },
  { label: 'Gradle (Group)',    value: 'gradle-group' },
  { label: 'Helm (Local)',     value: 'helm' },
  { label: 'NuGet (Local)',    value: 'nuget' },
  { label: 'Debian (Local)',   value: 'deb' },
  { label: 'RPM (Local)',      value: 'rpm' },
  { label: 'Conda (Local)',    value: 'conda' },
  { label: 'RubyGems (Local)', value: 'gem' },
  { label: 'Conan (Local)',    value: 'conan' },
  { label: 'Hex (Local)',      value: 'hexpm' },
  { label: 'PHP (Local)',      value: 'php' },
  { label: 'PHP (Proxy)',      value: 'php-proxy' },
  { label: 'PHP (Group)',      value: 'php-group' },
  { label: 'File (Local)',     value: 'file' },
  { label: 'File (Proxy)',     value: 'file-proxy' },
  { label: 'File (Group)',     value: 'file-group' },
  { label: 'Binary (Local)',   value: 'binary' },
] as const
