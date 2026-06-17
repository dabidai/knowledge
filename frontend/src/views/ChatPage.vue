<template>
  <div class="chat-page">
    <!-- 左侧对话历史 -->
    <div class="chat-sidebar">
      <el-button type="primary" @click="newChat" style="width:100%;margin-bottom:12px">
        <el-icon><Plus /></el-icon> 新对话
      </el-button>
      <div class="conv-list" v-loading="convLoading">
        <div
          v-for="conv in conversations"
          :key="conv.id"
          class="conv-item"
          :class="{ active: conv.id === currentConvId }"
          @click="selectConv(conv.id)"
          @mouseenter="hoverConvId = conv.id"
          @mouseleave="hoverConvId = 0"
        >
          <span class="conv-title">{{ conv.title }}</span>
          <el-button v-show="hoverConvId === conv.id"
            circle size="small" text type="danger"
            @click.stop="deleteConv(conv.id)">
            <el-icon><Delete /></el-icon>
          </el-button>
        </div>
        <el-empty v-if="!convLoading && conversations.length === 0"
          description="暂无对话" :image-size="60" />
      </div>
    </div>

    <!-- 右侧对话区 -->
    <div class="chat-main">
      <div class="chat-messages" ref="messagesRef">
        <div v-if="messages.length === 0 && !thinking" class="chat-welcome">
          <el-icon :size="48" color="#409EFF"><ChatDotRound /></el-icon>
          <h2>知识库智能助手</h2>
          <p>我是您的公文知识库助手，可以帮您查询政策法规、事项信息、签阅记录等</p>
          <div class="quick-questions">
            <span class="quick-label">试试这些问题：</span>
            <el-tag v-for="q in quickQuestions" :key="q"
                    class="quick-tag" @click="sendQuick(q)">{{ q }}</el-tag>
          </div>
        </div>

        <div v-for="(msg, i) in messages" :key="i" class="chat-message"
             :class="msg.role === 'user' ? 'msg-user' : 'msg-ai'">
          <div class="msg-avatar">
            <el-icon v-if="msg.role === 'user'" :size="24"><UserFilled /></el-icon>
            <el-icon v-else :size="24" color="#409EFF"><ChatDotRound /></el-icon>
          </div>
          <div class="msg-content">
            <div v-if="msg.role === 'assistant'" class="msg-text markdown-body"
                 v-html="renderMd(msg.content, msg.sources)"></div>
            <div v-else class="msg-text">{{ msg.content }}</div>

            <!-- 来源文档 -->
            <div v-if="msg.sources && msg.sources.length > 0" class="msg-sources">
              <div class="sources-header">📄 参考来源 ({{ msg.sources.length }})</div>
              <div class="sources-list">
                <div v-for="src in msg.sources" :key="src.fileId" class="source-item">
                  <div class="source-name">{{ src.fileName }}</div>
                  <div class="source-snippet" v-html="src.snippet || '无预览'"></div>
                  <div class="source-meta">
                    <el-tag size="small">{{ src.deptName }}</el-tag>
                    <a :href="src.downloadUrl" target="_blank" v-if="src.downloadUrl">
                      <el-button size="small" type="primary">⬇ 下载原文</el-button>
                    </a>
                    <span v-else style="color:#ccc;font-size:12px">(文件暂不可下载)</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 思考中 -->
        <div v-if="thinking" class="chat-message msg-ai">
          <div class="msg-avatar">
            <el-icon :size="24" color="#409EFF"><ChatDotRound /></el-icon>
          </div>
          <div class="msg-content">
            <div class="thinking-dots"><span></span><span></span><span></span></div>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="chat-input">
        <el-input v-model="input" type="textarea" :rows="3"
                  placeholder="输入您的问题..."
                  @keydown.enter.exact="handleSend"
                  :disabled="thinking"
                  resize="none" />
        <div class="input-actions">
          <span></span>
          <el-button type="primary" @click="handleSend"
                     :loading="thinking" :disabled="!input.trim()">
            <el-icon><Promotion /></el-icon> 发送
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue'
import { chatApi, conversationApi } from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({ html: true, breaks: true, linkify: true })

interface Message {
  role: 'user' | 'assistant'
  content: string
  sources?: any[]
}

interface ConvItem {
  id: number
  title: string
  updatedAt: string
}

const conversations = ref<ConvItem[]>([])
const convLoading = ref(false)
const currentConvId = ref<number | null>(null)
const hoverConvId = ref(0)
const messages = ref<Message[]>([])
const input = ref('')
const thinking = ref(false)
const messagesRef = ref<HTMLElement | null>(null)

const quickQuestions = [
  '最近有哪些关于信息安全的通知？',
  '帮我查一下2024年的收文情况',
  '信息技术部有哪些重要的批复文件？',
]

onMounted(async () => {
  await loadConversations()
})

async function loadConversations() {
  convLoading.value = true
  try {
    const res = await conversationApi.list()
    conversations.value = res.data.data || []
  } catch { /* ignore */ }
  finally { convLoading.value = false }
}

async function selectConv(id: number) {
  currentConvId.value = id
  messages.value = []
  try {
    const res = await conversationApi.messages(id)
    const list: any[] = res.data.data || []
    messages.value = list.map((m: any) => ({
      role: m.role,
      content: m.content,
      sources: m.sources ? (() => { try { return JSON.parse(m.sources) } catch { return [] } })() : [],
    }))
    await scrollBottom()
  } catch {
    ElMessage.error('加载对话失败')
  }
}

async function newChat() {
  currentConvId.value = null
  messages.value = []
}

async function deleteConv(id: number) {
  try {
    await ElMessageBox.confirm('确定删除该对话？', '提示', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
    await conversationApi.delete(id)
    ElMessage.success('已删除')
    if (currentConvId.value === id) newChat()
    await loadConversations()
  } catch { /* 取消或失败 */ }
}

async function handleSend() {
  const text = input.value.trim()
  if (!text || thinking.value) return

  messages.value.push({ role: 'user', content: text })
  input.value = ''
  thinking.value = true
  await scrollBottom()

  try {
    const history = messages.value
      .filter(m => m.role === 'user' || m.role === 'assistant')
      .slice(-20)
      .map(m => ({ role: m.role === 'assistant' ? 'assistant' : 'user', content: m.content }))

    const res = await chatApi.send({
      question: text,
      topK: 5,
      history: history.slice(0, -1),
      conversationId: currentConvId.value || undefined,
    })

    const data = res.data.data
    if (data.conversationId && !currentConvId.value) {
      currentConvId.value = data.conversationId
      await loadConversations()
    }

    messages.value.push({
      role: 'assistant',
      content: data.answer || '抱歉，AI 服务暂时不可用。',
      sources: data.sources || [],
    })
  } catch {
    messages.value.push({
      role: 'assistant',
      content: '抱歉，对话服务异常，请稍后重试。',
    })
  } finally {
    thinking.value = false
    await scrollBottom()
  }
}

function sendQuick(q: string) {
  input.value = q
  handleSend()
}

async function scrollBottom() {
  await nextTick()
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  }
}

function renderMd(text: string, sources?: any[]) {
  let html = md.render(text || '')
  if (sources && sources.length > 0) {
    for (const src of sources) {
      if (!src.fileName || !src.downloadUrl) continue
      const name = src.fileName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const regex = new RegExp(`(${name})`, 'gi')
      const link = `<a href="${src.downloadUrl}" target="_blank" title="下载: ${src.fileName}">$1</a>`
      html = html.replace(regex, link)
    }
  }
  return html
}
</script>

<style scoped>
.chat-page { display: flex; height: calc(100vh - 100px); max-width: 1200px; margin: 0 auto; gap: 0; }

.chat-sidebar {
  width: 220px; flex-shrink: 0; border-right: 1px solid #eee;
  padding: 12px; display: flex; flex-direction: column; overflow: hidden;
}
.conv-list { flex: 1; overflow-y: auto; }
.conv-item {
  padding: 10px 8px; border-radius: 6px; cursor: pointer;
  display: flex; align-items: center; justify-content: space-between;
  font-size: 13px; margin-bottom: 2px; transition: background 0.15s;
}
.conv-item:hover { background: #f5f7fa; }
.conv-item.active { background: #e6f4ff; border-left: 3px solid #409EFF; }
.conv-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }

.chat-main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.chat-messages { flex: 1; overflow-y: auto; padding: 16px 20px; scroll-behavior: smooth; }

.chat-welcome { text-align: center; padding: 60px 20px; color: #666; }
.chat-welcome h2 { margin: 16px 0 8px; font-size: 22px; }
.chat-welcome p { margin-bottom: 20px; font-size: 14px; color: #999; }
.quick-questions { margin-top: 16px; }
.quick-label { font-size: 13px; color: #999; margin-right: 8px; }
.quick-tag { cursor: pointer; margin: 4px 6px; }
.quick-tag:hover { background: #409EFF; color: #fff; border-color: #409EFF; }

.chat-message { display: flex; gap: 12px; padding: 12px 0; }
.msg-user { flex-direction: row-reverse; }
.msg-avatar { flex-shrink: 0; width: 36px; height: 36px;
  border-radius: 50%; background: #f0f2f5; display: flex;
  align-items: center; justify-content: center; }
.msg-user .msg-avatar { background: #409EFF; color: #fff; }
.msg-content { max-width: 75%; min-width: 0; }
.msg-user .msg-content { text-align: right; }
.msg-text { padding: 10px 16px; border-radius: 12px; line-height: 1.7;
  font-size: 14px; word-break: break-word; display: inline-block; text-align: left; }
.msg-user .msg-text { background: #409EFF; color: #fff; }
.msg-ai .msg-text { background: #f5f7fa; max-width: 100%; }

.msg-sources { margin-top: 8px; text-align: left; background: #fafafa; border-radius: 8px; padding: 12px; }
.sources-header { font-size: 13px; font-weight: 600; color: #666; margin-bottom: 8px; }
.sources-list { max-height: 300px; overflow-y: auto; }
.source-item { padding: 8px 0; border-bottom: 1px solid #eee; }
.source-item:last-child { border-bottom: none; }
.source-name { font-weight: bold; font-size: 13px; }
.source-snippet { color: #999; font-size: 12px; margin: 2px 0; }
.source-meta { display: flex; align-items: center; gap: 8px; margin-top: 4px; }

.chat-input { border-top: 1px solid #eee; padding: 12px 20px; }
.input-actions { display: flex; justify-content: space-between; margin-top: 8px; }

.thinking-dots { display: flex; gap: 4px; padding: 10px 16px; }
.thinking-dots span { width: 8px; height: 8px; border-radius: 50%;
  background: #ccc; animation: bounce 1.4s infinite ease-in-out both; }
.thinking-dots span:nth-child(1) { animation-delay: -0.32s; }
.thinking-dots span:nth-child(2) { animation-delay: -0.16s; }

@keyframes bounce {
  0%, 80%, 100% { transform: scale(0); }
  40% { transform: scale(1); }
}

.markdown-body :deep(h1), .markdown-body :deep(h2), .markdown-body :deep(h3) { margin: 8px 0 4px; }
.markdown-body :deep(p) { margin: 4px 0; }
.markdown-body :deep(code) { background: #eee; padding: 2px 4px; border-radius: 3px; font-size: 12px; }
.markdown-body :deep(pre) { background: #282c34; color: #abb2bf; padding: 12px; border-radius: 6px; overflow-x: auto; }
.markdown-body :deep(table) { border-collapse: collapse; margin: 8px 0; }
.markdown-body :deep(th), .markdown-body :deep(td) { padding: 6px 10px; border: 1px solid #ddd; font-size: 13px; }
.markdown-body :deep(a) { color: #409EFF; }
</style>
