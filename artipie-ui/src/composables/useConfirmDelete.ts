import { ref } from 'vue'

export function useConfirmDelete() {
  const visible = ref(false)
  const targetName = ref('')
  let resolvePromise: ((confirmed: boolean) => void) | null = null

  function confirm(name: string): Promise<boolean> {
    targetName.value = name
    visible.value = true
    return new Promise((resolve) => {
      resolvePromise = resolve
    })
  }

  function accept() {
    visible.value = false
    resolvePromise?.(true)
    resolvePromise = null
  }

  function reject() {
    visible.value = false
    resolvePromise?.(false)
    resolvePromise = null
  }

  return { visible, targetName, confirm, accept, reject }
}
