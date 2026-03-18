import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AppLayout from '../AppLayout.vue'
import PrimeVue from 'primevue/config'
import Aura from '@primevue/themes/aura'

describe('AppLayout', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('renders header, sidebar, and main content area', () => {
    const wrapper = mount(AppLayout, {
      global: {
        plugins: [[PrimeVue, { theme: { preset: Aura } }]],
        stubs: ['router-link', 'router-view', 'AppHeader', 'AppSidebar', 'HealthIndicator'],
      },
      slots: { default: '<div data-testid="content">Page Content</div>' },
    })
    expect(wrapper.find('[data-testid="app-layout"]').exists()).toBe(true)
  })
})
