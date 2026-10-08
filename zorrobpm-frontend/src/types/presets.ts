import type { ProcessVariable, ProcessVariableType } from '@/types/api'

/**
 * WO-VT-1 (фронт): типы шаблонов переменных. Повторяют contract-DTO бэкенда
 * (VariablePresetDTO / CreatePresetDTO / UpdatePresetDTO / PresetImportDTO /
 * PresetHistoryEntryDTO — см. zorrobpm-contract), без новых зависимостей.
 */

export type PresetTargetKind =
  | 'START'
  | 'USER_TASK'
  | 'SERVICE_TASK'
  | 'MESSAGE'
  | 'INCIDENT'
  | 'DMN'
  | 'ADHOC_JOB'

export type PresetVisibility = 'PRIVATE' | 'PROCESS'

export interface PresetVariable extends ProcessVariable {
  /** WO-VT-1 п.4: только для STRING отличает «пустая строка как значение» от «спросить». */
  allowEmptyString?: boolean | null
}

export interface VariablePreset {
  id: string
  processDefinitionKey: string
  targetKind: PresetTargetKind
  targetRef: string | null
  name: string
  description: string | null
  variables: PresetVariable[]
  ownerUserId: string
  visibility: PresetVisibility
  favorite: boolean
  createdAt: string
  updatedAt: string
  version: number
}

export interface CreatePresetInput {
  processDefinitionKey: string
  targetKind: PresetTargetKind
  targetRef?: string | null
  name: string
  description?: string | null
  variables: PresetVariable[]
  visibility?: PresetVisibility
}

export interface UpdatePresetInput {
  name?: string
  description?: string | null
  variables?: PresetVariable[]
  visibility?: PresetVisibility
  version: number
}

export interface PresetImportPayload {
  processDefinitionKey: string
  targetKind: PresetTargetKind
  targetRef?: string | null
  name: string
  description?: string | null
  visibility?: PresetVisibility
  variables: PresetVariable[]
}

export interface PresetHistoryEntry {
  id: string
  action: string
  actorUserId: string
  at: string
  variablesBefore: PresetVariable[] | null
  variablesAfter: PresetVariable[] | null
}

export type { ProcessVariableType }
