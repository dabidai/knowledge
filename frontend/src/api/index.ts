import axios, { AxiosError } from 'axios'
import { useAuthStore } from '@/stores/auth'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: '/api',
  timeout: 300000,  // 导入上传超时 5 分钟
})

// 请求拦截器：附加 JWT
http.interceptors.request.use(config => {
  const token = useAuthStore().token
  if (config.url === '/auth/login') return config
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

function handleError(err: any) {
  const url = err.config?.url
  if (url === '/auth/me') return Promise.reject(err) // restore 区分 401 与网络故障。
  if (err.response?.status === 401 && url !== '/auth/login') {
    const auth = useAuthStore()
    // 迟到的旧请求不能清除用户刚建立的新登录。
    if (auth.token && err.config?.headers?.Authorization === `Bearer ${auth.token}`) {
      auth.logout()
      ElMessage.warning('登录已失效，请重新登录')
      window.dispatchEvent(new Event('auth-expired'))
    }
  } else {
    ElMessage.error(err.response?.data?.message || (err.response ? '请求失败' : '网络连接失败，请稍后重试'))
  }
  return Promise.reject(err)
}

// 响应拦截器：统一错误处理
http.interceptors.response.use(
  res => {
    // 后端业务错误（HTTP 200 但业务 code 非 200）
    if (res.data && typeof res.data.code === 'number' && res.data.code >= 400) {
      return handleError(new AxiosError(res.data.message, undefined, res.config, res.request,
        { ...res, status: res.data.code }))
    }
    return res
  },
  handleError
)

// ============ API 方法 ============

export const authApi = {
  me: () => http.get('/auth/me', { timeout: 10000 }),
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
  tasks: (page = 0, size = 20) =>
    http.get('/import/tasks', { params: { page, size } }),
  deleteTask: (batchId: string) =>
    http.delete(`/import/tasks/${batchId}`),
  retryTask: (batchId: string) =>
    http.post(`/import/tasks/${batchId}/retry`),
  browseDir: (path?: string) =>
    http.get('/import/browse-dir', { params: path ? { path } : {} }),
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
  list: (page = 0, size = 20) => http.get('/users', { params: { page, size } }),
  create: (data: Record<string, string>) => http.post('/users', data),
  delete: (id: number) => http.delete(`/users/${id}`),
}

export const deptApi = {
  list: (page = 0, size = 50) => http.get('/departments', { params: { page, size } }),
  create: (name: string) => http.post('/departments', { name }),
  delete: (id: number) => http.delete(`/departments/${id}`),
}

export default http
