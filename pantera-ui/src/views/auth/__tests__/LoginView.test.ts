import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import LoginView from '../LoginView.vue'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'

describe('LoginView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('renders login form', () => {
    const wrapper = mount(LoginView, {
      global: {
        plugins: [
          [PrimeVue, { theme: { preset: Aura } }],
        ],
        stubs: ['router-link', 'router-view'],
      },
    })
    expect(wrapper.find('form').exists() || wrapper.find('[data-testid="login-form"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Sign in')
  })

  it('has username and password fields', () => {
    const wrapper = mount(LoginView, {
      global: {
        plugins: [
          [PrimeVue, { theme: { preset: Aura } }],
        ],
        stubs: ['router-link', 'router-view'],
      },
    })
    expect(wrapper.find('[data-testid="username"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="password"]').exists()).toBe(true)
  })
})
