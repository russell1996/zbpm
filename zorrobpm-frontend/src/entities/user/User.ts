export type UserRole = 'ADMIN' | 'USER' | 'SUPER_ADMIN'

export interface User {
  id: string
  username: string
  fullName: string | null
  email: string | null
  role: UserRole
  active: boolean
  forcePasswordChange: boolean
  /** WO-INT-4: 'HUMAN' (default) or 'SYSTEM'. A system account is an integration, not a person. */
  userType?: 'HUMAN' | 'SYSTEM'
  /** WO-ACL-18 criterion 5: an outstanding invite token means the user has not set a password yet. */
  pendingInvitation?: boolean
  createdAt: string
  updatedAt: string
}

export type CreationMode = 'PASSWORD' | 'INVITE'

export interface CreateUserDTO {
  username: string
  password: string
  fullName: string | null
  email: string | null
  role: UserRole
  active: boolean
  /** WO-ACL-18: 'INVITE' (default — one-time link) or 'PASSWORD' (admin sets it directly). */
  creationMode?: CreationMode
  /** WO-UI-10 Phase 2: 'HUMAN' (default) or 'SYSTEM'. */
  userType?: 'HUMAN' | 'SYSTEM'
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
