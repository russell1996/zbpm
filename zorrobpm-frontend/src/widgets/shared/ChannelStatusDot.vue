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
import {
  useRealtimeEvents,
  channelDotState,
  type ChannelDotState,
  type ChannelErrorKind,
} from '@/composables/useRealtimeEvents'
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

/**
 * WO-UI-31: состояние — ОДИН источник правды для точки (видимость + цвет)
 * и строки «Статус» панели. До фикса точка шла от channelDown()
 * (isConnected/realtimeDown/sessionExpired), а «Статус» панели — от
 * diagnostics.lastError: на вкладке-«ведомой» (isConnected ложился из
 * health-патча лидера и мог расходятся с диагнозом) точка горела при
 * «Статус: Подключено». Теперь оба — из channelDotState().
 */
const channelState = computed<ChannelDotState>(() =>
  channelDotState({
    isConnected: rt.isConnected.value,
    realtimeDown: rt.realtimeDown.value,
    sessionExpired: rt.sessionExpired.value,
  }),
)

/** Точка видима только когда канал НЕ здоров (после гистерезиса). */
function channelDown(): boolean {
  return channelState.value !== 'healthy'
}

/**
 * Цвет точки по состоянию (WO-UI-31): сбой/нет связи (down/expired) —
 * destructive (красный), переподключение (идут попытки) — янтарный.
 * Токены темы/Tailwind, без хардкод-hex; обе темы. Цвет — НЕ единственный
 * носитель смысла (aria-label + «Статус» в панели несут текст состояния).
 */
const DOT_COLOR: Record<Exclude<ChannelDotState, 'healthy'>, string> = {
  reconnecting: 'bg-amber-500',
  down: 'bg-destructive',
  expired: 'bg-destructive',
}

const STATE_TEXT: Record<ChannelDotState, string> = {
  healthy: 'channelStateConnected',
  reconnecting: 'channelStateReconnecting',
  down: 'channelStateDown',
  expired: 'channelStateSessionExpired',
}

// Каноничные цвета только для не-healthy состояний: при 'healthy' точка
// не рендерится (v-if на channelDown()), поэтому в DOT_COLOR healthy нет —
// каст безопасен (указатель на v-if выше).
const dotColor = computed(
  () => DOT_COLOR[channelState.value as Exclude<ChannelDotState, 'healthy'>],
)

const stateText = computed(() => t(STATE_TEXT[channelState.value]))

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
// тик), поэтому watch на геттер ниже. Геттер возвращает channelState —
// тот же источник, что и «Статус» панели.
watch(
  () => channelState.value,
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
      <!-- G24: точка — shadcn Button (ghost/icon), внутри — кружок 8px;
           сырые HTML-теги кнопок запрещены. WO-UI-31: цвет — по состоянию
           (bg-destructive / bg-amber-500, токены темы, без хардкод-hex);
           пульсации нет. aria-label и title несут ТЕКСТ состояния — цвет
           не единственный носитель смысла (состояния различимы и для
           screen reader'а, и при дальтонизме). -->
      <Button
        variant="ghost"
        size="icon"
        data-testid="channel-dot"
        class="h-8 w-8 shrink-0 hover:bg-transparent"
        :aria-label="t('channelAriaLabel', { state: stateText })"
        :title="stateText"
      >
        <span
          data-testid="channel-dot-color"
          class="h-2 w-2 rounded-full"
          :class="dotColor"
        />
      </Button>
    </DropdownMenuTrigger>
    <!-- WO-UI-31: align="start" — левый край панели = левый край точки
         (точка теперь в ЛЕВОЙ группе шапки; align="end" из правой группы
         раскрывал панель навстречу кнопкам «Обновить»/«Загрузить процесс»).
         В узком вьюпорте floating-ui shift (reka-ui avoidCollisions=true)
         подтягивает панель в границы — панель w-80=320 ≤ 360. -->
    <DropdownMenuContent align="start" class="w-80" data-testid="channel-dialog">
      <DropdownMenuLabel>{{ t('channelDiagTitle') }}</DropdownMenuLabel>
      <div class="px-2 py-1.5 text-sm">
        <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5">
          <!-- WO-UI-31: «Статус» = состояние канала (тот же
               channelDotState, что ведёт точку). До фикса здесь был
               diagnostics.lastError → точка горела, а строка говорила
               «Подключено». -->
          <dt class="text-muted-foreground">{{ t('channelDiagStatus') }}</dt>
          <dd data-testid="channel-diag-status">{{ stateText }}</dd>
          <dt
            v-if="rt.diagnostics.value.lastError !== 'none'"
            class="text-muted-foreground"
          >{{ t('channelDiagCause') }}</dt>
          <dd
            v-if="rt.diagnostics.value.lastError !== 'none'"
            data-testid="channel-diag-cause"
          >{{ t(ERROR_TEXT[rt.diagnostics.value.lastError]) }}</dd>
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
