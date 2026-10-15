import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ElMessage } from 'element-plus'
import { safeReturnPath } from '@/auth/navigation'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/LoginPage.vue'),
    },
    {
      path: '/search',
      name: 'Search',
      component: () => import('@/views/SearchPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/chat',
      name: 'Chat',
      component: () => import('@/views/ChatPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/import',
      name: 'Import',
      component: () => import('@/views/ImportPage.vue'),
      meta: { requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/graph',
      name: 'Graph',
      component: () => import('@/views/GraphPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/browse',
      name: 'Browse',
      component: () => import('@/views/BrowsePage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/users',
      name: 'Users',
      component: () => import('@/views/UserPage.vue'),
      meta: { requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/departments',
      name: 'Departments',
      component: () => import('@/views/DeptPage.vue'),
      meta: { requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/history',
      name: 'History',
      component: () => import('@/views/ImportHistoryPage.vue'),
      meta: { requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/',
      redirect: '/chat',
    },
  ],
})

router.beforeEach(async (to) => {
  const authStore = useAuthStore()
  if (to.meta.requiresAuth || (to.path === '/login' && authStore.status !== 'unavailable')) {
    try {
      await authStore.restore()
    } catch {
      if (to.path === '/login') return true // 允许用户重新登录，避免断网时路由卡死。
      ElMessage.error('暂时无法确认登录状态，请检查连接后重试')
      return { path: '/login', query: { redirect: safeReturnPath(to.fullPath) } }
    }
  }
  if (to.meta.requiresAuth && !authStore.isLoggedIn) {
    return { path: '/login', query: { redirect: safeReturnPath(to.fullPath) } }
  } else if (to.meta.requiresAdmin && !authStore.isAdmin) {
    ElMessage.warning('当前账号没有访问该页面的权限')
    return '/search'
  }
  if (to.path === '/login' && authStore.isLoggedIn) return safeReturnPath(to.query.redirect)
  return true
})

window.addEventListener('auth-expired', () => {
  const route = router.currentRoute.value
  if (route.path !== '/login') void router.replace({ path: '/login', query: { redirect: safeReturnPath(route.fullPath) } })
})

export default router
