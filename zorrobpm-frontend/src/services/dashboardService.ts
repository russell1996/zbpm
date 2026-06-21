import api from './api'
import type { DashboardData } from '@/types/api'

export async function getDashboard(): Promise<DashboardData> {
  const { data } = await api.get<DashboardData>('/ui/dashboard')
  return data
}
