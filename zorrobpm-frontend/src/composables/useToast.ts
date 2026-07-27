import { toast } from 'vue-sonner'

export function useToast() {
  function success(message: string, options?: { action?: { label: string; onClick: () => void } }) {
    toast.success(message, options)
  }

  function error(message: string) {
    toast.error(message)
  }

  function info(message: string) {
    toast.info(message)
  }

  function warning(message: string) {
    toast.warning(message)
  }

  function loading(message: string) {
    return toast.loading(message)
  }

  function dismiss(id?: string | number) {
    toast.dismiss(id)
  }

  return { success, error, info, warning, loading, dismiss }
}
