import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { RuntimeConfig } from '@/types'

export const useConfigStore = defineStore('config', () => {
  const apiBaseUrl = ref('/api/v1')
  const grafanaUrl = ref('')
  const appTitle = ref('Pantera')
  const defaultPageSize = ref(20)
  const apmEnabled = ref(false)
  const apmServerUrl = ref('')
  const apmServiceName = ref('pantera-ui')
  const apmEnvironment = ref('production')
  const registryUrl = ref(window.location.origin)
  /** `REGISTRY_URL` from config.json as configured; empty when unset (no origin fallback) */
  const configuredRegistryUrl = ref('')

  function loadConfig(cfg: RuntimeConfig) {
    apiBaseUrl.value = cfg.apiBaseUrl
    grafanaUrl.value = cfg.grafanaUrl
    appTitle.value = cfg.appTitle
    defaultPageSize.value = cfg.defaultPageSize
    apmEnabled.value = cfg.apmEnabled
    apmServerUrl.value = cfg.apmServerUrl
    apmServiceName.value = cfg.apmServiceName
    apmEnvironment.value = cfg.apmEnvironment
    registryUrl.value = cfg.registryUrl || window.location.origin
    configuredRegistryUrl.value = cfg.registryUrl || ''
  }

  return {
    apiBaseUrl, grafanaUrl, appTitle, defaultPageSize,
    apmEnabled, apmServerUrl, apmServiceName, apmEnvironment,
    registryUrl, configuredRegistryUrl, loadConfig,
  }
})
