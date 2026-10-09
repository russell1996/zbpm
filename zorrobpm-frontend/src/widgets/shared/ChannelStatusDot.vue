<script setup lang="ts">
import { ref, computed, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { useRealtimeEvents, type ChannelErrorKind } from '@/composables/useRealtimeEvents'
import { useRealtimeChannel } from '@/composables/useRealtimeChannel'
import { useToast } from '@/composables/useToast'

/**
 * WO-UI-26 Доп.6 п.2/Доп.7/Доп.8 — индикатор канала в глобальной шапке.
 *
 * Правила (владелец):
 * - здоровый канал → индикатора НЕТ вообще (никакого «Подключено»);
 * - сбой дольше гистерезиса (5 с) → ОДНА маленькая нейтральная точка
 *   (без текста, без пульсации, без prefers-reduced-motion нарушений);
 * - детали — по клику: shadcn-Dialog «Состояние канала» (статус, код
 *   последней ошибки, время попытки, число попыток, Last-Event-ID,
 *   «Повторить сейчас», «Скопировать диагностику»);
 * - никаких кнопок/плашек реконнекта слева сверху и полос над контентом
 *   (Доп.2: layout shift = 0 — fixed/overlay здесь вообще нет, точка в
 *   потоке шапки фиксированного размера).
 *
 * Различение причин (Доп.8): 401/403/429/сеть/таймаут/no-first-byte
 * («нет первого байта за N секунд» — признак буферизации прокси, REL-70).
 */
const DOWN_HYSTERESIS_MS = 5000

const { t } = useI18n()
const toast = useToast()
/**
 * Состояние канала — от экземпляра MainLayout (provide/inject), а НЕ свой
 * экземпляр composable: refs живут внутри вызова useRealtimeEvents, свой
 * экземпляр никогда не connect'ится и всегда «всё плохо». Fallback для
 * изолированных тестов — свой экземпляр.
 */
type RealtimeHandle = ReturnType<typeof useRealtimeEvents>
/**
 * Состояние канала — экземпляр MainLayout через модульный синглтон
 * useRealtimeChannel (provide/inject в том же тике монтирования не
 * стыкуется — см. useRealtimeChannel.ts). Без set — явная ошибка в консоль,
 * а не молча неверный индикатор.
 */
const injected = useRealtimeChannel().get()
if (!injected) {
  // eslint-disable-next-line no-console
  console.error('[ChannelStatusDot] no realtime channel set — dot disabled')
}
const rt: RealtimeHandle = injected ?? useRealtimeEvents()

const open = ref(false)
const downSince = ref<number | null>(null)
const showDot = ref(false)
let hysteresisTimer: ReturnType<typeof setTimeout> | null = null

function channelDown(): boolean {
  return rt.realtimeDown.value || rt.sessionExpired.value || !rt.isConnected.value
}

function armHysteresis(): void {
  if (downSince.value === null) downSince.value = Date.now()
  if (hysteresisTimer) return
  hysteresisTimer = setTimeout(() => {
    hysteresisTimer = null
    if (channelDown()) showDot.value = true
  }, DOWN_HYSTERESIS_MS)
}

function disarm(): void {
  downSince.value = null
  showDot.value = false
  if (hysteresisTimer) {
    clearTimeout(hysteresisTimer)
    hysteresisTimer = null
  }
}

// Реакция на здоровье канала — через интервал-опрос ref'ов нельзя (лишний
// тик), поэтому watch на геттер ниже.
watch(
  () => [rt.isConnected.value, rt.realtimeDown.value, rt.sessionExpired.value] as const,
  () => {
    if (channelDown()) armHysteresis()
    else disarm()
  },
  { immediate: true },
)

onUnmounted(() => {
  if (hysteresisTimer) clearTimeout(hysteresisTimer)
})

const ERROR_TEXT: Record<ChannelErrorKind, string> = {
  none: 'channelDiagNone',
  'http-401': 'channelDiag401',
  'http-403': 'channelDiag403',
  'http-429': 'channelDiag429',
  'http-other': 'channelDiagHttpOther',
  network: 'channelDiagNetwork',
  timeout: 'channelDiagTimeout',
  'no-first-byte': 'channelDiagNoFirstByte',
}

const errorHint = computed(() => {
  const k = rt.diagnostics.value.lastError
  if (k === 'no-first-byte') return t('channelDiagNoFirstByteHint')
  if (k === 'http-429') return t('channelDiag429Hint')
  return ''
})

function copyDiagnostics(): void {
  const d = rt.diagnostics.value
  const payload = JSON.stringify(
    {
      lastError: d.lastError,
      httpStatus: d.httpStatus,
      lastAttemptAt: d.lastAttemptAt,
      attempts: d.attempts,
      lastEventId: d.lastEventId,
      isLeader: d.isLeader,
      userAgent: typeof navigator !== 'undefined' ? navigator.userAgent : null,
      now: new Date().toISOString(),
    },
    null,
    2,
  )
  void navigator.clipboard
    ?.writeText(payload)
    .then(() => toast.success(t('channelDiagCopied')))
    .catch(() => toast.error(t('channelDiagCopyFailed')))
}

function retryNow(): void {
  open.value = false
  rt.retryConnection()
}
</script>

<template>
  <!-- WO-UI-26: без Tooltip-обёртки вокруг кнопки — TooltipTrigger
       перехватывает клик (превент), и Dialog не открывается ни в браузере,
       ни в тесте. Подсказка — нативным title (ноль JS, ноль layout). -->
  <template v-if="showDot">
    <Button
      variant="ghost"
      size="icon"
      data-testid="channel-dot"
      class="h-8 w-8 shrink-0 hover:bg-transparent"
      :aria-label="t('channelStatus')"
      :title="t('channelStatus')"
      @click="open = true"
    >
      <span class="h-2 w-2 rounded-full bg-muted-foreground/60" />
    </Button>

    <Dialog v-model:open="open">
      <!-- data-testid — на внутреннем div: DialogContent рендерит fragment/
           teleport-root, не-наследованные атрибуты на нём теряются (warn) и
           querySelector их не находит ни в тесте, ни в проде. -->
      <DialogContent class="sm:max-w-md">
        <div data-testid="channel-dialog">
        <DialogHeader>
          <DialogTitle>{{ t('channelDiagTitle') }}</DialogTitle>
          <DialogDescription>{{ t('channelDiagSubtitle') }}</DialogDescription>
        </DialogHeader>
        <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
          <dt class="text-muted-foreground">{{ t('channelDiagStatus') }}</dt>
          <dd data-testid="channel-diag-status">{{ t(ERROR_TEXT[rt.diagnostics.value.lastError]) }}</dd>
          <dt class="text-muted-foreground">{{ t('channelDiagLastAttempt') }}</dt>
          <dd>{{ rt.diagnostics.value.lastAttemptAt ?? '—' }}</dd>
          <dt class="text-muted-foreground">{{ t('channelDiagAttempts') }}</dt>
          <dd>{{ rt.diagnostics.value.attempts }}</dd>
          <dt class="text-muted-foreground">{{ t('channelDiagLastEventId') }}</dt>
          <dd>{{ rt.diagnostics.value.lastEventId ?? '—' }}</dd>
          <dt class="text-muted-foreground">{{ t('channelDiagRole') }}</dt>
          <dd>{{ rt.diagnostics.value.isLeader ? t('channelDiagLeader') : t('channelDiagFollower') }}</dd>
        </dl>
        <p v-if="errorHint" class="text-sm text-muted-foreground">{{ errorHint }}</p>
        <DialogFooter class="gap-2">
          <Button variant="outline" data-testid="channel-diag-copy" @click="copyDiagnostics">
            {{ t('channelDiagCopy') }}
          </Button>
          <Button data-testid="channel-diag-retry" @click="retryNow">
            {{ t('channelDiagRetry') }}
          </Button>
        </DialogFooter>
        </div>
      </DialogContent>
    </Dialog>
  </template>
</template>
