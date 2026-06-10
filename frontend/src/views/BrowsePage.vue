<template>
  <div class="browse-page">
    <el-container style="height: calc(100vh - 140px)">
      <!-- 左侧目录树 -->
      <el-aside width="320px" class="tree-panel">
        <el-card shadow="never" style="height:100%">
          <template #header>
            <div style="display:flex;justify-content:space-between;align-items:center">
              <strong>📁 文档目录</strong>
              <el-button @click="loadTree" :loading="treeLoading" size="small" text>
                <el-icon><Refresh /></el-icon>
              </el-button>
            </div>
          </template>

          <div v-loading="treeLoading" style="height:calc(100% - 60px);overflow-y:auto">
            <el-empty v-if="!treeLoading && tree.length === 0" description="暂无文档数据" />

            <el-tree
              :data="tree"
              :props="{ children: 'children', label: 'label' }"
              node-key="id"
              highlight-current
              @node-click="handleNodeClick"
              :default-expand-all="false"
              accordion
            >
              <template #default="{ node, data }">
                <span class="tree-node">
                  <el-icon v-if="data.type === 'department'" color="#409EFF"><Folder /></el-icon>
                  <el-icon v-else-if="data.type === 'category'" color="#E6A23C"><FolderOpened /></el-icon>
                  <el-icon v-else-if="data.type === 'item'" color="#67C23A"><Tickets /></el-icon>
                  <el-icon v-else-if="data.type === 'document'" color="#909399"><Document /></el-icon>
                  <span style="margin-left:6px">{{ node.label }}</span>
                  <el-tag v-if="data.type === 'document' && data.doc?.status !== 'matched'"
                          size="small" type="warning" style="margin-left:8px">
                    {{ data.doc?.status }}
                  </el-tag>
                </span>
              </template>
            </el-tree>
          </div>
        </el-card>
      </el-aside>

      <!-- 右侧文档内容 -->
      <el-main class="content-panel">
        <el-card v-if="!selectedDoc" shadow="never" style="height:100%">
          <el-empty description="请从左侧目录树选择文档查看" />
        </el-card>

        <el-card v-else shadow="never" style="height:100%">
          <template #header>
            <div style="display:flex;justify-content:space-between;align-items:center">
              <strong>📄 {{ selectedDoc.fileName }}</strong>
              <el-tag v-if="selectedDoc.status !== 'matched'" type="warning" size="small">
                {{ selectedDoc.status }}
              </el-tag>
            </div>
          </template>

          <div v-loading="docLoading" class="markdown-body" v-html="renderedMarkdown"
               style="height:calc(100% - 60px);overflow-y:auto;padding:16px">
          </div>

          <div v-if="!docLoading && !docContent" style="text-align:center;padding:40px;color:#999">
            该文档尚未解析生成 Markdown
          </div>
        </el-card>
      </el-main>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { browseApi } from '@/api'

interface DocRef {
  fileId: string
  fileName: string
  status: string
  minioMdPath: string
}

interface TreeNode {
  id: string
  label: string
  type: 'department' | 'category' | 'item' | 'document'
  doc?: DocRef
  item?: any
  children?: TreeNode[]
}

const tree = ref<TreeNode[]>([])
const treeLoading = ref(false)
const docLoading = ref(false)
const selectedDoc = ref<DocRef | null>(null)
const docContent = ref('')

// 为树节点生成唯一 ID
let nodeIdCounter = 0
function assignIds(nodes: TreeNode[]): TreeNode[] {
  return nodes.map(n => ({
    ...n,
    id: n.type + ':' + (nodeIdCounter++),
    children: n.children ? assignIds(n.children) : undefined,
  }))
}

async function loadTree() {
  treeLoading.value = true
  try {
    nodeIdCounter = 0
    const res = await browseApi.tree()
    tree.value = assignIds(res.data.data || [])
  } finally {
    treeLoading.value = false
  }
}

async function handleNodeClick(data: TreeNode) {
  if (data.type === 'document' && data.doc) {
    selectedDoc.value = data.doc
    docContent.value = ''
    docLoading.value = true
    try {
      const res = await browseApi.doc(data.doc.fileId)
      docContent.value = res.data.data?.content || ''
    } catch {
      docContent.value = ''
    } finally {
      docLoading.value = false
    }
  }
}

// 简单 Markdown → HTML 转换
import { computed } from 'vue'
const renderedMarkdown = computed(() => {
  if (!docContent.value) return ''
  return docContent.value
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    // 标题
    .replace(/^### (.+)$/gm, '<h3>$1</h3>')
    .replace(/^## (.+)$/gm, '<h2>$1</h2>')
    .replace(/^# (.+)$/gm, '<h1>$1</h1>')
    // 表格
    .replace(/^\|(.+)\|$/gm, (match) => {
      const cells = match.split('|').filter(c => c.trim())
      if (cells.every(c => /^[-: ]+$/.test(c.trim()))) return ''
      const tag = match.includes('---') ? '' :
        '<tr>' + cells.map(c => `<td>${c.trim()}</td>`).join('') + '</tr>'
      return tag
    })
    // 换行
    .replace(/\n\n/g, '<br/><br/>')
    .replace(/\n/g, '<br/>')
})

loadTree()
</script>

<style scoped>
.browse-page { max-width: 1400px; margin: 0 auto; }
.tree-panel { border-right: 1px solid #eee; padding-right: 8px; }
.content-panel { padding: 0 0 0 16px; }
.tree-node { display: flex; align-items: center; font-size: 14px; }
.markdown-body h1 { font-size: 22px; border-bottom: 2px solid #eee; padding-bottom: 8px; }
.markdown-body h2 { font-size: 18px; border-bottom: 1px solid #eee; padding-bottom: 6px; }
.markdown-body h3 { font-size: 16px; }
.markdown-body td { padding: 4px 12px; border: 1px solid #ddd; }
.markdown-body tr:nth-child(even) { background: #f9f9f9; }
</style>
