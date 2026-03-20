<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getRole, putRole } from '@/api/roles'
import { useNotificationStore } from '@/stores/notifications'
import AppLayout from '@/components/layout/AppLayout.vue'
import Card from 'primevue/card'
import Button from 'primevue/button'
import Textarea from 'primevue/textarea'
import Tag from 'primevue/tag'
import type { Role } from '@/types'

const props = defineProps<{ name: string }>()
const router = useRouter()
const notify = useNotificationStore()

const role = ref<Role | null>(null)
const permissionsJson = ref('')
const saving = ref(false)
const loading = ref(true)

onMounted(async () => {
  try {
    role.value = await getRole(props.name)
    permissionsJson.value = JSON.stringify(role.value.permissions, null, 2)
  } catch {
    notify.error('Role not found')
    router.push('/admin/roles')
  } finally { loading.value = false }
})

async function handleSave() {
  saving.value = true
  try {
    await putRole(props.name, { permissions: JSON.parse(permissionsJson.value) })
    notify.success('Role updated')
  } catch { notify.error('Failed to update role') }
  finally { saving.value = false }
}
</script>

<template>
  <AppLayout>
    <div class="max-w-2xl space-y-5">
      <router-link to="/admin/roles" class="text-blue-500 hover:underline text-sm">
        <i class="pi pi-arrow-left mr-1" /> Roles
      </router-link>

      <Card v-if="role" class="shadow-sm">
        <template #title>
          <div class="flex items-center gap-3">
            {{ role.name }}
            <Tag :value="role.enabled !== false ? 'Active' : 'Disabled'"
              :severity="role.enabled !== false ? 'success' : 'danger'" />
          </div>
        </template>
        <template #content>
          <div>
            <label class="block text-sm font-medium mb-1">Permissions (JSON)</label>
            <Textarea v-model="permissionsJson" rows="12" class="w-full font-mono text-sm" />
          </div>
          <div class="flex gap-2 mt-4">
            <Button label="Save" icon="pi pi-check" :loading="saving" @click="handleSave" />
            <Button label="Back" severity="secondary" text @click="router.back()" />
          </div>
        </template>
      </Card>
    </div>
  </AppLayout>
</template>
