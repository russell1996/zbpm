import axios from 'axios'
import { createRefreshInterceptor } from './refreshInterceptor'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/api',
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true, // Send httpOnly cookie automatically
})

createRefreshInterceptor(api)

export default api
