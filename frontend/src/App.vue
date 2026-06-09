<template>
  <el-container class="app-container">
    <el-header class="app-header" v-if="authStore.isLoggedIn">
      <div class="header-left">
        <h2>📚 本地知识库</h2>
      </div>
      <div class="header-right">
        <span class="user-info">{{ authStore.user?.username }} ({{ authStore.user?.deptName }})</span>
        <el-button type="danger" text @click="logout">退出</el-button>
      </div>
    </el-header>

    <el-container>
      <el-aside width="200px" v-if="authStore.isLoggedIn">
        <el-menu :router="true" :default-active="route.path" class="app-menu">
          <el-menu-item index="/search">
            <el-icon><Search /></el-icon> 知识检索
          </el-menu-item>
          <el-menu-item index="/import">
            <el-icon><Upload /></el-icon> 文档导入
          </el-menu-item>
          <el-menu-item index="/graph">
            <el-icon><Share /></el-icon> 知识图谱
          </el-menu-item>
          <el-menu-item index="/browse">
            <el-icon><Document /></el-icon> 结构化浏览
          </el-menu-item>
          <el-menu-item index="/users" v-if="authStore.isAdmin">
            <el-icon><User /></el-icon> 用户管理
          </el-menu-item>
          <el-menu-item index="/history">
            <el-icon><Clock /></el-icon> 导入历史
          </el-menu-item>
        </el-menu>
      </el-aside>

      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

function logout() {
  authStore.logout()
  router.push('/login')
}
</script>

<style>
body { margin: 0; font-family: "Microsoft YaHei", sans-serif; }
.app-container { height: 100vh; }
.app-header {
  display: flex; align-items: center; justify-content: space-between;
  background: #1a1a2e; color: #fff;
}
.app-header h2 { margin: 0; font-size: 18px; }
.header-right { display: flex; align-items: center; gap: 12px; }
.user-info { opacity: 0.8; font-size: 14px; }
.app-menu { height: 100%; border-right: 1px solid #eee; }
</style>
