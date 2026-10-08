import api from './api'
import type {
  CreatePresetInput,
  PresetHistoryEntry,
  PresetImportPayload,
  PresetTargetKind,
  PresetVisibility,
  UpdatePresetInput,
  VariablePreset,
} from '@/types/presets'

/**
 * WO-VT-1 (фронт): клиент `/presets/**` (контракт — PresetContract бэкенда).
 * Флаг `zorrobpm.ui.variable-presets.enabled=false` гасит API в 404 — см.
 * isPresetsDisabled(): UI по нему прячет фичу, а не показывает вечную ошибку.
 */

export interface PresetListQuery {
  key?: string
  kind?: PresetTargetKind
  ref?: string
}

function toQueryString(params: Record<string, unknown>): string {
  const entries = Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')
  return entries.length > 0 ? '?' + new URLSearchParams(entries.map(([k, v]) => [k, String(v)])).toString() : ''
}

export async function listPresets(query: PresetListQuery = {}): Promise<VariablePreset[]> {
  const qs = toQueryString({ ...query })
  const { data } = await api.get<VariablePreset[]>(`/presets${qs}`)
  return data
}

export async function getPreset(id: string): Promise<VariablePreset> {
  const { data } = await api.get<VariablePreset>(`/presets/${id}`)
  return data
}

export async function createPreset(input: CreatePresetInput): Promise<VariablePreset> {
  const { data } = await api.post<VariablePreset>('/presets', input)
  return data
}

export async function updatePreset(id: string, input: UpdatePresetInput): Promise<VariablePreset> {
  const { data } = await api.put<VariablePreset>(`/presets/${id}`, input)
  return data
}

export async function deletePreset(id: string): Promise<void> {
  await api.delete(`/presets/${id}`)
}

export async function importPreset(payload: PresetImportPayload): Promise<VariablePreset> {
  const { data } = await api.post<VariablePreset>('/presets/import', payload)
  return data
}

export async function exportPreset(id: string): Promise<PresetImportPayload> {
  const { data } = await api.get<PresetImportPayload>(`/presets/${id}/export`)
  return data
}

export async function getPresetHistory(id: string): Promise<PresetHistoryEntry[]> {
  const { data } = await api.get<PresetHistoryEntry[]>(`/presets/${id}/history`)
  return data
}

export async function setPresetFavorite(id: string, favorite: boolean): Promise<void> {
  if (favorite) {
    await api.put(`/presets/${id}/favorite`)
  } else {
    await api.delete(`/presets/${id}/favorite`)
  }
}

export async function changePresetVisibility(id: string, visibility: PresetVisibility): Promise<VariablePreset> {
  const { data } = await api.put<VariablePreset>(`/presets/${id}/visibility`, { visibility })
  return data
}

/**
 * Выключенный флаг: бэкенд отвечает 404 `Variable presets are disabled`
 * (PresetResource.requireEnabled). От чужих 404 (нет шаблонов — это 200 с
 * пустым списком; чужой PRIVATE — 404 без тела про флаг) отличаем по тексту.
 */
export function isPresetsDisabled(error: unknown): boolean {
  const status = (error as { response?: { status?: number } })?.response?.status
  if (status !== 404) return false
  const message =
    (error as { response?: { data?: { message?: string } } })?.response?.data?.message ??
    (error as Error)?.message ??
    ''
  return message.toLowerCase().includes('presets are disabled')
}

/** 409 от бэкенда: PRESET_CONFLICT (дубль имени) и stale-версия PUT. */
export function isPresetConflict(error: unknown): boolean {
  return (error as { response?: { status?: number } })?.response?.status === 409
}

/** Машинный код ошибки бэкенда (PRESET_VALIDATION_FAILED / PRESET_NOT_FOUND / …). */
export function presetErrorCode(error: unknown): string | null {
  const code = (error as { response?: { data?: { code?: string } } })?.response?.data?.code
  return typeof code === 'string' && code ? code : null
}
