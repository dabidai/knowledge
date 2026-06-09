<template>
  <div class="user-page">
    <el-card>
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <strong>👥 用户管理</strong>
          <el-button type="primary" @click="showCreate = true">新增用户</el-button>
        </div>
      </template>

      <el-table :data="users" stripe v-loading="loading">
        <el-table-column prop="username" label="用户名" />
        <el-table-column label="部门">
          <template #default="{ row }">{{ row.department?.name }}</template>
        </el-table-column>
        <el-table-column prop="role" label="角色" width="120">
          <template #default="{ row }">
            <el-tag :type="row.role === 'admin' ? 'danger' : 'info'">{{ row.role }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-popconfirm title="确认删除？" @confirm="handleDelete(row.id)">
              <template #reference>
                <el-button type="danger" size="small" link :disabled="row.role === 'admin'">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增用户对话框 -->
    <el-dialog v-model="showCreate" title="新增用户" width="400px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="用户名">
          <el-input v-model="form.username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" />
        </el-form-item>
        <el-form-item label="部门">
          <el-select v-model="form.dept" style="width:100%">
            <el-option v-for="d in depts" :key="d" :label="d" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.role" style="width:100%">
            <el-option label="普通用户" value="default" />
            <el-option label="管理员" value="admin" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" @click="handleCreate">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { userApi } from '@/api'
import { ElMessage } from 'element-plus'

const users = ref<any[]>([])
const loading = ref(false)
const showCreate = ref(false)
const depts = ['信息技术部', '办公室', '研究室']
const form = reactive({ username: '', password: '', dept: '信息技术部', role: 'default' })

async function loadUsers() {
  loading.value = true
  try {
    const res = await userApi.list()
    users.value = res.data.data
  } finally {
    loading.value = false
  }
}

async function handleCreate() {
  try {
    await userApi.create({ ...form })
    showCreate.value = false
    ElMessage.success('用户创建成功')
    loadUsers()
  } catch {}
}

async function handleDelete(id: number) {
  try {
    await userApi.delete(id)
    ElMessage.success('已删除')
    loadUsers()
  } catch {}
}

onMounted(loadUsers)
</script>
