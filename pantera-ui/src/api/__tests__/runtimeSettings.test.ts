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
      expect(decodeRuntimeValue('"h2"')).toBe('h2')
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
          'http_client.protocol': {
            value: '"h2"',
            default: '"h2"',
            source: 'default',
          },
          'http_client.http2_multiplexing_limit': {
            value: '200',
            default: '100',
            source: 'db',
          },
          'http_client.http2_max_pool_size': {
            value: '4',
            default: '1',
            source: 'db',
          },
        },
      })
      const result = await listRuntimeSettings()
      expect(get).toHaveBeenCalledWith('/settings/runtime')
      expect(result.length).toBe(3)
      // Sorted alphabetically by key
      expect(result.map(r => r.key)).toEqual([
        'http_client.http2_max_pool_size',
        'http_client.http2_multiplexing_limit',
        'http_client.protocol',
      ])
      // Decoded
      expect(result[0].value).toBe(4)
      expect(result[0].default).toBe(1)
      expect(result[0].source).toBe('db')
      expect(result[1].value).toBe(200)
      expect(result[2].value).toBe('h2')
    })
  })

  describe('patchRuntimeSetting', () => {
    it('PATCHes /settings/runtime/:key with {value} and decodes the response', async () => {
      patch.mockResolvedValueOnce({
        data: {
          key: 'http_client.protocol',
          value: '"h1"',
          source: 'db',
        },
      })
      const updated = await patchRuntimeSetting('http_client.protocol', 'h1')
      expect(patch).toHaveBeenCalledWith(
        '/settings/runtime/http_client.protocol',
        { value: 'h1' },
      )
      expect(updated.value).toBe('h1')
      expect(updated.source).toBe('db')
      // Default is filled in from the catalog since the server PATCH
      // response omits it
      expect(updated.default).toBe(SPEC_DEFAULTS['http_client.protocol'])
    })

    it('rounds-trips integer keys', async () => {
      patch.mockResolvedValueOnce({
        data: {
          key: 'http_client.http2_max_pool_size',
          value: '4',
          source: 'db',
        },
      })
      const updated = await patchRuntimeSetting(
        'http_client.http2_max_pool_size', 4,
      )
      expect(patch).toHaveBeenCalledWith(
        '/settings/runtime/http_client.http2_max_pool_size',
        { value: 4 },
      )
      expect(updated.value).toBe(4)
    })
  })

  describe('resetRuntimeSetting', () => {
    it('DELETEs /settings/runtime/:key', async () => {
      del.mockResolvedValueOnce({ data: undefined })
      await resetRuntimeSetting('http_client.http2_multiplexing_limit')
      expect(del).toHaveBeenCalledWith(
        '/settings/runtime/http_client.http2_multiplexing_limit',
      )
    })
  })

  describe('SPEC_DEFAULTS', () => {
    it('catalogues all server-side keys', () => {
      const keys = Object.keys(SPEC_DEFAULTS)
      expect(keys.length).toBe(3)
      // Must include every documented key from SettingsKey.java
      expect(keys).toContain('http_client.protocol')
      expect(keys).toContain('http_client.http2_max_pool_size')
      expect(keys).toContain('http_client.http2_multiplexing_limit')
    })
  })
})
