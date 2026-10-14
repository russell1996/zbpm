import { toast } from 'vue-sonner'

export function useToast() {
  function success(message: string, options?: { action?: { label: string; onClick: () => void }; duration?: number }) {
    toast.success(message, options)
  }

  function error(message: string) {
    toast.error(message)
  }

  // WO-UI-34: опции (action «Отменить» + duration) — как у success: тихий
  // undo-тост при замене введённых строк шаблоном.
  function info(message: string, options?: { action?: { label: string; onClick: () => void }; duration?: number }) {
    toast.info(message, options)
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
