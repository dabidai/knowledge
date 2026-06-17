<template>
  <div class="chat-page">
    <div class="chat-container">
      <!-- 消息列表 -->
      <div class="chat-messages" ref="messagesRef">
        <div v-if="messages.length === 0" class="chat-welcome">
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
            <!-- AI 回答用 Markdown 渲染 -->
            <div v-if="msg.role === 'assistant'" class="msg-text markdown-body"
                 v-html="renderMd(msg.content, msg.sources)"></div>
            <div v-else class="msg-text">{{ msg.content }}</div>

            <!-- 来源文档（仅 AI 回答附带） -->
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

            <div class="msg-time">{{ msg.time }}</div>
          </div>
        </div>

        <!-- 思考中动画 -->
        <div v-if="thinking" class="chat-message msg-ai">
          <div class="msg-avatar">
            <el-icon :size="24" color="#409EFF"><ChatDotRound /></el-icon>
          </div>
          <div class="msg-content">
            <div class="thinking-dots">
              <span></span><span></span><span></span>
            </div>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="chat-input">
        <el-input v-model="input" type="textarea" :rows="3"
                  placeholder="输入您的问题，支持自然语言提问..."
                  @keydown.enter.exact="handleSend"
                  :disabled="thinking"
                  resize="none">
        </el-input>
        <div class="input-actions">
          <el-button @click="clearChat" :disabled="thinking" size="small" text>
            清空对话
          </el-button>
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
import { ref, nextTick } from 'vue'
import { chatApi } from '@/api'
import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({ html: true, breaks: true, linkify: true })

interface Message {
  role: 'user' | 'assistant'
  content: string
  time: string
  sources?: any[]
}

const messages = ref<Message[]>([])
const input = ref('')
const thinking = ref(false)
const messagesRef = ref<HTMLElement | null>(null)

const quickQuestions = [
  '最近有哪些关于信息安全的通知？',
  '帮我查一下2024年的收文情况',
  '信息技术部有哪些重要的批复文件？',
]

/** 发送消息 */
async function handleSend() {
  const text = input.value.trim()
  if (!text || thinking.value) return

  const now = new Date().toLocaleTimeString()
  messages.value.push({ role: 'user', content: text, time: now })
  input.value = ''
  thinking.value = true
  await scrollBottom()

  try {
    // 构建对话历史（取最近 10 轮，过滤错误回复）
    const history = messages.value
      .filter(m => {
        if (m.role === 'assistant' && m.content.startsWith('抱歉')) return false
        return m.role === 'user' || m.role === 'assistant'
      })
      .slice(-20)
      .map(m => ({ role: m.role === 'assistant' ? 'assistant' : 'user', content: m.content }))

    const res = await chatApi.send({
      question: text,
      topK: 5,
      history: history.slice(0, -1), // 不包含当前问题
    })

    messages.value.push({
      role: 'assistant',
      content: res.data.data.answer || '抱歉，AI 服务暂时不可用。',
      time: new Date().toLocaleTimeString(),
      sources: res.data.data.sources || [],
    })
  } catch {
    messages.value.push({
      role: 'assistant',
      content: '抱歉，对话服务异常，请稍后重试。',
      time: new Date().toLocaleTimeString(),
    })
  } finally {
    thinking.value = false
    await scrollBottom()
  }
}

/** 快捷问题 */
function sendQuick(q: string) {
  input.value = q
  handleSend()
}

/** 清空对话 */
function clearChat() {
  messages.value = []
}

/** 自动滚动到底部 */
async function scrollBottom() {
  await nextTick()
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  }
}

/** Markdown 渲染 + 来源文件链接注入 */
function renderMd(text: string, sources?: any[]) {
  let html = md.render(text || '')
  if (sources && sources.length > 0) {
    for (const src of sources) {
      if (!src.fileName || !src.downloadUrl) continue
      const name = src.fileName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') // 转义正则特殊字符
      const regex = new RegExp(`(${name})`, 'gi')
      const link = `<a href="${src.downloadUrl}" target="_blank" title="下载: ${src.fileName}">$1</a>`
      html = html.replace(regex, link)
    }
  }
  return html
}
</script>

<style scoped>
.chat-page { max-width: 900px; margin: 0 auto; height: calc(100vh - 100px); }
.chat-container { display: flex; flex-direction: column; height: 100%; }
.chat-messages { flex: 1; overflow-y: auto; padding: 16px 0; scroll-behavior: smooth; }
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
.msg-content { max-width: 75%; }
.msg-user .msg-content { text-align: right; }
.msg-text { padding: 10px 16px; border-radius: 12px; line-height: 1.7;
  font-size: 14px; word-break: break-word; }
.msg-user .msg-text { background: #409EFF; color: #fff; }
.msg-ai .msg-text { background: #f5f7fa; }
.msg-time { font-size: 11px; color: #ccc; margin-top: 4px; }

.msg-sources { margin-top: 12px; text-align: left; background: #fafafa; border-radius: 8px; padding: 12px; }
.sources-header { font-size: 13px; font-weight: 600; color: #666; margin-bottom: 8px; }
.sources-list { max-height: 300px; overflow-y: auto; }
.source-item { padding: 8px 0; border-bottom: 1px solid #eee; }
.source-item:last-child { border-bottom: none; }
.source-name { font-weight: bold; font-size: 13px; }
.source-snippet { color: #999; font-size: 12px; margin: 2px 0; }
.source-meta { display: flex; align-items: center; gap: 8px; margin-top: 4px; }

.chat-input { border-top: 1px solid #eee; padding: 12px 0; }
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

/* Markdown 样式复用 */
.markdown-body :deep(h1), .markdown-body :deep(h2), .markdown-body :deep(h3) { margin: 8px 0 4px; }
.markdown-body :deep(p) { margin: 4px 0; }
.markdown-body :deep(code) { background: #eee; padding: 2px 4px; border-radius: 3px; font-size: 12px; }
.markdown-body :deep(pre) { background: #282c34; color: #abb2bf; padding: 12px; border-radius: 6px; overflow-x: auto; }
.markdown-body :deep(table) { border-collapse: collapse; margin: 8px 0; }
.markdown-body :deep(th), .markdown-body :deep(td) { padding: 6px 10px; border: 1px solid #ddd; font-size: 13px; }
.markdown-body :deep(a) { color: #409EFF; }
</style>
