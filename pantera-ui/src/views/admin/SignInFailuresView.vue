<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Message from 'primevue/message'
import { getSignInReportSettings } from '@/api/signInFailures'
import { useAuthStore } from '@/stores/auth'
import AppLayout from '@/components/layout/AppLayout.vue'
import SignInRejectedKeys from '@/components/admin/SignInRejectedKeys.vue'
import SignInFailedPasswords from '@/components/admin/SignInFailedPasswords.vue'
import SignInSecondFactor from '@/components/admin/SignInSecondFactor.vue'

// The sign-in failures report (2.2.15): who fails to sign in, with what,
// and from where. Three lists, each behind the permission to read users.
// The open tab is kept in the address (?tab=passwords) so a list can be
// linked to.
type TabId = 'api-keys' | 'passwords' | 'second-factor'

const TABS: ReadonlyArray<{ id: TabId; label: string; icon: string }> = [
  { id: 'api-keys', label: 'Rejected API keys', icon: 'pi pi-key' },
  { id: 'passwords', label: 'Failed password logins', icon: 'pi pi-lock' },
  { id: 'second-factor', label: 'Second factor', icon: 'pi pi-mobile' },
]
const FIRST_TAB: TabId = 'api-keys'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

function tabOf(value: unknown): TabId {
  return TABS.find(t => t.id === value)?.id ?? FIRST_TAB
}

const activeTab = ref<TabId>(tabOf(route.query.tab))
// A list is loaded when its tab is first opened and then kept, with its
// filters and its page, while the other tabs are looked at.
const opened = reactive<Record<TabId, boolean>>({
  'api-keys': false, passwords: false, 'second-factor': false,
})
opened[activeTab.value] = true

function show(tab: TabId) {
  activeTab.value = tab
  opened[tab] = true
}

function selectTab(tab: TabId) {
  show(tab)
  const { tab: _previous, ...rest } = route.query
  router.replace({ query: tab === FIRST_TAB ? rest : { ...rest, tab } })
}

// A link to another tab followed while this page is open. The route also
// changes when the page is left; that opens no tab.
const pagePath = route.path
watch(() => route.query.tab, (value) => {
  if (route.path === pagePath) show(tabOf(value))
})

// The settings of the report are served to administrators only; without
// them the page says nothing about the number of days.
const retentionDays = ref<number | null>(null)
const reportOff = ref(false)

const retention = computed(() => {
  const days = retentionDays.value
  if (days === null) return 'A line is kept for a limited time after its last event.'
  return `A line is kept for ${days} ${days === 1 ? 'day' : 'days'} after its last event.`
})

onMounted(async () => {
  if (!auth.isAdmin) return
  try {
    const settings = await getSignInReportSettings()
    const days = parseInt(settings.sign_in_report_retention_days ?? '', 10)
    retentionDays.value = Number.isInteger(days) && days > 0 ? days : null
    reportOff.value = settings.sign_in_report_enabled === 'false'
  } catch {
    // The lists do not depend on it: the sentence stays without the number.
  }
})
</script>

<template>
  <AppLayout>
    <div class="space-y-5">
      <div class="space-y-1">
        <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Sign-in failures</h1>
        <p class="text-sm text-gray-600 dark:text-gray-300" data-testid="sign-in-failures-purpose">
          Who fails to sign in, with what, and from where.
        </p>
        <p class="text-sm text-gray-500" data-testid="sign-in-failures-currency">
          Events of this node are listed at once, events of other nodes within a minute.
          {{ retention }}
        </p>
      </div>

      <Message v-if="reportOff" severity="warn" :closable="false" data-testid="sign-in-report-off">
        The report is switched off: no sign-in failure is noted.
        Lines already stored stay until their retention ends.
      </Message>

      <div
        role="tablist"
        class="flex flex-wrap gap-1 border-b border-gray-200 dark:border-gray-800"
        data-testid="sign-in-tabs"
      >
        <button
          v-for="t in TABS"
          :id="`sign-in-tab-${t.id}`"
          :key="t.id"
          type="button"
          role="tab"
          :aria-selected="activeTab === t.id"
          :aria-controls="`sign-in-panel-${t.id}`"
          :data-testid="`sign-in-tab-${t.id}`"
          class="flex items-center gap-2 px-4 py-2 -mb-px border-b-2 text-sm transition-colors"
          :class="activeTab === t.id
            ? 'border-amber-500 text-amber-600 dark:text-amber-400 font-medium'
            : 'border-transparent text-gray-500 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-200'"
          @click="selectTab(t.id)"
        >
          <i :class="t.icon" />
          {{ t.label }}
        </button>
      </div>

      <div
        v-if="opened['api-keys']"
        v-show="activeTab === 'api-keys'"
        id="sign-in-panel-api-keys"
        role="tabpanel"
        aria-labelledby="sign-in-tab-api-keys"
        data-testid="sign-in-panel-api-keys"
      >
        <SignInRejectedKeys />
      </div>
      <div
        v-if="opened.passwords"
        v-show="activeTab === 'passwords'"
        id="sign-in-panel-passwords"
        role="tabpanel"
        aria-labelledby="sign-in-tab-passwords"
        data-testid="sign-in-panel-passwords"
      >
        <SignInFailedPasswords />
      </div>
      <div
        v-if="opened['second-factor']"
        v-show="activeTab === 'second-factor'"
        id="sign-in-panel-second-factor"
        role="tabpanel"
        aria-labelledby="sign-in-tab-second-factor"
        data-testid="sign-in-panel-second-factor"
      >
        <SignInSecondFactor />
      </div>
    </div>
  </AppLayout>
</template>
