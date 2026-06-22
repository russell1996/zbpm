import api from './api'
import type { ProcessVariable } from '@/types/api'

export interface DmnInput {
  id: string
  label: string
  expression: string
}

export interface DmnOutput {
  id: string
  label: string
  name: string
}

export interface DmnRule {
  id: string
  inputEntries: string[]
  outputEntries: string[]
}

export interface DmnDecision {
  id: string
  name: string
  version: number
  createdAt: string
  hitPolicy: string
  inputs: DmnInput[]
  outputs: DmnOutput[]
  rules: DmnRule[]
}

export async function getDecisions(): Promise<DmnDecision[]> {
  const { data } = await api.get<DmnDecision[]>('/dmn')
  return data
}

export async function getDecision(decisionId: string): Promise<DmnDecision> {
  const { data } = await api.get<DmnDecision>(`/dmn/${encodeURIComponent(decisionId)}`)
  return data
}

export async function evaluateDecision(decisionId: string, variables: ProcessVariable[]): Promise<Record<string, unknown>> {
  const { data } = await api.post<Record<string, unknown>>(`/dmn/${encodeURIComponent(decisionId)}/evaluate`, { variables })
  return data
}

/** Best-effort type inference for an ad-hoc test input (so FEEL numeric/boolean tests actually match). */
export function inferVariable(name: string, raw: string): ProcessVariable {
  const value = raw.trim()
  if (value === 'true' || value === 'false') return { name, value, type: 'BOOLEAN' }
  if (/^-?\d+$/.test(value)) return { name, value, type: 'LONG' }
  if (/^-?\d*\.\d+$/.test(value)) return { name, value, type: 'DOUBLE' }
  return { name, value: raw, type: 'STRING' }
}
