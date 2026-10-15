import './register.mjs'
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosError } from 'axios'
import { messages } from './messages.mjs'

const storage = new Map()
globalThis.localStorage = {
  getItem: key => storage.get(key) ?? null,
  setItem: (key, value) => storage.set(key, String(value)),
  removeItem: key => storage.delete(key),
}
globalThis.window = new EventTarget()
const { useAuthStore } = await import('../src/stores/auth.ts')
const { default: http } = await import('../src/api/index.ts')
const { safeReturnPath } = await import('../src/auth/navigation.ts')
const profile = { id: 1, username: 'owner', role: 'admin', deptName: 'A', enabled: true }
const success = (config, data) => ({ config, status: 200, statusText: 'OK', headers: {}, data: { code: 200, data } })
const denied = config => Promise.reject(new AxiosError('unauthorized', undefined, config, null,
  { config, status: 401, statusText: 'Unauthorized', headers: {}, data: { message: '凭证无效' } }))

beforeEach(() => { storage.clear(); messages.length = 0; setActivePinia(createPinia()) })

test('local user JSON cannot establish privileges; concurrent restoration makes one me request', async () => {
  storage.set('token', 'saved')
  storage.set('user', '{broken JSON')
  const auth = useAuthStore()
  assert.equal(auth.isLoggedIn, false)
  assert.equal(auth.isAdmin, false)
  let calls = 0
  http.defaults.adapter = async config => { calls++; return success(config, profile) }
  await Promise.all([auth.restore(), auth.restore()])
  assert.equal(calls, 1)
  assert.equal(auth.isAdmin, true)
})

test('network failure preserves token and permits retry; me 401 clears both store and persistence', async () => {
  storage.set('token', 'saved')
  const auth = useAuthStore()
  http.defaults.adapter = config => Promise.reject(new AxiosError('offline', 'ERR_NETWORK', config))
  await assert.rejects(auth.restore())
  assert.equal(auth.token, 'saved')
  assert.equal(auth.status, 'unavailable')
  http.defaults.adapter = async config => success(config, profile)
  await auth.restore()
  assert.equal(auth.isLoggedIn, true)
  auth.logout(); storage.set('token', 'expired'); auth.token = 'expired'; auth.status = 'unchecked'
  http.defaults.adapter = denied
  await auth.restore()
  assert.equal(auth.token, '')
  assert.equal(storage.has('token'), false)
})

test('login rejection reports credentials without session-expired redirect', async () => {
  const auth = useAuthStore()
  let events = 0
  const listener = () => events++
  window.addEventListener('auth-expired', listener)
  http.defaults.adapter = denied
  await assert.rejects(auth.login('owner', 'wrong-password'))
  assert.equal(events, 0)
  assert.deepEqual(messages, [['error', '凭证无效']])
  window.removeEventListener('auth-expired', listener)
})

test('parallel 401 expires once, and old unauthorized requests cannot clear a new login', async () => {
  const auth = useAuthStore()
  auth.token = 'old'; auth.user = profile; auth.status = 'authenticated'; storage.set('token', 'old')
  let events = 0
  const listener = () => events++
  window.addEventListener('auth-expired', listener)
  http.defaults.adapter = denied
  await Promise.allSettled([http.get('/protected'), http.get('/protected')])
  assert.equal(events, 1)
  assert.equal(messages.filter(m => m[0] === 'warning').length, 1)
  auth.token = 'old'; auth.status = 'authenticated'
  let rejectOld
  const waiting = new Promise((_, reject) => { rejectOld = reject })
  let oldConfig
  http.defaults.adapter = config => { oldConfig = config; return waiting }
  const pending = http.get('/protected')
  await new Promise(resolve => setTimeout(resolve, 0))
  http.defaults.adapter = async config => success(config, { token: 'new', user: profile })
  await auth.login('owner', 'password123')
  rejectOld(new AxiosError('late unauthorized', undefined, oldConfig, null,
    { status: 401, config: oldConfig, headers: {}, statusText: 'Unauthorized', data: {} }))
  await assert.rejects(pending)
  assert.equal(auth.token, 'new')
  assert.equal(auth.isLoggedIn, true)
  assert.equal(events, 1)
  window.removeEventListener('auth-expired', listener)
})

test('late me response after logout cannot restore previous identity', async () => {
  storage.set('token', 'old')
  const auth = useAuthStore()
  let complete
  http.defaults.adapter = config => new Promise(resolve => { complete = () => resolve(success(config, profile)) })
  const pending = auth.restore()
  await new Promise(resolve => setTimeout(resolve, 0))
  auth.logout()
  complete(); await pending
  assert.equal(auth.isLoggedIn, false)
  assert.equal(auth.user, null)
})

test('return path preserves local route and rejects external, encoded or login-loop destinations', () => {
  assert.equal(safeReturnPath('/browse?file=123#section'), '/browse?file=123#section')
  for (const value of ['https://evil.example', '//evil.example', '/\\evil.example', '/%2fevil.example',
    '/%5cevil.example', '/login', '/login?redirect=/users', '/%6cogin', '/%ZZ', null, ['/users']]) {
    assert.equal(safeReturnPath(value), '/chat')
  }
})

test('forbidden response retains identity and does not trigger a login redirect', async () => {
  const auth = useAuthStore()
  auth.token = 'valid'; auth.user = profile; auth.status = 'authenticated'
  http.defaults.adapter = config => Promise.reject(new AxiosError('forbidden', undefined, config, null,
    { config, status: 403, headers: {}, statusText: 'Forbidden', data: { message: '权限不足' } }))
  await assert.rejects(http.get('/protected'))
  assert.equal(auth.isLoggedIn, true)
  assert.deepEqual(messages, [['error', '权限不足']])
})

test('login does not send previous bearer credentials and late me cannot overwrite same-token new login', async () => {
  storage.set('token', 'same-token')
  const auth = useAuthStore()
  let complete
  http.defaults.adapter = config => new Promise(resolve => {
    complete = () => resolve(success(config, { ...profile, role: 'default' }))
  })
  const pending = auth.restore()
  await new Promise(resolve => setTimeout(resolve, 0))
  auth.logout()
  http.defaults.adapter = async config => {
    assert.equal(config.headers.Authorization, undefined)
    return success(config, { token: 'same-token', user: profile })
  }
  await auth.login('owner', 'password123')
  complete(); await pending
  assert.equal(auth.isAdmin, true)
})
