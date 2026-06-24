<template>
  <div class="dept-page">
    <el-card>
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <strong>部门管理</strong>
          <el-button type="primary" @click="showCreate = true">新增部门</el-button>
        </div>
      </template>

      <el-table :data="departments" stripe v-loading="loading" empty-text="暂无部门">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="name" label="部门名称" />
        <el-table-column prop="createdAt" label="创建时间" width="180">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-popconfirm title="确认删除该部门？部门下有用户时无法删除" @confirm="handleDelete(row.id)">
              <template #reference>
                <el-button type="danger" size="small" link>删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增部门对话框 -->
    <el-dialog v-model="showCreate" title="新增部门" width="360px">
      <el-form label-width="80px" @submit.prevent="handleCreate">
        <el-form-item label="部门名称">
          <el-input v-model="newName" placeholder="请输入部门名称" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" @click="handleCreate" :disabled="!newName.trim()">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { deptApi } from '@/api'
import { ElMessage } from 'element-plus'

const departments = ref<any[]>([])
const loading = ref(false)
const showCreate = ref(false)
const newName = ref('')

async function load() {
  loading.value = true
  try {
    const res = await deptApi.list()
    departments.value = res.data.data || []
  } finally {
    loading.value = false
  }
}

async function handleCreate() {
  const name = newName.value.trim()
  if (!name) return
  try {
    await deptApi.create(name)
    showCreate.value = false
    newName.value = ''
    ElMessage.success('部门创建成功')
    load()
  } catch {}
}

async function handleDelete(id: number) {
  try {
    await deptApi.delete(id)
    ElMessage.success('已删除')
    load()
  } catch {}
}

function formatDate(date: string) {
  if (!date) return ''
  return new Date(date).toLocaleString('zh-CN')
}

onMounted(load)
</script>
