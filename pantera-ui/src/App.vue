<script setup lang="ts">
import { useThemeStore } from '@/stores/theme'
import { onMounted } from 'vue'

const theme = useThemeStore()

onMounted(() => {
  theme.applyTheme()
})
</script>

<template>
  <router-view />
</template>
