<script setup lang="ts">
import { ref, computed, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
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
 * - детали — по клику: shadcn-панель «Состояние канала» (статус, код
 *   последней ошибки, время попытки, число попыток, Last-Event-ID,
 *   «Повторить сейчас», «Скопировать диагностику»);
 * - никаких кнопок/плашек реконнекта слева сверху и полос над контентом
 *   (Доп.2: layout shift = 0 — точка в потоке шапки фиксированного размера).
 *
 * Панель — DropdownMenu (рабочий shadcn-примитив в этом репо: тот же, что
 * меню пользователя в HeaderBar). shadcn Dialog здесь НЕ подошёл: ни один
 * экран репо его не использует (все диалоги — самописные v-if), DialogRoot
 * не открывался ни в браузере, ни в тесте (см. историю правок).
 *
 * Различение причин (Доп.8): 401/403/429/сеть/таймаут/no-first-byte
 * («нет первого байта за N секунд» — признак буферизации прокси, REL-70).
 */
const DOWN_HYSTERESIS_MS = 5000

const { t } = useI18n()
const toast = useToast()
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
  rt.retryConnection()
}
</script>

<template>
  <!-- Подсказка — нативным title (ноль JS, ноль layout). -->
  <DropdownMenu v-if="showDot">
    <DropdownMenuTrigger as-child>
      <!-- G24: точка — shadcn Button (ghost/icon), внутри — нейтральный
           кружок 8px; сырые HTML-теги кнопок запрещены. -->
      <Button
        variant="ghost"
        size="icon"
        data-testid="channel-dot"
        class="h-8 w-8 shrink-0 hover:bg-transparent"
        :aria-label="t('channelStatus')"
        :title="t('channelStatus')"
      >
        <span class="h-2 w-2 rounded-full bg-muted-foreground/60" />
      </Button>
    </DropdownMenuTrigger>
    <DropdownMenuContent align="end" class="w-80" data-testid="channel-dialog">
      <DropdownMenuLabel>{{ t('channelDiagTitle') }}</DropdownMenuLabel>
      <div class="px-2 py-1.5 text-sm">
        <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5">
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
        <p v-if="errorHint" class="mt-2 text-muted-foreground">{{ errorHint }}</p>
        <p class="mt-1 text-xs text-muted-foreground">{{ t('channelDiagSubtitle') }}</p>
      </div>
      <DropdownMenuSeparator />
      <DropdownMenuItem data-testid="channel-diag-copy" @select="copyDiagnostics">
        {{ t('channelDiagCopy') }}
      </DropdownMenuItem>
      <DropdownMenuItem data-testid="channel-diag-retry" @select="retryNow">
        {{ t('channelDiagRetry') }}
      </DropdownMenuItem>
    </DropdownMenuContent>
  </DropdownMenu>
</template>
