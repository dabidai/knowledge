<template>
  <div class="search-page">
    <el-input v-model="query" size="large" placeholder="输入关键词搜索文档，支持自然语言提问..."
              @keyup.enter="handleSearch" clearable>
      <template #append>
        <el-button type="primary" :loading="loading" @click="handleSearch">
          <el-icon><Search /></el-icon> 搜索
        </el-button>
      </template>
    </el-input>

    <div v-if="result" class="result-area">
      <!-- AI 答案 -->
      <el-card class="answer-card">
        <template #header><strong>🤖 AI 回答</strong></template>
        <div class="answer-text">{{ result.answer }}</div>
      </el-card>

      <!-- 来源文档 -->
      <el-card v-if="result.sources.length > 0" class="sources-card">
        <template #header><strong>📄 来源文档 ({{ result.sources.length }})</strong></template>
        <div v-for="src in result.sources" :key="src.fileId" class="source-item">
          <div class="source-name">{{ src.fileName }}</div>
          <div class="source-snippet" v-html="src.snippet || '无预览'"></div>
          <div class="source-meta">
            <el-tag size="small">{{ src.deptName }}</el-tag>
            <a :href="src.downloadUrl" target="_blank" v-if="src.downloadUrl">
              <el-button size="small" type="primary" link>⬇ 下载</el-button>
            </a>
          </div>
        </div>
      </el-card>

      <el-empty v-if="!loading && result.sources.length === 0" description="未找到相关文档" />
    </div>

    <el-empty v-if="!result && !loading" description="输入问题开始搜索" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { searchApi } from '@/api'

const query = ref('')
const loading = ref(false)
const result = ref<any>(null)

async function handleSearch() {
  if (!query.value.trim()) return
  loading.value = true
  try {
    const res = await searchApi.search({ query: query.value, topK: 5 })
    result.value = res.data.data
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.search-page { max-width: 900px; margin: 0 auto; }
.result-area { margin-top: 24px; }
.answer-card { margin-bottom: 16px; }
.answer-text { white-space: pre-wrap; line-height: 1.8; font-size: 15px; }
.source-item {
  padding: 12px 0; border-bottom: 1px solid #f0f0f0;
}
.source-name { font-weight: bold; margin-bottom: 6px; }
.source-snippet { color: #666; font-size: 14px; margin-bottom: 6px; }
.source-meta { display: flex; align-items: center; gap: 12px; }
</style>
