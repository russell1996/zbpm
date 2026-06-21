import { toast } from 'vue-sonner'

export function useToast() {
  function success(message: string) {
    toast.success(message)
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

  return { success, error, info, warning }
}
