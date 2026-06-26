<template>
  <div class="import-page">
    <el-card>
      <template #header><strong>📦 文档导入</strong></template>

      <el-tabs v-model="mode" type="border-card">
        <!-- ========== 上传文件 Tab ========== -->
        <el-tab-pane label="上传文件" name="upload">
          <el-form label-width="100px">
            <el-form-item label="目标位置">
              <el-radio-group v-model="target">
                <el-radio value="public">公共区</el-radio>
                <el-radio v-for="d in departments" :key="d.id" :value="d.name"
                  :disabled="!authStore.isAdmin && d.name !== authStore.user?.deptName">
                  {{ d.name }}
                </el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="选择文件">
              <el-upload ref="uploadRef" drag :auto-upload="false" :on-change="handleFileChange"
                :limit="1" accept=".zip,.7z,.tar,.tar.gz,.tgz,.gz">
                <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
                <div class="el-upload__text">拖拽压缩包到此处，或<em>点击选择</em></div>
              </el-upload>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="uploading" @click="handleUpload"
                :disabled="!file"><el-icon><Upload /></el-icon> 开始导入</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- ========== 服务器路径 Tab ========== -->
        <el-tab-pane label="服务器路径" name="path">
          <el-form label-width="100px">
            <el-form-item label="目标位置">
              <el-radio-group v-model="target">
                <el-radio value="public">公共区</el-radio>
                <el-radio v-for="d in departments" :key="d.id" :value="d.name"
                  :disabled="!authStore.isAdmin && d.name !== authStore.user?.deptName">
                  {{ d.name }}
                </el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="导入模式">
              <el-radio-group v-model="pathMode">
                <el-radio value="file">压缩包文件</el-radio>
                <el-radio value="dir">扫描文件夹</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="服务器路径">
              <el-input v-model="serverPath" :placeholder="pathMode === 'file'
                ? '例如: /data/documents/test.zip'
                : '例如: /data/documents/2024年文件/'" />
              <el-button style="margin-left:8px" @click="openBrowser">
                <el-icon><FolderOpened /></el-icon> 浏览
              </el-button>
              <div class="el-upload__tip" style="margin-top:4px">
                <template v-if="pathMode === 'file'">输入压缩包路径（zip/7z/tar），支持大文件</template>
                <template v-else>输入文件夹路径，自动扫描目录下所有文档（pdf/doc/docx/ofd/wps）</template>
              </div>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="uploading" @click="handlePathImport"
                :disabled="!serverPath.trim()"><el-icon><Upload /></el-icon> 开始导入</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <!-- 导入进度 -->
      <div v-if="batchId" class="progress-area">
        <el-divider />
        <p><strong>导入任务:</strong> {{ batchId }}</p>
        <el-progress :percentage="progress.percent" :status="progressStatus" />
        <p>状态: {{ progress.status }} | {{ progress.processedFiles }} / {{ progress.totalFiles }}</p>
        <el-button @click="refreshProgress" :loading="progressLoading">刷新进度</el-button>
        <el-alert v-if="progress.errors" type="error" :title="progress.errors" style="margin-top:12px" />
      </div>
    </el-card>

    <!-- 目录浏览弹窗 -->
    <el-dialog v-model="browserVisible" title="浏览服务器目录" width="600px">
      <div style="margin-bottom:8px;color:#999">当前: {{ browserCurrent }}</div>
      <div style="margin-bottom:8px">
        <el-button v-if="browserCurrent !== '/'" size="small" @click="goToParent">
          <el-icon><Back /></el-icon> 返回上级
        </el-button>
      </div>

      <el-table :data="browserEntries" @row-dblclick="handleBrowserClick" highlight-current-row
        max-height="400" size="small">
        <el-table-column label="名称" prop="name">
          <template #default="{ row }">
            <el-icon v-if="row.isDir" style="margin-right:4px;color:#409EFF">
              <Folder />
            </el-icon>
            <el-icon v-else style="margin-right:4px;color:#67C23A">
              <Document />
            </el-icon>
            {{ row.name }}
          </template>
        </el-table-column>
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            {{ row.isDir ? '目录' : '文件' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button v-if="row.isDir" size="small" @click="handleBrowserClick(row)">
              进入
            </el-button>
            <el-button v-else size="small" @click="selectBrowserPath(row)">
              选择
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="browserVisible = false">取消</el-button>
        <el-button type="primary" @click="selectCurrentDir" :disabled="browserCurrent === importRoot">
          <el-icon><CircleCheck /></el-icon> 选择当前目录
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { importApi, deptApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { ElMessage } from 'element-plus'
import { Folder, Document, FolderOpened, CircleCheck, Upload, UploadFilled, Back } from '@element-plus/icons-vue'

const authStore = useAuthStore()
const mode = ref('upload')
const pathMode = ref('file')
const target = ref('public')
const uploading = ref(false)
const file = ref<File | null>(null)
const serverPath = ref('')
const batchId = ref('')
const progressLoading = ref(false)
const departments = ref<{ id: number; name: string }[]>([])
const progress = reactive({
  status: '', totalFiles: 0, processedFiles: 0, percent: 0, errors: ''
})

onMounted(async () => {
  try {
    const res = await deptApi.list()
    departments.value = res.data.data.content || []
    // admin 默认选公共区，普通用户默认选自己的部门
    if (!authStore.isAdmin && authStore.user?.deptName) {
      target.value = authStore.user.deptName
    }
  } catch { /* 部门加载失败则只显示公共区 */ }
})

// ========== 目录浏览器 ==========
const browserVisible = ref(false)
const browserCurrent = ref('')
const browserEntries = ref<any[]>([])
const importRoot = ref('')

async function openBrowser() {
  browserVisible.value = true
  // 如果 serverPath 已有值，从该路径浏览；否则从根目录
  await loadBrowserDir(serverPath.value.trim())
}

async function loadBrowserDir(path: string) {
  try {
    const res = await importApi.browseDir(path || '')
    const data = res.data.data as any
    importRoot.value = data.root?.name || ''
    browserCurrent.value = data.current || ''
    browserEntries.value = data.entries || []
  } catch {
    ElMessage.error('读取目录失败')
  }
}

function handleBrowserClick(row: any) {
  if (row.isDir) {
    loadBrowserDir(row.path)
  }
}

function goToParent() {
  const parent = browserEntries.value.find(e => e.name === '..')
  if (parent) loadBrowserDir(parent.path)
}

function selectBrowserPath(row: any) {
  // 选择了文件：填入路径（后端已返回绝对路径）
  serverPath.value = row.path
  browserVisible.value = false
}

function selectCurrentDir() {
  // 选择当前浏览的目录作为导入目标
  serverPath.value = browserCurrent.value
  browserVisible.value = false
}

const progressStatus = computed(() => {
  if (progress.status === 'complete') return 'success'
  if (progress.status === 'failed') return 'exception'
  return ''
})

function handleFileChange(uploadFile: any) {
  file.value = uploadFile.raw
}

async function handleUpload() {
  if (!file.value) return
  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('file', file.value)
    formData.append('target', target.value)

    const res = await importApi.upload(formData)
    batchId.value = res.data.data.batchId
    ElMessage.success('导入任务已创建')
    pollProgress()
  } finally {
    uploading.value = false
  }
}

async function handlePathImport() {
  if (!serverPath.value.trim()) return
  uploading.value = true
  try {
    const api = pathMode.value === 'dir' ? importApi.fromDir : importApi.fromPath
    const res = await api({
      path: serverPath.value.trim(),
      target: target.value,
    })
    batchId.value = res.data.data.batchId
    ElMessage.success('导入任务已创建')
    pollProgress()
  } finally {
    uploading.value = false
  }
}

function pollProgress() {
  const timer = setInterval(async () => {
    if (!batchId.value) { clearInterval(timer); return }
    const res = await importApi.progress(batchId.value)
    Object.assign(progress, res.data.data)
    if (progress.status === 'complete' || progress.status === 'failed') {
      clearInterval(timer)
    }
  }, 3000)
}

async function refreshProgress() {
  if (!batchId.value) return
  progressLoading.value = true
  try {
    const res = await importApi.progress(batchId.value)
    Object.assign(progress, res.data.data)
  } finally {
    progressLoading.value = false
  }
}
</script>

<style scoped>
.import-page { max-width: 700px; margin: 0 auto; }
.progress-area { margin-top: 16px; }
</style>
