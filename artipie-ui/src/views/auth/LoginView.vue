<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import Button from 'primevue/button'

const router = useRouter()
const auth = useAuthStore()
const notify = useNotificationStore()

const username = ref('')
const password = ref('')
const errorMsg = ref('')
const ssoLoading = ref<string | null>(null)

const directGrantTypes = new Set(['artipie', 'keycloak'])
const ssoProviders = computed(() =>
  auth.providers.filter(p => !directGrantTypes.has(p.type))
)

onMounted(() => { auth.fetchProviders() })

async function handleLogin() {
  errorMsg.value = ''
  try {
    await auth.login(username.value, password.value)
    router.push('/')
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : 'Login failed'
    errorMsg.value = msg
    notify.error('Login failed', msg)
  }
}

async function handleSsoRedirect(providerType: string) {
  ssoLoading.value = providerType
  errorMsg.value = ''
  try {
    await auth.ssoRedirect(providerType)
  } catch (e: unknown) {
    ssoLoading.value = null
    const msg = e instanceof Error ? e.message : 'SSO redirect failed'
    errorMsg.value = msg
    notify.error('SSO Error', msg)
  }
}
</script>

<template>
  <div class="login-page">
    <!-- Left: Brand panel -->
    <div class="login-left">
      <div class="circle-1" />
      <div class="circle-2" />
      <div class="circle-3" />

      <div class="left-top">
        <div class="logo-row">
          <div class="logo-mark">A1</div>
          <div class="logo-name">Artipie</div>
        </div>
      </div>

      <div class="left-center">
        <div class="hero-text">
          Manage every<br>
          <span class="highlight">artifact</span> in<br>
          one place.
        </div>
        <p class="hero-sub">
          Universal artifact registry supporting Maven, Docker, npm, PyPI,
          Helm, NuGet and more — built for teams that ship fast.
        </p>
      </div>

      <div class="left-bottom">
        <div class="stat"><div class="num">15+</div><div class="lbl">Package formats</div></div>
        <div class="stat"><div class="num">RBAC</div><div class="lbl">Fine-grained access</div></div>
        <div class="stat"><div class="num">SSO</div><div class="lbl">Enterprise ready</div></div>
      </div>
    </div>

    <!-- Right: Form panel -->
    <div class="login-right">
      <div class="form-title">Sign in</div>
      <div class="form-subtitle">Access your artifact registry</div>

      <!-- SSO first -->
      <div v-if="ssoProviders.length > 0" class="sso-section">
        <Button
          v-for="provider in ssoProviders"
          :key="provider.type"
          :label="ssoLoading === provider.type ? 'Redirecting...' : `Continue with ${provider.type}`"
          icon="pi pi-globe"
          class="w-full sso-btn"
          :loading="ssoLoading === provider.type"
          :disabled="!!ssoLoading"
          @click="handleSsoRedirect(provider.type)"
        />
        <div class="login-divider"><span>or sign in manually</span></div>
      </div>

      <form data-testid="login-form" @submit.prevent="handleLogin" class="login-form">
        <div class="form-group">
          <label for="username">Username</label>
          <InputText id="username" data-testid="username" v-model="username" class="w-full" placeholder="Enter your username" :disabled="auth.loading" />
        </div>
        <div class="form-group">
          <label for="password">Password</label>
          <Password inputId="password" data-testid="password" v-model="password" class="w-full" :feedback="false" toggleMask :disabled="auth.loading" :pt="{ root: { class: 'w-full' }, input: { class: 'w-full' } }" />
        </div>
        <p v-if="errorMsg" class="error-msg">{{ errorMsg }}</p>
        <Button type="submit" :label="ssoProviders.length > 0 ? 'Sign in' : 'Sign in'" class="w-full signin-btn" :class="{ 'primary-action': ssoProviders.length === 0 }" :severity="ssoProviders.length > 0 ? 'secondary' : undefined" :outlined="ssoProviders.length > 0" :loading="auth.loading" :disabled="!username || !password" />
      </form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  min-height: 100vh;
  font-family: 'Inter', system-ui, sans-serif;
}

/* Left panel */
.login-left {
  flex: 1;
  background: linear-gradient(145deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 40px 48px;
  position: relative;
  overflow: hidden;
}
.circle-1 { position: absolute; width: 400px; height: 400px; border-radius: 50%; border: 1px solid rgba(249,115,22,0.12); top: 10%; right: -10%; }
.circle-2 { position: absolute; width: 600px; height: 600px; border-radius: 50%; border: 1px solid rgba(249,115,22,0.06); top: -15%; right: -20%; }
.circle-3 { position: absolute; width: 300px; height: 300px; border-radius: 50%; background: rgba(249,115,22,0.04); bottom: 5%; left: 10%; }

.left-top, .left-center, .left-bottom { position: relative; z-index: 1; }
.logo-row { display: flex; align-items: center; gap: 12px; }
.logo-mark { width: 40px; height: 40px; border-radius: 10px; background: linear-gradient(135deg, #d97b2a, #e8a65c); display: flex; align-items: center; justify-content: center; font-weight: 800; font-size: 15px; color: white; }
.logo-name { font-size: 20px; font-weight: 700; color: white; }
.left-center { max-width: 400px; }
.hero-text { font-size: 44px; font-weight: 800; color: white; line-height: 1.15; letter-spacing: -1px; margin-bottom: 20px; }
.hero-text .highlight { color: #d97b2a; }
.hero-sub { font-size: 16px; color: #94a3b8; line-height: 1.7; }
.left-bottom { display: flex; gap: 32px; }
.stat .num { font-size: 28px; font-weight: 700; color: #d97b2a; }
.stat .lbl { font-size: 12px; color: #64748b; text-transform: uppercase; letter-spacing: 0.5px; margin-top: 2px; }

/* Right panel */
.login-right {
  width: 500px;
  background: #111827;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 48px 56px;
}
.form-title { font-size: 24px; font-weight: 700; color: white; margin-bottom: 4px; }
.form-subtitle { font-size: 14px; color: #6b7280; margin-bottom: 32px; }

.sso-section { margin-bottom: 0; }
.sso-btn { height: 50px; margin-bottom: 8px; }
:deep(.sso-btn) {
  background: linear-gradient(135deg, #d97b2a, #c06a1f) !important;
  border-color: #d97b2a !important;
  font-weight: 600 !important;
  font-size: 15px !important;
  border-radius: 12px !important;
}
:deep(.sso-btn:hover) {
  box-shadow: 0 6px 24px rgba(249,115,22,0.35) !important;
}
.login-divider {
  display: flex; align-items: center; gap: 14px;
  margin: 24px 0; color: #4b5563; font-size: 12px;
  text-transform: uppercase; letter-spacing: 1px;
}
.login-divider::before, .login-divider::after { content: ''; flex: 1; height: 1px; background: #1f2937; }

.form-group { margin-bottom: 16px; }
.form-group label { display: block; font-size: 13px; font-weight: 500; color: #9ca3af; margin-bottom: 7px; }
:deep(.form-group .p-inputtext) {
  height: 46px; border-radius: 10px; background: #0d1117; border-color: #1f2937; color: white;
}
:deep(.form-group .p-inputtext::placeholder) { color: #374151; }
:deep(.form-group .p-inputtext:focus) { border-color: #d97b2a; box-shadow: 0 0 0 3px rgba(249,115,22,0.1); }

/* Password wrapper — force full width, eye icon inside */
:deep(.form-group .p-password) {
  display: flex !important;
  width: 100% !important;
  position: relative;
}
:deep(.form-group .p-password > .p-inputtext) {
  width: 100% !important;
  flex: 1 !important;
  padding-right: 40px !important;
}
:deep(.form-group .p-password .p-password-toggle) {
  position: absolute !important;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  color: #6b7280;
  background: none !important;
  border: none !important;
  cursor: pointer;
  z-index: 1;
  width: auto !important;
  padding: 0 !important;
}
:deep(.form-group .p-password .p-password-toggle:hover) {
  color: #9ca3af;
  background: none !important;
}

.error-msg { color: #ef4444; font-size: 13px; margin-bottom: 8px; }

.signin-btn { height: 46px; }
:deep(.signin-btn) { border-radius: 10px !important; font-weight: 600 !important; }
:deep(.primary-action) {
  background: linear-gradient(135deg, #d97b2a, #c06a1f) !important;
  border-color: #d97b2a !important;
}

@media (max-width: 960px) {
  .login-page { flex-direction: column; }
  .login-left { display: none; }
  .login-right { width: 100%; min-height: 100vh; padding: 32px; }
}
</style>
