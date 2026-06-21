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

const MOCK_DECISIONS: DmnDecision[] = [
  {
    id: 'credit-decision',
    name: 'Credit Decision',
    version: 1,
    createdAt: '2024-01-15T10:00:00Z',
    hitPolicy: 'UNIQUE',
    inputs: [
      { id: 'in1', label: 'Credit Score', expression: 'creditScore' },
      { id: 'in2', label: 'Income', expression: 'income' },
    ],
    outputs: [
      { id: 'out1', label: 'Decision', name: 'decision' },
      { id: 'out2', label: 'Amount', name: 'amount' },
    ],
    rules: [
      { id: 'r1', inputEntries: ['>= 700', '>= 50000'], outputEntries: ['"APPROVED"', '25000'] },
      { id: 'r2', inputEntries: ['>= 600', '>= 30000'], outputEntries: ['"REVIEW"', '10000'] },
      { id: 'r3', inputEntries: ['< 600', '>= 0'], outputEntries: ['"REJECTED"', '0'] },
    ],
  },
]

export async function getDecisions(): Promise<DmnDecision[]> {
  return MOCK_DECISIONS
}

export async function getDecision(id: string): Promise<DmnDecision> {
  const d = MOCK_DECISIONS.find((d) => d.id === id)
  if (!d) throw new Error('Decision not found')
  return d
}

export async function evaluateDecision(id: string, variables: Record<string, unknown>): Promise<Record<string, unknown>> {
  const decision = await getDecision(id)
  // Simple mock evaluation
  const result: Record<string, unknown> = {}
  for (const output of decision.outputs) {
    result[output.name] = 'Mock result'
  }
  return result
}
