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
                <el-radio value="信息技术部">信息技术部</el-radio>
                <el-radio value="办公室">办公室</el-radio>
                <el-radio value="研究室">研究室</el-radio>
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
                <el-radio value="信息技术部">信息技术部</el-radio>
                <el-radio value="办公室">办公室</el-radio>
                <el-radio value="研究室">研究室</el-radio>
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
                ? '例如: F:/code/knowledge/input/test.zip'
                : '例如: F:/code/knowledge/input/docs/'" />
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
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { importApi } from '@/api'
import { ElMessage } from 'element-plus'

const mode = ref('upload')
const pathMode = ref('file')
const target = ref('public')
const uploading = ref(false)
const file = ref<File | null>(null)
const serverPath = ref('')
const batchId = ref('')
const progressLoading = ref(false)
const progress = reactive({
  status: '', totalFiles: 0, processedFiles: 0, percent: 0, errors: ''
})

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
