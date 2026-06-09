import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

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
      path: '/import',
      name: 'Import',
      component: () => import('@/views/ImportPage.vue'),
      meta: { requiresAuth: true },
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
      path: '/history',
      name: 'History',
      component: () => import('@/views/ImportHistoryPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/',
      redirect: '/search',
    },
  ],
})

router.beforeEach((to, _from, next) => {
  const authStore = useAuthStore()

  if (to.meta.requiresAuth && !authStore.isLoggedIn) {
    next('/login')
  } else if (to.meta.requiresAdmin && !authStore.isAdmin) {
    next('/search')
  } else {
    next()
  }
})

export default router
