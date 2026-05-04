import { getApiClient } from './client'

/**
 * Server-side catalog of runtime-tunable keys validated by
 * {@code SettingsHandler.validateRuntime}. Kept as a string-literal
 * union so the UI gets autocomplete + a compile error if a typo is
 * introduced when a new key is added on the server.
 */
export type RuntimeSettingKey =
  | 'http_client.protocol'
  | 'http_client.http2_max_pool_size'
  | 'http_client.http2_multiplexing_limit'
  | 'prefetch.enabled'
  | 'prefetch.concurrency.global'
  | 'prefetch.concurrency.per_upstream'
  | 'prefetch.queue.capacity'
  | 'prefetch.worker_threads'
  | 'prefetch.circuit_breaker.drop_threshold_per_sec'
  | 'prefetch.circuit_breaker.window_seconds'
  | 'prefetch.circuit_breaker.disable_minutes'

/**
 * Decoded runtime value. Strings (protocol), numbers (most knobs), and
 * booleans (prefetch.enabled) all flow through the same channel.
 */
export type RuntimeValue = string | number | boolean

/**
 * Source of a runtime value as reported by the server.
 *  - {@code "db"}: a row exists in the {@code settings} table.
 *  - {@code "default"}: spec default (no DB row).
 */
export type RuntimeSource = 'db' | 'default'

/**
 * One entry as returned by the server. The server emits {@code value}
 * and {@code default} as JSON-literal strings (e.g. {@code "\"h2\""},
 * {@code "100"}, {@code "true"}) so they round-trip through {@code Json}
 * unchanged. The wrapper below decodes them into native JS values for
 * easy consumption by Vue components.
 */
export interface RuntimeSettingRaw {
  value: string
  default: string
  source: RuntimeSource
}

/** Decoded form: {@code value} and {@code default} are native JS. */
export interface RuntimeSetting {
  key: RuntimeSettingKey
  value: RuntimeValue
  default: RuntimeValue
  source: RuntimeSource
}

/** {@code GET /api/v1/settings/runtime} response. */
export type RuntimeSettingsList = Record<string, RuntimeSettingRaw>

/**
 * Decode a JSON-literal string (as the server emits) into a native
 * value. The server uses Vert.x {@code Json.encode}/{@code defaultRepr}
 * so {@code "\"h2\""} -> "h2", {@code "100"} -> 100, {@code "true"} -> true.
 *
 * If the literal is malformed (shouldn't happen in normal operation),
 * we return the raw string so the UI can still display it without
 * crashing — callers can detect this by typeof !== expected.
 */
export function decodeRuntimeValue(repr: string): RuntimeValue {
  try {
    return JSON.parse(repr) as RuntimeValue
  } catch {
    return repr
  }
}

/**
 * Decode a server entry into the consumer-friendly shape used by
 * the admin UI. The string-keyed record from the server becomes a
 * stable array sorted by key so v-for renders deterministically.
 */
function decodeEntry(key: string, raw: RuntimeSettingRaw): RuntimeSetting {
  return {
    key: key as RuntimeSettingKey,
    value: decodeRuntimeValue(raw.value),
    default: decodeRuntimeValue(raw.default),
    source: raw.source,
  }
}

/**
 * GET /api/v1/settings/runtime — list all 11 runtime-tunable keys
 * with their current value, spec default, and source. Decoded into
 * native JS values.
 */
export async function listRuntimeSettings(): Promise<RuntimeSetting[]> {
  const { data } = await getApiClient().get<RuntimeSettingsList>(
    '/settings/runtime',
  )
  return Object.entries(data)
    .map(([k, v]) => decodeEntry(k, v))
    .sort((a, b) => a.key.localeCompare(b.key))
}

/**
 * PATCH /api/v1/settings/runtime/:key — update a single runtime-tunable.
 * Body: {@code {"value": <typed>}}. Admin-only on the server side.
 *
 * Returns the canonical {@code {key, value, source}} envelope from the
 * server so the caller can re-render without a follow-up GET.
 */
export async function patchRuntimeSetting(
  key: RuntimeSettingKey,
  value: RuntimeValue,
): Promise<RuntimeSetting> {
  const { data } = await getApiClient().patch<{
    key: string
    value: string
    source: RuntimeSource
  }>(`/settings/runtime/${key}`, { value })
  // Server PATCH response shape includes {key, value (json-literal), source}
  // but not the spec default. Pull the default from the catalog so the UI
  // can keep showing it without an extra round-trip.
  return {
    key: data.key as RuntimeSettingKey,
    value: decodeRuntimeValue(data.value),
    default: SPEC_DEFAULTS[data.key as RuntimeSettingKey],
    source: data.source,
  }
}

/**
 * DELETE /api/v1/settings/runtime/:key — revert to spec default. Admin-only.
 */
export async function resetRuntimeSetting(key: RuntimeSettingKey): Promise<void> {
  await getApiClient().delete(`/settings/runtime/${key}`)
}

/**
 * Spec defaults mirroring {@code SettingsKey} on the server. Used by
 * {@link patchRuntimeSetting} since the PATCH response omits the
 * default field. Kept in sync with the documented v2.2.0 catalog.
 */
export const SPEC_DEFAULTS: Record<RuntimeSettingKey, RuntimeValue> = {
  'http_client.protocol': 'h2',
  'http_client.http2_max_pool_size': 1,
  'http_client.http2_multiplexing_limit': 100,
  'prefetch.enabled': true,
  'prefetch.concurrency.global': 64,
  'prefetch.concurrency.per_upstream': 16,
  'prefetch.queue.capacity': 2048,
  'prefetch.worker_threads': 8,
  'prefetch.circuit_breaker.drop_threshold_per_sec': 100,
  'prefetch.circuit_breaker.window_seconds': 30,
  'prefetch.circuit_breaker.disable_minutes': 5,
}
