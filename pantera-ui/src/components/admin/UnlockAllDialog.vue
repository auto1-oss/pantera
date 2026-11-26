<script setup lang="ts">
import { computed } from 'vue'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'

// Confirmation only: the parent owns the request, so the users list and
// the Failed logins card unlock through the same code.
const props = defineProps<{ visible: boolean; count: number; running?: boolean }>()
const emit = defineEmits<{ 'update:visible': [boolean]; confirm: [] }>()

const visibleModel = computed({ get: () => props.visible, set: v => emit('update:visible', v) })
</script>

<template>
  <Dialog
    v-model:visible="visibleModel"
    header="Unlock all"
    modal
    :closable="!running"
    :style="{ width: '30rem' }"
  >
    <div class="space-y-2 text-sm" data-testid="unlock-all-dialog">
      <p>
        Unlock <strong>{{ count }}</strong> locked {{ count === 1 ? 'name' : 'names' }}?
      </p>
      <p class="text-gray-500">
        Every lock and its failed-login count is removed, for names without a
        Pantera user too. The users can sign in again at once.
      </p>
    </div>
    <template #footer>
      <Button
        label="Cancel"
        text
        severity="secondary"
        :disabled="running"
        data-testid="unlock-all-cancel"
        @click="emit('update:visible', false)"
      />
      <Button
        label="Unlock all"
        icon="pi pi-lock-open"
        severity="warn"
        :loading="running"
        :disabled="running || count === 0"
        data-testid="unlock-all-confirm"
        @click="emit('confirm')"
      />
    </template>
  </Dialog>
</template>
