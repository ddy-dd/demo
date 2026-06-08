<script setup lang="ts">
import WebsocketClient from "@/api/websocket.ts";
import type {WsMessage} from "@/type/request.ts";
//import { UploadFilled } from '@element-plus/icons-vue'
import {ElMessage} from "element-plus";
import http from "@/api/http.ts";
import {ref, nextTick, onUnmounted} from "vue";
import {marked} from "marked";
import katex from 'katex';
import 'katex/dist/katex.min.css';
import { nanoid } from 'nanoid';

let wsClient: WebsocketClient | null
const message = ref('')
const communications = ref<{text: string; isUser: boolean}[]>([])
const messagesContainer = ref<HTMLElement | null>(null)
const isConnected = ref(false)

// 查询等待提示
const showWaiting = ref(false)
let waitingTimer: ReturnType<typeof setTimeout> | null = null

function startWaitingTimer() {
  if (waitingTimer) clearTimeout(waitingTimer)
  showWaiting.value = false
  waitingTimer = setTimeout(() => {
    showWaiting.value = true
  }, 500)
}

function clearWaiting() {
  showWaiting.value = false
  if (waitingTimer) {
    clearTimeout(waitingTimer)
    waitingTimer = null
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  })
}

const initWebSocketListener = () => {
  return new Promise((resolve) => {
    const chatId = nanoid()
    wsClient = WebsocketClient.getInstance(chatId)
    const ws = wsClient!.getWsConnection()
    ws.onopen = () => {
      console.log('连接成功')
      isConnected.value = true
      wsClient!.interval = setInterval(() => {
        wsClient!.ping()
      }, 20000)
      resolve(true)
    }
    ws.onmessage = (event) => {
      const msg: WsMessage = JSON.parse(event.data)
      console.log('收到消息', msg)
      switch (msg.type){
        case 'pong':
          console.log('websocket 连接正常------------')
          break
        case 'stop':
          if (wsClient && wsClient.interval) {
            clearInterval(wsClient.interval)
            wsClient.interval = null
          }
          ws.close()
          isConnected.value = false
          break
        case 'text': {
          // 流式文本：如果上一条也是 AI 消息则追加，否则新建一条
          const msgs = communications.value
          const lastMsg = msgs.length > 0 ? msgs[msgs.length - 1] : null
          if (lastMsg && !lastMsg.isUser) {
            lastMsg.text += msg.data
            communications.value = [...communications.value]
          } else {
            communications.value.push({text: msg.data, isUser: false})
          }
          clearWaiting()
          scrollToBottom()
          break
        }
        case 'error':
          ElMessage.error(msg.data)
          clearWaiting()
          break
        default:
          break
      }
    }
  })
}

const send = async () => {
  if(!message.value.trim()) return
  if(!wsClient){
    await initWebSocketListener();
  }
  if (wsClient) {
    communications.value.push({text: message.value, isUser: true})
    scrollToBottom()
    wsClient.send({
      type: 'text',
      data: message.value,
    })
    message.value = ''
    startWaitingTimer()
  }
}

const upload = async (options: any) => {
  const formData = new FormData()
  formData.append('file', options.file)
  try{
    const res = await http.uploadAndVectorize(formData)
    ElMessage.success('上传成功')
    console.log(res)
  }catch (e) {
    ElMessage.error('上传失败')
  }
}
const renderMarkdown = (text: string): string => {
  // 1) 用占位符替换所有数学公式，防止被 marked 破坏
  const placeholders: string[] = []
  let idx = 0

  // 块级公式 $$...$$ 优先
  text = text.replace(/\$\$([\s\S]*?)\$\$/g, (_, math: string) => {
    const key = `\x00MATH${idx}\x00`
    placeholders[idx] = katex.renderToString(math.trim(), {
      displayMode: true,
      throwOnError: false,
    })
    idx++
    return key
  })

  // 行内公式 $...$
  text = text.replace(/\$([^\$]+)\$/g, (_, math: string) => {
    const key = `\x00MATH${idx}\x00`
    placeholders[idx] = katex.renderToString(math.trim(), {
      displayMode: false,
      throwOnError: false,
    })
    idx++
    return key
  })

  // 2) 渲染 markdown
  let html = marked.parse(text, { async: false }) as string

  // 3) 还原数学公式
  placeholders.forEach((katexHtml, i) => {
    html = html.replace(`\x00MATH${i}\x00`, katexHtml)
  })

  return html
}

const handleClose = () => {
  wsClient?.close()
  isConnected.value = false
}

onUnmounted(() => {
  if (waitingTimer) clearTimeout(waitingTimer)
})
</script>

<template>
  <div class="chat-page">
    <div class="chat-card">
      <header class="chat-header">
        <h1 class="chat-title">对话</h1>
        <span class="status-dot" :class="{ connected: isConnected }"></span>
      </header>

      <div class="messages-area" ref="messagesContainer">
        <div v-if="communications.length === 0" class="messages-empty">
          <p>开始一段新的对话</p>
        </div>
        <div
          v-for="(msg, idx) in communications"
          :key="idx"
          class="message-bubble"
          :class="msg.isUser ? 'message-bubble--user' : 'message-bubble--bot'"
          v-html="renderMarkdown(msg.text)"
        >
        </div>
        <!-- 等待提示：AI 正在思考时显示 -->
        <div v-if="showWaiting" class="message-bubble message-bubble--bot thinking-bubble">
          <div class="thinking-spinner"></div>
          <span>正在思考…</span>
        </div>
      </div>

      <div class="input-area">
        <div class="input-row">
          <input
            type="text"
            v-model="message"
            placeholder="输入消息…"
            class="chat-input"
            @keyup.enter="send"
          />
          <button class="btn btn-send" @click="send" :disabled="!message.trim()">
            发送
          </button>
        </div>
        <div class="action-row">
          <button class="btn btn-outline" @click="handleClose" :disabled="!isConnected">断开</button>
          <button class="btn btn-outline" @click="initWebSocketListener">重连</button>
          <label class="upload-label">
            <input type="file" hidden @change="(e: any) => upload({ file: e.target.files[0] })" />
            <span class="upload-trigger">上传文件</span>
          </label>
        </div>
      </div>
    </div>
  </div>
</template>

<style>
.chat-page {
  height: 100vh;
  display: flex;
  background: #f7f6f3;
  font-family: "SF Pro Text", "Hiragino Sans", "Noto Sans SC", -apple-system, sans-serif;
  position: relative;
}

.chat-card {
  width: 100%;
  height: 100vh;
  background: #fff;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.875rem 1.5rem;
  border-bottom: 1px solid #f0eeeb;
  flex-shrink: 0;
}

.chat-title {
  font-size: 1rem;
  font-weight: 500;
  letter-spacing: 0.06em;
  color: #2c2c2c;
  margin: 0;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #d0ceca;
  transition: background 0.3s;
}
.status-dot.connected {
  background: #7ba587;
  box-shadow: 0 0 0 3px rgba(123,165,135,0.15);
}

.messages-area {
  flex: 1;
  overflow-y: auto;
  padding: 1.25rem 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  scroll-behavior: smooth;
}

.messages-area::-webkit-scrollbar {
  width: 4px;
}
.messages-area::-webkit-scrollbar-thumb {
  background: #e0ddd8;
  border-radius: 2px;
}

.messages-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
.messages-empty p {
  color: #bbb8b2;
  font-size: 0.85rem;
  letter-spacing: 0.04em;
}

.message-bubble {
  background: #f4f3f0;
  color: #2c2c2c;
  padding: 0.6rem 1rem;
  border-radius: 12px 12px 12px 4px;
  font-size: 0.9rem;
  line-height: 1.6;
  max-width: 85%;
  width: fit-content;
  word-break: break-word;
  text-align: left;
  animation: msg-in 0.25s ease-out;
}
.message-bubble--user {
  align-self: flex-end;
  background: #d6e4f0;
  border-radius: 12px 12px 4px 12px;
}
.message-bubble--bot {
  align-self: flex-start;
}
.message-bubble p {
  margin: 0 0 0.4em;
  text-align: left;
}
.message-bubble p:last-child {
  margin-bottom: 0;
}
.message-bubble pre {
  background: #e8e7e3;
  border-radius: 6px;
  padding: 0.5rem 0.75rem;
  overflow-x: auto;
  font-size: 0.85rem;
  margin: 0.3em 0;
}
.message-bubble code {
  background: #e8e7e3;
  border-radius: 3px;
  padding: 0 0.3em;
  font-size: 0.85em;
}
.message-bubble pre code {
  background: none;
  padding: 0;
}
.message-bubble ul, .message-bubble ol {
  margin: 0.3em 0;
  padding-left: 1.25em;
}
.message-bubble a {
  color: #2c6b9e;
  text-decoration: underline;
}

@keyframes msg-in {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

.input-area {
  padding: 1rem 1.5rem 1.5rem;
  border-top: 1px solid #f0eeeb;
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
  flex-shrink: 0;
}

.input-row {
  display: flex;
  gap: 0.5rem;
}

.chat-input {
  flex: 1;
  border: 1px solid #e5e3df;
  border-radius: 12px;
  padding: 0.625rem 1rem;
  font-size: 0.9rem;
  font-family: inherit;
  color: #2c2c2c;
  background: #fafaf9;
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.chat-input::placeholder {
  color: #c5c2bb;
}
.chat-input:focus {
  border-color: #b5b0a8;
  box-shadow: 0 0 0 3px rgba(181,176,168,0.12);
  background: #fff;
}

.btn {
  border: none;
  border-radius: 12px;
  padding: 0.625rem 1.25rem;
  font-size: 0.85rem;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.2s, opacity 0.2s;
  white-space: nowrap;
}

.btn-send {
  background: #2c2c2c;
  color: #fff;
  font-weight: 450;
  letter-spacing: 0.03em;
}
.btn-send:hover:not(:disabled) {
  background: #444;
}
.btn-send:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.action-row {
  display: flex;
  gap: 0.5rem;
}

.btn-outline {
  background: transparent;
  color: #7d7a74;
  border: 1px solid #e5e3df;
  padding: 0.45rem 1rem;
  font-size: 0.8rem;
  border-radius: 10px;
}
.btn-outline:hover:not(:disabled) {
  background: #f4f3f0;
  color: #2c2c2c;
}
.btn-outline:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.upload-label {
  cursor: pointer;
  margin-left: auto;
}
.upload-trigger {
  display: inline-block;
  padding: 0.45rem 1rem;
  font-size: 0.8rem;
  color: #7d7a74;
  border: 1px dashed #d5d2cc;
  border-radius: 10px;
  transition: background 0.2s, border-color 0.2s;
}
.upload-trigger:hover {
  background: #f4f3f0;
  border-color: #b5b0a8;
  color: #2c2c2c;
}

.deerflow-badge {
  position: fixed;
  bottom: 1rem;
  right: 1.25rem;
  font-size: 0.7rem;
  color: #c8c4bd;
  text-decoration: none;
  letter-spacing: 0.04em;
  transition: color 0.2s;
}
.deerflow-badge:hover {
  color: #7d7a74;
}

/* 等待提示（与原有气泡风格一致） */
.thinking-bubble {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.thinking-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid #d5d2cc;
  border-top-color: #7d7a74;
  border-radius: 50%;
  animation: thinking-spin 0.8s linear infinite;
  flex-shrink: 0;
}
@keyframes thinking-spin {
  to { transform: rotate(360deg); }
}
</style>
