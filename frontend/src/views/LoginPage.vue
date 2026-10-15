<template>
  <div class="login-wrapper">
    <el-card class="login-card">
      <template #header>
        <h2 style="text-align:center;margin:0">📚 本地知识库登录</h2>
      </template>
      <el-alert v-if="authStore.status === 'unavailable'" title="暂时无法确认现有登录"
                description="登录凭证仍保留，可以重试连接或重新登录。" type="warning" :closable="false" style="margin-bottom:16px">
        <el-button link type="primary" :loading="retrying" @click="retryIdentity">重试连接</el-button>
      </el-alert>
      <el-form :model="form" label-width="80px" @submit.prevent="handleLogin">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" placeholder="请输入密码"
                    @keyup.enter="handleLogin" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" style="width:100%"
                     @click="handleLogin">登 录</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { safeReturnPath } from '@/auth/navigation'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const loading = ref(false)
const retrying = ref(false)
const form = reactive({ username: '', password: '' })

async function retryIdentity() {
  if (retrying.value) return
  retrying.value = true
  try {
    await authStore.restore()
    if (authStore.isLoggedIn) await router.replace(safeReturnPath(route.query.redirect))
  } catch {
    ElMessage.error('连接仍不可用，请稍后重试')
  } finally {
    retrying.value = false
  }
}

async function handleLogin() {
  if (!form.username || !form.password || loading.value) return
  loading.value = true
  try {
    await authStore.login(form.username, form.password)
    await router.replace(safeReturnPath(route.query.redirect))
  } catch {
    // API 层已给出错误提示，保留输入供重试。
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrapper {
  display: flex; align-items: center; justify-content: center;
  height: 100vh; background: #f0f2f5;
}
.login-card { width: 400px; }
</style>
