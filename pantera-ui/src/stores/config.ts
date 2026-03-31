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
  }

  return {
    apiBaseUrl, grafanaUrl, appTitle, defaultPageSize,
    apmEnabled, apmServerUrl, apmServiceName, apmEnvironment,
    registryUrl, loadConfig,
  }
})
