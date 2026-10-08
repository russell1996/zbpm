import api from './api'
import type { ProcessVariable } from '@/types/api'

/**
 * WO-VT-1 (фронт): публикация сообщения (`POST /messages/publish`,
 * RuntimeContract.publishMessage) — эндпоинт бэкенда уже есть, фронт его
 * не звал. Переменные — из шаблона MESSAGE (или ручной ввод пикера).
 */
export interface PublishMessageInput {
  messageName: string
  correlationKey?: string | null
  processInstanceId?: string | null
  variables: ProcessVariable[]
}

export interface PublishMessageResult {
  correlated?: number
  startedInstances?: string[]
  [key: string]: unknown
}

export async function publishMessage(input: PublishMessageInput): Promise<PublishMessageResult> {
  const { data } = await api.post<PublishMessageResult>('/messages/publish', {
    messageName: input.messageName,
    correlationKey: input.correlationKey ?? null,
    processInstanceId: input.processInstanceId ?? null,
    variables: input.variables,
  })
  return data
}
