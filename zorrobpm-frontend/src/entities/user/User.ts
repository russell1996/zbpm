export type UserRole = 'ADMIN' | 'USER' | 'SUPER_ADMIN'

export interface User {
  id: string
  username: string
  fullName: string | null
  email: string | null
  role: UserRole
  active: boolean
  forcePasswordChange: boolean
  createdAt: string
  updatedAt: string
}

export interface CreateUserDTO {
  username: string
  password: string
  fullName: string | null
  email: string | null
  role: UserRole
  active: boolean
}

export interface UpdateUserDTO {
  fullName: string | null
  email: string | null
  role: UserRole
  active: boolean
  /** Optional: when set, resets the password. */
  password?: string
}

export interface LoginDTO {
  username: string
  password: string
}

export interface AuthResponse {
  token: string
  user: User
}
