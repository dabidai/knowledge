import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '@/api'

export interface UserInfo {
  id: number
  username: string
  role: 'admin' | 'default'
  deptName: string | null
  enabled: boolean
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || '')
  // 不从本地 user JSON 恢复权限，唯一来源是 login/me。
  const user = ref<UserInfo | null>(null)
  const status = ref<'unchecked' | 'authenticated' | 'anonymous' | 'unavailable'>(token.value ? 'unchecked' : 'anonymous')
  const isLoggedIn = computed(() => status.value === 'authenticated' && !!token.value && !!user.value)
  const isAdmin = computed(() => isLoggedIn.value && user.value?.role === 'admin')
  let pendingRestore: Promise<void> | null = null
  let generation = 0

  function logout() {
    generation++
    token.value = ''
    user.value = null
    status.value = 'anonymous'
    localStorage.removeItem('token')
    localStorage.removeItem('user')
  }

  async function login(username: string, password: string) {
    const res = await authApi.login({ username, password })
    generation++
    token.value = res.data.data.token
    user.value = res.data.data.user
    status.value = 'authenticated'
    localStorage.setItem('token', token.value)
    localStorage.removeItem('user')
  }

  async function restore() {
    if (status.value !== 'unchecked' && status.value !== 'unavailable') return
    if (pendingRestore) return pendingRestore
    const requestedToken = token.value
    const requestedGeneration = generation
    pendingRestore = (async () => {
      try {
        const res = await authApi.me()
        if (token.value !== requestedToken || generation !== requestedGeneration) return
        user.value = res.data.data
        status.value = 'authenticated'
      } catch (err: any) {
        if (token.value !== requestedToken || generation !== requestedGeneration) return
        if (err.response?.status === 401) logout()
        else {
          status.value = 'unavailable'
          throw err // 网络/服务异常保留凭证，页面可重试。
        }
      } finally {
        pendingRestore = null
      }
    })()
    return pendingRestore
  }

  return { token, user, status, isLoggedIn, isAdmin, login, restore, logout }
})
