<template>
  <div class="history-page">
    <el-card>
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <strong>📜 导入历史</strong>
          <el-button @click="refresh" :loading="loading" size="small">刷新</el-button>
        </div>
      </template>

      <el-table :data="tasks" v-loading="loading" stripe empty-text="暂无导入记录">
        <el-table-column prop="batchId" label="批次号" min-width="180">
          <template #default="{ row }">
            <el-tag type="info" size="small">{{ row.batchId?.substring(0, 8) }}...</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="archiveName" label="压缩包" min-width="180" show-overflow-tooltip />
        <el-table-column prop="targetDept" label="目标位置" width="120">
          <template #default="{ row }">
            <el-tag :type="row.targetDept === 'public' ? 'success' : ''" size="small">
              {{ row.targetDept === 'public' ? '公共区' : row.targetDept }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'complete'" type="success" size="small">完成</el-tag>
            <el-tag v-else-if="row.status === 'failed'" type="danger" size="small">失败</el-tag>
            <el-tag v-else-if="row.status === 'pending'" type="info" size="small">等待中</el-tag>
            <el-tag v-else type="warning" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="160">
          <template #default="{ row }">
            <template v-if="row.totalFiles > 0">
              <el-progress
                :percentage="Math.round(row.processedFiles * 100 / row.totalFiles)"
                :status="row.status === 'failed' ? 'exception' : row.status === 'complete' ? 'success' : undefined"
              />
              <span style="font-size:12px;color:#999">{{ row.processedFiles }} / {{ row.totalFiles }}</span>
            </template>
            <span v-else style="font-size:12px;color:#999">--</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170" />
        <el-table-column prop="completedAt" label="完成时间" width="170">
          <template #default="{ row }">
            {{ row.completedAt || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row)">详情</el-button>
            <el-button v-if="row.status === 'failed'" link type="warning" @click="handleRetry(row)">
              重试
            </el-button>
            <el-button v-if="row.status === 'failed' || row.status === 'complete'"
                       link type="danger" @click="handleDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div style="margin-top:16px;display:flex;justify-content:flex-end">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          @current-change="refresh"
          @size-change="refresh"
        />
      </div>
    </el-card>

    <!-- 详情弹窗 -->
    <el-dialog v-model="dialogVisible" title="导入详情" width="600px">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="批次号">{{ currentTask?.batchId }}</el-descriptions-item>
        <el-descriptions-item label="压缩包">{{ currentTask?.archiveName }}</el-descriptions-item>
        <el-descriptions-item label="目标位置">{{ currentTask?.targetDept }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag v-if="currentTask?.status === 'complete'" type="success" size="small">完成</el-tag>
          <el-tag v-else-if="currentTask?.status === 'failed'" type="danger" size="small">失败</el-tag>
          <el-tag v-else type="info" size="small">{{ currentTask?.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="文件进度">
          {{ currentTask?.totalFiles > 0 ? `${currentTask?.processedFiles} / ${currentTask?.totalFiles}` : '--' }}
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ currentTask?.createdAt }}</el-descriptions-item>
        <el-descriptions-item label="完成时间">{{ currentTask?.completedAt || '-' }}</el-descriptions-item>
        <el-descriptions-item label="错误信息" :span="2">
          <span v-if="currentTask?.errors" style="color:red">{{ currentTask.errors }}</span>
          <span v-else style="color:#999">无</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { importApi } from '@/api'

const tasks = ref<any[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const dialogVisible = ref(false)
const currentTask = ref<any>(null)

async function refresh() {
  loading.value = true
  try {
    const res = await importApi.tasks(page.value - 1, size.value)
    tasks.value = res.data.data.content || []
    total.value = res.data.data.totalElements || 0
  } finally {
    loading.value = false
  }
}

function showDetail(task: any) {
  currentTask.value = task
  dialogVisible.value = true
}

import { ElMessage, ElMessageBox } from 'element-plus'

async function handleRetry(task: any) {
  try {
    await ElMessageBox.confirm('重置该任务状态后，需重新上传文件导入。确定重试？', '确认重试')
    await importApi.retryTask(task.batchId)
    ElMessage.success('任务已重置，请重新上传文件')
    refresh()
  } catch { /* 取消 */ }
}

async function handleDelete(task: any) {
  try {
    await ElMessageBox.confirm('确定要删除该导入记录吗？此操作不可撤销。', '确认删除', {
      type: 'warning',
    })
    await importApi.deleteTask(task.batchId)
    ElMessage.success('任务已删除')
    refresh()
  } catch { /* 取消 */ }
}

refresh()
</script>

<style scoped>
.history-page { max-width: 1200px; margin: 0 auto; }
</style>
