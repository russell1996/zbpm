export interface User {
  id: string
  login: string
  fullName: string | null
  email: string | null
  active: boolean
  createdAt: string
  updatedAt: string
}

export interface CreateUserDTO {
  login: string
  fullName: string | null
  email: string | null
  active: boolean
}

export interface UpdateUserDTO {
  fullName: string | null
  email: string | null
  active: boolean
}
