<template>
  <div class="graph-page">
    <el-container style="height: calc(100vh - 140px)">
      <!-- 左侧搜索面板 -->
      <el-aside width="300px" class="search-panel">
        <el-card shadow="never" style="height:100%">
          <template #header><strong>🔍 事项搜索</strong></template>

          <el-input v-model="searchQuery" placeholder="搜索事项标题..." clearable
                    @keyup.enter="handleSearch" style="margin-bottom:12px">
            <template #append>
              <el-button :loading="searchLoading" @click="handleSearch">
                <el-icon><Search /></el-icon>
              </el-button>
            </template>
          </el-input>

          <div v-loading="searchLoading" style="height:calc(100% - 100px);overflow-y:auto">
            <div v-if="searchResults.length === 0 && !searchLoading" style="color:#999;text-align:center;margin-top:40px">
              输入关键词搜索事项，或直接查看全局概览
            </div>
            <div
              v-for="item in searchResults"
              :key="item.id"
              class="search-item"
              :class="{ active: selectedItemId === item.id }"
              @click="selectItem(item)"
            >
              <div class="item-title">{{ item.label }}</div>
              <div class="item-meta">
                <el-tag size="small">{{ item.properties?.category || '未分类' }}</el-tag>
                <span style="margin-left:8px;font-size:12px;color:#999">
                  {{ item.properties?.docCount || 0 }} 个文档
                </span>
              </div>
            </div>
          </div>

          <div style="position:absolute;bottom:60px;left:20px;right:20px">
            <el-button type="primary" @click="loadOverview" :loading="graphLoading" style="width:100%">
              全局概览
            </el-button>
          </div>
        </el-card>
      </el-aside>

      <!-- 右侧图谱可视化 -->
      <el-main class="graph-panel">
        <div v-loading="graphLoading" style="height:100%;position:relative">
          <div ref="chartRef" style="width:100%;height:100%"></div>
          <el-empty v-if="!graphLoading && nodes.length === 0" description="暂无图谱数据，请先导入文档" />
        </div>
      </el-main>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick } from 'vue'
import { graphApi, searchApi } from '@/api'
import * as echarts from 'echarts'

interface GraphNode {
  id: string
  label: string
  type: 'department' | 'item' | 'document' | 'opinion' | 'user'
  properties?: Record<string, any>
}

interface GraphEdge {
  source: string
  target: string
  label: string
}

const chartRef = ref<HTMLElement | null>(null)
const graphLoading = ref(false)
const searchLoading = ref(false)
const searchQuery = ref('')
const searchResults = ref<GraphNode[]>([])
const selectedItemId = ref('')
const nodes = ref<GraphNode[]>([])
const edges = ref<GraphEdge[]>([])

let chartInstance: echarts.ECharts | null = null

onMounted(() => {
  loadOverview()
  window.addEventListener('resize', () => chartInstance?.resize())
})

async function loadOverview() {
  graphLoading.value = true
  selectedItemId.value = ''
  try {
    const res = await graphApi.query()
    const data = res.data?.data
    if (!data) {
      console.error('图谱数据为空，可能 Neo4j 未运行或数据库无数据')
      return
    }
    nodes.value = data.nodes || []
    edges.value = data.edges || []
    // 同时作为搜索结果展示
    searchResults.value = (data.nodes || []).filter((n: GraphNode) => n.type === 'item')
    await nextTick()
    renderChart()
  } catch (err: any) {
    console.error('加载图谱失败:', err)
  } finally {
    graphLoading.value = false
  }
}

async function handleSearch() {
  if (!searchQuery.value.trim()) return
  searchLoading.value = true
  try {
    const res = await searchApi.search({ query: searchQuery.value, topK: 10 })
    // 如果搜索结果有关联的事项ID，则查询对应图谱
    const sources = res.data.data?.sources || []
    if (sources.length > 0) {
      searchResults.value = sources.map((s: any, i: number) => ({
        id: s.fileId || 'result-' + i,
        label: s.fileName,
        type: 'document',
      }))
    }
  } finally {
    searchLoading.value = false
  }
}

async function selectItem(item: GraphNode) {
  selectedItemId.value = item.id
  graphLoading.value = true
  try {
    const res = await graphApi.query(item.id)
    const data = res.data?.data
    if (!data) {
      console.error('事项图谱数据为空:', item.id)
      return
    }
    nodes.value = data.nodes || []
    edges.value = data.edges || []
    await nextTick()
    renderChart()
  } catch (err: any) {
    console.error('加载事项图谱失败:', err)
  } finally {
    graphLoading.value = false
  }
}

function renderChart() {
  if (!chartRef.value) return

  if (!chartInstance) {
    chartInstance = echarts.init(chartRef.value)
  }

  const typeColors: Record<string, string> = {
    department: '#409EFF',
    item: '#67C23A',
    document: '#E6A23C',
    opinion: '#F56C6C',
    user: '#909399',
  }

  const data = nodes.value.map(n => ({
    id: n.id,
    name: n.label,
    symbolSize: n.type === 'item' ? 40 : n.type === 'department' ? 50 : 28,
    itemStyle: { color: typeColors[n.type] || '#ccc' },
    category: n.type,
  }))

  const links = edges.value.map(e => ({
    source: e.source,
    target: e.target,
    label: { show: true, formatter: e.label, fontSize: 10 },
  }))

  const categories = Object.entries(typeColors).map(([name, color]) => ({
    name, itemStyle: { color },
  }))

  chartInstance.setOption({
    tooltip: {
      formatter: (params: any) => {
        if (params.dataType === 'node') {
          const node = nodes.value.find(n => n.id === params.data.id)
          if (node?.properties) {
            return `<b>${params.name}</b><br/>` +
              Object.entries(node.properties)
                .map(([k, v]) => `${k}: ${v}`).join('<br/>')
          }
          return `<b>${params.name}</b><br/>类型: ${params.data.category}`
        }
        return `${params.data.source} → ${params.data.target}: ${params.data.label?.formatter || ''}`
      },
    },
    legend: {
      data: categories.map(c => c.name),
      bottom: 10,
    },
    series: [{
      type: 'graph',
      layout: 'force',
      data,
      links,
      categories,
      roam: true,
      draggable: true,
      force: {
        repulsion: 300,
        edgeLength: [150, 300],
        gravity: 0.1,
      },
      label: {
        show: true,
        position: 'right',
        fontSize: 11,
        formatter: (p: any) => p.name.length > 12 ? p.name.substring(0, 12) + '...' : p.name,
      },
      lineStyle: {
        color: '#aaa',
        curveness: 0.2,
      },
      emphasis: {
        focus: 'adjacency',
        lineStyle: { width: 3 },
      },
    }],
  }, true)
}
</script>

<style scoped>
.graph-page { max-width: 1400px; margin: 0 auto; }
.search-panel { border-right: 1px solid #eee; padding-right: 8px; }
.graph-panel { padding: 0; }
.search-item {
  padding: 10px; border-bottom: 1px solid #f5f5f5; cursor: pointer;
  border-radius: 4px; transition: background 0.2s;
}
.search-item:hover { background: #f0f7ff; }
.search-item.active { background: #e6f4ff; border-left: 3px solid #409EFF; }
.item-title { font-size: 14px; font-weight: 500; }
.item-meta { margin-top: 4px; }
</style>
