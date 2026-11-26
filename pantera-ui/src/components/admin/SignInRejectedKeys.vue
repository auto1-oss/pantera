<script setup lang="ts">
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Tag from 'primevue/tag'
import { listRejectedApiKeys, type RejectedApiKey } from '@/api/signInFailures'
import { localDateTime } from '@/utils/relativeTime'
import { rejectedKeyName, rejectedKeyReason, shortKeyId, userOrNoName } from '@/utils/signInFailures'
import SignInFailureList from './SignInFailureList.vue'

// Rejected API keys of every user, for the Sign-in failures page. A key
// that is expired or revoked still proves whose key it was; one that
// this Pantera did not issue proves nothing, and its line says so.

/** A stored key without an end has a creation date and no expiry date. */
function expiry(key: RejectedApiKey): string {
  return key.key_created_at && !key.key_expires_at ? 'No expiry' : localDateTime(key.key_expires_at)
}
</script>

<template>
  <SignInFailureList :fetch="listRejectedApiKeys" empty-text="No API key was rejected">
    <template #intro>
      A tool is still sending each of these keys. Replace the key in that tool.
    </template>
    <template #default="{ items, loading }">
      <DataTable :value="items" :loading="loading" size="small" striped-rows class="text-sm">
        <Column header="User">
          <template #body="{ data }">
            <div class="flex flex-wrap items-center gap-2">
              <span class="font-mono break-all" data-testid="line-user">{{ userOrNoName(data.user) }}</span>
              <Tag
                v-if="!data.verified"
                value="Not verified"
                severity="warn"
                title="This Pantera did not issue the key. The user name is only what the client sent."
                data-testid="not-verified"
              />
            </div>
          </template>
        </Column>
        <Column header="Key">
          <template #body="{ data }">
            <div :title="data.key_id" data-testid="key-name">{{ rejectedKeyName(data) }}</div>
            <div
              v-if="data.key_name && data.key_id"
              class="text-xs text-gray-400 font-mono"
              :title="data.key_id"
              data-testid="key-id"
            >
              {{ shortKeyId(data.key_id) }}
            </div>
          </template>
        </Column>
        <Column header="Key created">
          <template #body="{ data }">
            <span :title="data.key_created_at ?? ''">{{ localDateTime(data.key_created_at) }}</span>
          </template>
        </Column>
        <Column header="Key expires">
          <template #body="{ data }">
            <span :title="data.key_expires_at ?? ''" data-testid="key-expires">{{ expiry(data) }}</span>
          </template>
        </Column>
        <Column header="Reason">
          <template #body="{ data }">
            <span :title="data.reason" data-testid="key-reason">{{ rejectedKeyReason(data.reason) }}</span>
          </template>
        </Column>
        <Column field="rejected" header="Rejected" />
        <Column header="First rejected">
          <template #body="{ data }">
            <span :title="data.first_rejected_at ?? ''">{{ localDateTime(data.first_rejected_at) }}</span>
          </template>
        </Column>
        <Column header="Last rejected">
          <template #body="{ data }">
            <span :title="data.last_rejected_at ?? ''">{{ localDateTime(data.last_rejected_at) }}</span>
          </template>
        </Column>
        <Column header="Client address">
          <template #body="{ data }">{{ data.client_ip || '—' }}</template>
        </Column>
        <Column header="Client program">
          <template #body="{ data }">
            <div class="max-w-[14rem] truncate" :title="data.user_agent">{{ data.user_agent || '—' }}</div>
          </template>
        </Column>
        <Column header="Path">
          <template #body="{ data }">{{ data.path || '—' }}</template>
        </Column>
      </DataTable>
    </template>
  </SignInFailureList>
</template>
