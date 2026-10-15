/** 返回路径只允许本站路由；拒绝协议、反斜杠、控制字符和登录循环。 */
export function safeReturnPath(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')
      || /[\\\\\u0000-\u0020]/.test(value)) return '/chat'
  const path = value.split(/[?#]/)[0]
  try {
    const decoded = decodeURIComponent(path)
    if (decoded.startsWith('//') || /[\\\\\u0000-\u0020]/.test(decoded)
        || decoded === '/login' || decoded.startsWith('/login/')) return '/chat'
  } catch { return '/chat' }
  return value
}
