<script setup lang="ts">
import { computed } from 'vue'
import Tag from 'primevue/tag'
import type { TreeEntry } from '@/types'

const props = defineProps<{
  items: TreeEntry[]
  sortBy: 'name' | 'date' | 'size'
  sortDir: 'asc' | 'desc'
  loading: boolean
}>()

defineEmits<{
  (e: 'sort', by: 'name' | 'date' | 'size'): void
  (e: 'navigate', entry: TreeEntry): void
}>()

const sortIcon = computed(() =>
  props.sortDir === 'asc' ? 'pi pi-arrow-up' : 'pi pi-arrow-down'
)

function ariaSort(key: 'name' | 'date' | 'size'): 'ascending' | 'descending' | 'none' {
  if (props.sortBy !== key) return 'none'
  return props.sortDir === 'asc' ? 'ascending' : 'descending'
}

function formatSize(bytes?: number): string {
  if (!bytes) return '-'
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}

function formatModified(iso?: string | null): string {
  if (!iso) return ''
  const ts = Date.parse(iso)
  if (Number.isNaN(ts)) return ''
  const diffMs = Date.now() - ts
  const sec = Math.round(diffMs / 1000)
  if (sec < 60) return `${sec}s ago`
  const min = Math.round(sec / 60)
  if (min < 60) return `${min}m ago`
  const hr = Math.round(min / 60)
  if (hr < 48) return `${hr}h ago`
  const day = Math.round(hr / 24)
  if (day < 30) return `${day}d ago`
  const mon = Math.round(day / 30)
  if (mon < 18) return `${mon}mo ago`
  return `${Math.round(mon / 12)}y ago`
}

function formatModifiedAbsolute(iso?: string | null): string {
  if (!iso) return ''
  const ts = Date.parse(iso)
  if (Number.isNaN(ts)) return ''
  const d = new Date(ts)
  const yyyy = d.getUTCFullYear()
  const mm = String(d.getUTCMonth() + 1).padStart(2, '0')
  const dd = String(d.getUTCDate()).padStart(2, '0')
  const hh = String(d.getUTCHours()).padStart(2, '0')
  const min = String(d.getUTCMinutes()).padStart(2, '0')
  return `${yyyy}-${mm}-${dd} ${hh}:${min}`
}

function kindPillClass(kind: string): string {
  switch (kind) {
    case 'ARTIFACT':
      return 'bg-indigo-50 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300'
    case 'CHECKSUM':
      return 'bg-amber-50 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300'
    case 'SIGNATURE':
      return 'bg-violet-50 dark:bg-violet-900/40 text-violet-700 dark:text-violet-300'
    case 'METADATA':
    default:
      return 'bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300'
  }
}
</script>

<template>
  <div data-testid="artifact-tree-table" class="w-full">
    <div
      class="flex gap-2 px-3 py-1.5 border-b border-gray-200 dark:border-gray-700
             bg-gray-50 dark:bg-gray-800 text-xs font-semibold uppercase
             tracking-wide text-gray-600 dark:text-gray-300"
    >
      <div
        data-testid="tree-header"
        role="columnheader"
        :aria-sort="ariaSort('name')"
        class="flex-1 cursor-pointer select-none flex items-center gap-1"
        @click="$emit('sort', 'name')"
      >
        Name
        <i v-if="sortBy === 'name'" :class="sortIcon" class="text-[10px]" />
      </div>
      <div
        data-testid="tree-header"
        role="columnheader"
        :aria-sort="ariaSort('date')"
        class="w-[140px] text-right cursor-pointer select-none flex items-center justify-end gap-1"
        @click="$emit('sort', 'date')"
      >
        Uploaded
        <i v-if="sortBy === 'date'" :class="sortIcon" class="text-[10px]" />
      </div>
      <div
        data-testid="tree-header"
        role="columnheader"
        :aria-sort="ariaSort('size')"
        class="w-[70px] text-right cursor-pointer select-none flex items-center justify-end gap-1"
        @click="$emit('sort', 'size')"
      >
        Size
        <i v-if="sortBy === 'size'" :class="sortIcon" class="text-[10px]" />
      </div>
    </div>

    <div
      v-for="entry in items"
      :key="entry.path"
      data-testid="tree-row"
      class="flex gap-2 px-3 py-1 border-b border-gray-100 dark:border-gray-700
             hover:bg-gray-50 dark:hover:bg-gray-700 cursor-pointer
             items-center min-h-[28px]"
      @click="$emit('navigate', entry)"
    >
      <div class="flex-1 flex items-center gap-2 text-sm">
        <i
          :class="entry.type === 'directory'
            ? 'pi pi-folder text-yellow-500'
            : 'pi pi-file text-gray-400 dark:text-gray-500'"
        />
        <span class="font-mono text-gray-900 dark:text-gray-100">{{ entry.name }}</span>
        <span
          v-if="entry.type === 'file' && entry.artifact_kind"
          :class="kindPillClass(entry.artifact_kind)"
          class="text-[10px] px-1.5 py-0 rounded leading-[16px]"
        >{{ entry.artifact_kind.toLowerCase() }}</span>
        <Tag
          v-if="entry.type === 'file' && (entry as Record<string, unknown>).yanked"
          value="Yanked"
          severity="danger"
          class="text-xs"
        />
      </div>
      <div class="w-[140px] text-right leading-[1.15]">
        <template v-if="entry.modified">
          <div
            data-testid="tree-uploaded-abs"
            class="text-[11px] text-gray-700 dark:text-gray-200 tabular-nums"
          >{{ formatModifiedAbsolute(entry.modified) }}</div>
          <div
            data-testid="tree-uploaded-rel"
            class="text-[10px] text-gray-400 dark:text-gray-500"
          >{{ formatModified(entry.modified) }}</div>
        </template>
        <span
          v-else
          data-testid="tree-uploaded-empty"
          class="text-xs text-gray-300 dark:text-gray-600"
        >—</span>
      </div>
      <div class="w-[70px] text-right text-sm tabular-nums text-gray-700 dark:text-gray-200">
        <template v-if="entry.size !== undefined && entry.type === 'file'">
          {{ formatSize(entry.size) }}
        </template>
        <span v-else class="text-gray-300 dark:text-gray-600">—</span>
      </div>
    </div>
  </div>
</template>
