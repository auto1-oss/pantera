import { describe, it, expect, vi, beforeEach } from 'vitest'

// Stub the axios client so we can observe each wrapper's URL + body
// without touching the network. We only care about the request shape;
// the wrappers are thin and a contract-style unit test is the
// appropriate level here.
const get = vi.fn()
const patch = vi.fn()
const del = vi.fn()

vi.mock('../client', () => ({
  getApiClient: () => ({ get, patch, delete: del }),
}))

import {
  decodeRuntimeValue,
  listRuntimeSettings,
  patchRuntimeSetting,
  resetRuntimeSetting,
  SPEC_DEFAULTS,
} from '../runtimeSettings'

describe('runtimeSettings API wrappers', () => {
  beforeEach(() => {
    get.mockReset()
    patch.mockReset()
    del.mockReset()
  })

  describe('decodeRuntimeValue', () => {
    it('decodes JSON-literal strings to native JS types', () => {
      expect(decodeRuntimeValue('"adaptive"')).toBe('adaptive')
      expect(decodeRuntimeValue('100')).toBe(100)
      expect(decodeRuntimeValue('true')).toBe(true)
      expect(decodeRuntimeValue('false')).toBe(false)
    })

    it('returns the raw string when the literal is malformed', () => {
      // Falls back gracefully so a server bug doesn't crash the UI
      expect(decodeRuntimeValue('not-json')).toBe('not-json')
    })
  })

  describe('listRuntimeSettings', () => {
    it('GETs /settings/runtime, decodes literals, sorts by key', async () => {
      get.mockResolvedValueOnce({
        data: {
          'http_client.bulkhead.adaptive': {
            value: 'true',
            default: 'true',
            source: 'default',
          },
          'http_client.bulkhead.max_permits': {
            value: '200',
            default: '100',
            source: 'db',
          },
          'http_client.bulkhead.min_permits': {
            value: '7',
            default: '5',
            source: 'db',
          },
        },
      })
      const result = await listRuntimeSettings()
      expect(get).toHaveBeenCalledWith('/settings/runtime')
      expect(result.length).toBe(3)
      // Sorted alphabetically by key
      expect(result.map(r => r.key)).toEqual([
        'http_client.bulkhead.adaptive',
        'http_client.bulkhead.max_permits',
        'http_client.bulkhead.min_permits',
      ])
      // Decoded
      expect(result[0].value).toBe(true)
      expect(result[0].default).toBe(true)
      expect(result[0].source).toBe('default')
      expect(result[1].value).toBe(200)
      expect(result[2].value).toBe(7)
    })
  })

  describe('patchRuntimeSetting', () => {
    it('PATCHes /settings/runtime/:key with {value} and decodes the response', async () => {
      patch.mockResolvedValueOnce({
        data: {
          key: 'http_client.bulkhead.adaptive',
          value: 'false',
          source: 'db',
        },
      })
      const updated = await patchRuntimeSetting('http_client.bulkhead.adaptive', false)
      expect(patch).toHaveBeenCalledWith(
        '/settings/runtime/http_client.bulkhead.adaptive',
        { value: false },
      )
      expect(updated.value).toBe(false)
      expect(updated.source).toBe('db')
      // Default is filled in from the catalog since the server PATCH
      // response omits it
      expect(updated.default).toBe(SPEC_DEFAULTS['http_client.bulkhead.adaptive'])
    })

    it('rounds-trips integer keys', async () => {
      patch.mockResolvedValueOnce({
        data: {
          key: 'http_client.bulkhead.max_permits',
          value: '250',
          source: 'db',
        },
      })
      const updated = await patchRuntimeSetting(
        'http_client.bulkhead.max_permits', 250,
      )
      expect(patch).toHaveBeenCalledWith(
        '/settings/runtime/http_client.bulkhead.max_permits',
        { value: 250 },
      )
      expect(updated.value).toBe(250)
    })
  })

  describe('resetRuntimeSetting', () => {
    it('DELETEs /settings/runtime/:key', async () => {
      del.mockResolvedValueOnce({ data: undefined })
      await resetRuntimeSetting('http_client.bulkhead.min_permits')
      expect(del).toHaveBeenCalledWith(
        '/settings/runtime/http_client.bulkhead.min_permits',
      )
    })
  })

  describe('SPEC_DEFAULTS', () => {
    it('catalogues all server-side keys', () => {
      const keys = Object.keys(SPEC_DEFAULTS)
      expect(keys.length).toBe(8)
      // Must include every documented key from SettingsKey.java
      expect(keys).toContain('http_client.bulkhead.adaptive')
      expect(keys).toContain('http_client.bulkhead.min_permits')
      expect(keys).toContain('http_client.bulkhead.max_permits')
      expect(keys).toContain('http_client.bulkhead.initial_permits')
      expect(keys).toContain('http_client.bulkhead.target_p99_ms')
      expect(keys).toContain('http_client.bulkhead.window_seconds')
      expect(keys).toContain('http_client.bulkhead.ramp_up_step')
      expect(keys).toContain('http_client.bulkhead.ramp_down_factor')
    })
  })
})
