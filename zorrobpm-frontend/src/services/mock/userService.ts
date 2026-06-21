import type { User, CreateUserDTO, UpdateUserDTO } from '@/entities/user/User'
import type { PagedData, IdDTO } from '@/types/api'

const MOCK_USERS: User[] = [
  {
    id: '1',
    login: 'admin',
    fullName: 'Administrator',
    email: 'admin@zorrobpm.dev',
    active: true,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
  },
  {
    id: '2',
    login: 'john.doe',
    fullName: 'John Doe',
    email: 'john@zorrobpm.dev',
    active: true,
    createdAt: '2024-01-15T10:30:00Z',
    updatedAt: '2024-01-15T10:30:00Z',
  },
  {
    id: '3',
    login: 'jane.smith',
    fullName: 'Jane Smith',
    email: 'jane@zorrobpm.dev',
    active: false,
    createdAt: '2024-02-01T08:00:00Z',
    updatedAt: '2024-03-01T12:00:00Z',
  },
]

let nextId = 4

export async function getMe(): Promise<User> {
  return MOCK_USERS[0]
}

export async function getUsers(params?: { pageIndex?: number; pageSize?: number; login?: string; active?: boolean }): Promise<PagedData<User>> {
  let filtered = [...MOCK_USERS]
  if (params?.login) {
    filtered = filtered.filter((u) => u.login.toLowerCase().includes(params.login!.toLowerCase()))
  }
  if (params?.active !== undefined) {
    filtered = filtered.filter((u) => u.active === params.active)
  }
  const page = params?.pageIndex ?? 0
  const size = params?.pageSize ?? 10
  const start = page * size
  return {
    pageIndex: page,
    pageSize: size,
    totalElements: filtered.length,
    data: filtered.slice(start, start + size),
  }
}

export async function getUser(id: string): Promise<User> {
  const user = MOCK_USERS.find((u) => u.id === id)
  if (!user) throw new Error('User not found')
  return user
}

export async function createUser(dto: CreateUserDTO): Promise<IdDTO> {
  const id = String(nextId++)
  const now = new Date().toISOString()
  MOCK_USERS.push({ ...dto, id, createdAt: now, updatedAt: now })
  return { id }
}

export async function updateUser(id: string, dto: UpdateUserDTO): Promise<IdDTO> {
  const user = MOCK_USERS.find((u) => u.id === id)
  if (!user) throw new Error('User not found')
  user.fullName = dto.fullName
  user.email = dto.email
  user.active = dto.active
  user.updatedAt = new Date().toISOString()
  return { id }
}
