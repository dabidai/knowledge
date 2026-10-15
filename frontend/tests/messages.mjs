export const messages = []
export const ElMessage = {
  error: message => messages.push(['error', message]),
  warning: message => messages.push(['warning', message]),
}
