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
          <el-empty description="请从左侧目录树选择文档查看">
            <template #image>
              <el-icon :size="80" color="#ccc"><Document /></el-icon>
            </template>
          </el-empty>
        </el-card>

        <el-card v-else shadow="never" style="height:100%">
          <template #header>
            <div style="display:flex;justify-content:space-between;align-items:center">
              <strong>📄 {{ selectedDoc.fileName }}</strong>
              <div style="display:flex;gap:8px;align-items:center">
                <el-tag v-if="selectedDoc.status !== 'matched'" type="warning" size="small">
                  {{ selectedDoc.status === 'expected' ? '待匹配' : selectedDoc.status === 'orphan' ? '未关联' : selectedDoc.status }}
                </el-tag>
                <el-button size="small" text @click="copyContent">
                  <el-icon><CopyDocument /></el-icon> 复制
                </el-button>
              </div>
            </div>
          </template>

          <!-- 加载中 -->
          <div v-if="docLoading" style="display:flex;align-items:center;justify-content:center;height:100%">
            <el-icon class="is-loading" :size="40"><Loading /></el-icon>
          </div>

          <!-- Markdown 内容 -->
          <div v-else-if="docContent"
               class="markdown-body"
               v-html="renderedMarkdown"
               style="height:calc(100% - 60px);overflow-y:auto;padding:16px 24px">
          </div>

          <!-- 空内容 -->
          <div v-else style="display:flex;flex-direction:column;align-items:center;justify-content:center;height:100%;color:#999">
            <el-icon :size="48"><Warning /></el-icon>
            <p style="margin-top:12px">该文档尚未解析生成 Markdown 内容</p>
            <p style="font-size:13px;color:#bbb">文档状态: {{ selectedDoc.status }}，导入后系统会自动解析</p>
          </div>
        </el-card>
      </el-main>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { browseApi } from '@/api'
import { ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'

/** 初始化 Markdown 解析器（开启表格、链接等扩展） */
const md = new MarkdownIt({
  html: true,        // 允许 HTML 标签
  breaks: true,      // 换行转 <br>
  linkify: true,     // 自动识别链接
  typographer: true, // 智能引号
})

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

/** 为树节点生成唯一 ID */
let nodeIdCounter = 0
function assignIds(nodes: TreeNode[]): TreeNode[] {
  return nodes.map(n => ({
    ...n,
    id: n.type + ':' + (nodeIdCounter++),
    children: n.children ? assignIds(n.children) : undefined,
  }))
}

/** 加载文档目录树 */
async function loadTree() {
  treeLoading.value = true
  try {
    nodeIdCounter = 0
    const res = await browseApi.tree()
    tree.value = assignIds(res.data.data || [])
  } catch {
    ElMessage.error('加载文档目录失败')
  } finally {
    treeLoading.value = false
  }
}

/** 点击树节点，加载文档内容 */
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
      ElMessage.error('加载文档内容失败')
    } finally {
      docLoading.value = false
    }
  }
}

/** 使用 markdown-it 渲染 Markdown 为 HTML */
const renderedMarkdown = computed(() => {
  if (!docContent.value) return ''
  return md.render(docContent.value)
})

/** 复制文档内容 */
async function copyContent() {
  try {
    await navigator.clipboard.writeText(docContent.value)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选择文本')
  }
}

loadTree()
</script>

<style scoped>
.browse-page { max-width: 1400px; margin: 0 auto; }
.tree-panel { border-right: 1px solid #eee; padding-right: 8px; }
.content-panel { padding: 0 0 0 16px; }
.tree-node { display: flex; align-items: center; font-size: 14px; }

/* markdown-it 渲染样式 */
.markdown-body :deep(h1) {
  font-size: 24px; border-bottom: 2px solid #409EFF; padding-bottom: 10px; margin: 24px 0 16px;
}
.markdown-body :deep(h2) {
  font-size: 20px; border-bottom: 1px solid #e0e0e0; padding-bottom: 8px; margin: 20px 0 12px;
}
.markdown-body :deep(h3) { font-size: 17px; margin: 16px 0 10px; color: #333; }
.markdown-body :deep(h4) { font-size: 15px; margin: 12px 0 8px; color: #555; }
.markdown-body :deep(p) { line-height: 1.9; margin: 8px 0; }
.markdown-body :deep(ul), .markdown-body :deep(ol) { padding-left: 24px; margin: 8px 0; }
.markdown-body :deep(li) { line-height: 1.8; }
.markdown-body :deep(blockquote) {
  border-left: 4px solid #409EFF; padding: 8px 16px; margin: 12px 0;
  background: #f5f7fa; color: #666;
}
.markdown-body :deep(code) {
  background: #f0f2f5; padding: 2px 6px; border-radius: 3px;
  font-family: 'Courier New', monospace; font-size: 13px;
}
.markdown-body :deep(pre) {
  background: #282c34; color: #abb2bf; padding: 16px; border-radius: 6px;
  overflow-x: auto; margin: 12px 0;
}
.markdown-body :deep(pre code) { background: none; padding: 0; color: inherit; }
.markdown-body :deep(table) {
  border-collapse: collapse; width: 100%; margin: 12px 0;
}
.markdown-body :deep(th) {
  background: #f5f7fa; font-weight: 600; padding: 10px 14px;
  border: 1px solid #dcdfe6; text-align: left;
}
.markdown-body :deep(td) {
  padding: 8px 14px; border: 1px solid #dcdfe6;
}
.markdown-body :deep(tr:nth-child(even)) { background: #fafafa; }
.markdown-body :deep(a) { color: #409EFF; text-decoration: none; }
.markdown-body :deep(a:hover) { text-decoration: underline; }
.markdown-body :deep(img) { max-width: 100%; }
.markdown-body :deep(hr) { border: none; border-top: 1px solid #eee; margin: 20px 0; }

/* 过渡动画 */
.markdown-body :deep(*) { transition: none; }
</style>
