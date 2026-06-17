import axios from 'axios'
import { useAuthStore } from '@/stores/auth'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: '/api',
  timeout: 300000,  // 导入上传超时 5 分钟
})

// 请求拦截器：附加 JWT
http.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器：统一错误处理
http.interceptors.response.use(
  res => {
    // 后端业务错误（HTTP 200 但业务 code 非 200）
    if (res.data && typeof res.data.code === 'number' && res.data.code >= 400) {
      ElMessage.error(res.data.message || '请求失败')
      return Promise.reject(new Error(res.data.message || '请求失败'))
    }
    return res
  },
  err => {
    if (err.response?.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      window.location.href = '/login'
    } else {
      ElMessage.error(err.response?.data?.message || '请求失败')
    }
    return Promise.reject(err)
  }
)

// ============ API 方法 ============

export const authApi = {
  login: (data: { username: string; password: string }) =>
    http.post('/auth/login', data),
}

export const searchApi = {
  search: (data: { query: string; topK?: number; category?: string; year?: string; itemType?: string }) =>
    http.post('/search', data),
}

export const chatApi = {
  send: (data: { question: string; topK?: number; history?: { role: string; content: string }[]; conversationId?: number }) =>
    http.post('/chat', data),
}

export const conversationApi = {
  list: () => http.get('/conversations'),
  messages: (id: number) => http.get(`/conversations/${id}/messages`),
  create: (title: string) => http.post('/conversations', { title }),
  rename: (id: number, title: string) => http.put(`/conversations/${id}`, { title }),
  delete: (id: number) => http.delete(`/conversations/${id}`),
}

export const importApi = {
  upload: (formData: FormData) =>
    http.post('/import/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }),
  fromPath: (data: { path: string; target: string }) =>
    http.post('/import/from-path', data),
  fromDir: (data: { path: string; target: string }) =>
    http.post('/import/from-dir', data),
  progress: (batchId: string) =>
    http.get(`/import/progress/${batchId}`),
  tasks: () =>
    http.get('/import/tasks'),
  deleteTask: (batchId: string) =>
    http.delete(`/import/tasks/${batchId}`),
  retryTask: (batchId: string) =>
    http.post(`/import/tasks/${batchId}/retry`),
}

export const browseApi = {
  tree: () => http.get('/browse/tree'),
  doc: (fileId: string) => http.get(`/browse/doc/${fileId}`),
}

export const graphApi = {
  query: (itemId?: string) =>
    http.get('/graph', { params: itemId ? { itemId } : {} }),
}

export const userApi = {
  list: () => http.get('/users'),
  create: (data: Record<string, string>) => http.post('/users', data),
  delete: (id: number) => http.delete(`/users/${id}`),
}

export const deptApi = {
  list: () => http.get('/departments'),
  create: (name: string) => http.post('/departments', { name }),
}

export default http
