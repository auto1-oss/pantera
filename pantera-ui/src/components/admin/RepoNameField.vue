<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import InputText from 'primevue/inputtext'
import { repoExists } from '@/api/repos'
import { validateRepoName } from '@/utils/repoName'

const props = defineProps<{ modelValue: string }>()
const emit = defineEmits<{ 'update:modelValue': [string]; 'valid-change': [boolean] }>()

const error = ref<string | null>(null)
const checking = ref(false)
const available = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null
let generation = 0

function setValid(v: boolean) {
  emit('valid-change', v)
}

watch(() => props.modelValue, (name) => {
  generation += 1
  const mine = generation
  if (timer) clearTimeout(timer)
  available.value = false
  checking.value = false
  // The client rule mirrors the server's; an invalid name never hits the API.
  const rule = validateRepoName(name)
  error.value = name ? rule : null
  if (rule) {
    setValid(false)
    return
  }
  checking.value = true
  setValid(false)
  timer = setTimeout(async () => {
    timer = null
    // PUT is an upsert, so an unverified name must never read as free.
    let taken: boolean | null
    try {
      taken = await repoExists(name)
    } catch {
      taken = null
    }
    if (mine !== generation) return
    checking.value = false
    if (taken === null) {
      error.value = 'Could not check whether the name is free; try again'
      setValid(false)
    } else if (taken) {
      error.value = 'A repository with this name already exists'
      setValid(false)
    } else {
      available.value = true
      setValid(true)
    }
  }, 300)
}, { immediate: true })

onBeforeUnmount(() => { if (timer) clearTimeout(timer) })
</script>

<template>
  <div>
    <label for="repoName" class="mb-1 block text-sm font-medium">Repository name</label>
    <div class="relative">
      <InputText
        id="repoName"
        :model-value="modelValue"
        placeholder="my-repo"
        class="w-full pr-9"
        :invalid="!!error"
        autocomplete="off"
        data-testid="repo-name-input"
        @update:model-value="emit('update:modelValue', String($event ?? ''))"
      />
      <span class="absolute right-3 top-1/2 -translate-y-1/2 text-sm">
        <i v-if="checking" class="pi pi-spin pi-spinner text-gray-400" />
        <i v-else-if="available" class="pi pi-check text-green-600" data-testid="name-ok" />
      </span>
    </div>
    <p v-if="error" class="mt-1 text-xs text-red-600 dark:text-red-400" data-testid="name-error">{{ error }}</p>
    <p v-else class="mt-1 text-xs text-gray-500">Letters, digits, ".", "_", "-" and "/"; it becomes part of the repository URL.</p>
  </div>
</template>
