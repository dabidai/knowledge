<template>
  <div class="search-page">
    <!-- 搜索框 -->
    <el-input v-model="query" size="large" placeholder="输入关键词搜索文档，支持自然语言提问..."
              @keyup.enter="handleSearch" clearable>
      <template #append>
        <el-button type="primary" :loading="loading" @click="handleSearch">
          <el-icon><Search /></el-icon> 搜索
        </el-button>
      </template>
    </el-input>

    <!-- 筛选条件 -->
    <div class="filter-bar">
      <el-select v-model="category" placeholder="事项分类" clearable size="small" style="width:140px">
        <el-option label="通知" value="通知" />
        <el-option label="通告" value="通告" />
        <el-option label="报告" value="报告" />
        <el-option label="请示" value="请示" />
        <el-option label="批复" value="批复" />
        <el-option label="意见" value="意见" />
        <el-option label="函" value="函" />
        <el-option label="纪要" value="纪要" />
        <el-option label="决定" value="决定" />
        <el-option label="公告" value="公告" />
      </el-select>
      <el-select v-model="year" placeholder="年度" clearable size="small" style="width:120px">
        <el-option v-for="y in years" :key="y" :label="y" :value="y" />
      </el-select>
      <el-select v-model="itemType" placeholder="事项类型" clearable size="small" style="width:120px">
        <el-option label="收文" value="收文" />
        <el-option label="发文" value="发文" />
      </el-select>
      <el-tag v-if="result" size="small" type="info" style="margin-left:auto">
        {{ result.sources?.length || 0 }} 条结果
      </el-tag>
    </div>

    <!-- 搜索结果 -->
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
import { ref, computed } from 'vue'
import { searchApi } from '@/api'

const query = ref('')
const loading = ref(false)
const result = ref<any>(null)
const category = ref('')
const year = ref('')
const itemType = ref('')

/** 生成最近 30 年的年份列表 */
const years = computed(() => {
  const currentYear = new Date().getFullYear()
  const list: string[] = []
  for (let y = currentYear; y >= currentYear - 30; y--) {
    list.push(String(y))
  }
  return list
})

/** 执行搜索 */
async function handleSearch() {
  if (!query.value.trim()) return
  loading.value = true
  try {
    const res = await searchApi.search({
      query: query.value,
      topK: 5,
      category: category.value || undefined,
      year: year.value || undefined,
      itemType: itemType.value || undefined,
    })
    result.value = res.data.data
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.search-page { max-width: 900px; margin: 0 auto; }
.filter-bar {
  display: flex; gap: 12px; align-items: center;
  margin-top: 12px; padding: 8px 0;
}
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
