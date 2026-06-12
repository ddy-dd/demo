<script setup lang="ts">
import WebsocketClient from "@/api/websocket.ts";
import type {WsMessage} from "@/type/request.ts";
import {ElMessage} from "element-plus";
import http from "@/api/http.ts";
import {ref, nextTick, onUnmounted} from "vue";
import {marked} from "marked";
import katex from 'katex';
import 'katex/dist/katex.min.css';
import { nanoid } from 'nanoid';
import type {userLocation} from "@/type/userLocation.ts";
import {locationStore} from "@/stores/loaction.ts"
import {chatStore} from "@/stores/chat.ts"
import { v4 as uuidv4 } from 'uuid';

let wsClient: WebsocketClient | null
const message = ref('')

const userLocationStore = locationStore()
const useChatStore = chatStore()

const communications = useChatStore.communications
const messagesContainer = ref<HTMLElement | null>(null)
const isConnected = ref(false)
const isSending = ref(false)            // 是否正在等待 AI 回复，控制停止按钮显示

// === Thinking 模式状态 ===
const isThinking = ref(false)             // 是否正在输出思考过程
const streamingThinking = ref('')         // 流式积累的思考内容
const expandedThinking = ref<Set<number>>(new Set())  // 哪些消息的思考展开

function toggleThinking(idx: number) {
  const next = new Set(expandedThinking.value)
  if (next.has(idx)) next.delete(idx)
  else next.add(idx)
  expandedThinking.value = next
}

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
    const uuid = uuidv4();
    wsClient = WebsocketClient.getInstance(uuid)
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
          isSending.value = false
          if (wsClient && wsClient.interval) {
            clearInterval(wsClient.interval)
            wsClient.interval = null
          }
          ws.close()
          isConnected.value = false
          break
        case 'over':
          isSending.value = false
          break
        case 'thinking': {
          // 流式思考内容，在 text 到达前持续积累
          isThinking.value = true
          streamingThinking.value += msg.data || ''
          clearWaiting()
          scrollToBottom()
          break
        }
        case 'text': {
          const lastMsg = communications.length > 0 ? communications[communications.length - 1] : null

          if (isThinking.value) {
            // 思考结束，第一条 text：将 thinking 合并到新消息
            communications.push({
              text: msg.data,
              isUser: false,
              thinking: streamingThinking.value,
            })
            isThinking.value = false
            streamingThinking.value = ''
          } else if (lastMsg && !lastMsg.isUser) {
            // 继续流式输出 text
            lastMsg.text += msg.data
          } else {
            // 没有 thinking，直接新建 AI 消息
            communications.push({text: msg.data, isUser: false})
          }

          clearWaiting()
          scrollToBottom()
          break
        }
        case 'error':
          ElMessage.error(msg.data)
          clearWaiting()
          isThinking.value = false
          break
        case 'tools':
          switch (msg.data){
            case 'location':
              getLocation()
              break
            default:
              break;
          }
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
    const uuid = nanoid()
    useChatStore.setCurrentUuid(uuid)

    communications.push({text: message.value, isUser: true})
    scrollToBottom()
    wsClient.send({
      type: 'text',
      data: message.value,
      uuid: uuid,
    })
    message.value = ''
    isSending.value = true
    startWaitingTimer()
  }
}

const stopChat = () => {
  if (wsClient) {
    wsClient.send({
      type: 'stop',
      data: '',
      uuid: useChatStore.currentUuid,
    })
    isSending.value = false
  }
}

function getLocation() {
  // 1. 检查浏览器是否支持 Geolocation API
  if(userLocationStore.hasLocation){
    wsClient!.send({
      type: 'tools',
      data: userLocationStore.getLocation(),
    })
    return
  }
  if (navigator.geolocation) {
    navigator.geolocation.getCurrentPosition(
        // 2. 成功回调函数
        function(position) {
          let latitude = position.coords.latitude;   // 纬度
          let longitude = position.coords.longitude; // 经度
          const ul: userLocation = {
            latitude,
            longitude,
          }
          console.log(`当前位置: 纬度 ${latitude}, 经度 ${longitude}`);
          userLocationStore.setLocation(ul)
          wsClient!.send({
            type: 'tools',
            data: ul,
          })
        },
        // 3. 失败回调函数（处理错误）
        function(error) {
          switch(error.code) {
            case error.PERMISSION_DENIED:
              console.log("用户拒绝了位置请求");
              wsClient!.send({
                type: 'tools',
                data: "用户拒绝了位置请求",
              })
              break;
            case error.POSITION_UNAVAILABLE:
              console.log("无法获取位置信息");
              wsClient!.send({
                type: 'tools',
                data: "无法获取位置信息",
              })
              break;
            case error.TIMEOUT:
              console.log("获取位置超时");
              wsClient!.send({
                type: 'tools',
                data: "获取位置超时",
              })
              break;
            default:
              console.log("发生未知错误");
          }
        },
        // 4. 可选配置参数
        {
          enableHighAccuracy: true,  // 要求高精度
          timeout: 20000,             // 超时时间（毫秒）
          maximumAge: 0              // 不使用缓存，强制获取最新位置
        }
    );
  } else {
    alert("您的浏览器不支持地理定位功能");
    wsClient!.send({
      type: 'tools',
      data: "您的浏览器不支持地理定位功能",
    })
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
  isThinking.value = false
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

        <template v-for="(msg, idx) in communications" :key="idx">
          <!-- ── 用户消息 ── -->
          <div
            v-if="msg.isUser"
            class="message-bubble message-bubble--user"
            v-html="renderMarkdown(msg.text)"
          >
          </div>

          <!-- ── AI 回复（含可选的 thinking 区块）── -->
          <div v-else class="bot-message-group">
            <!-- Thinking 区块：折叠/展开 -->
            <div v-if="msg.thinking" class="thinking-section">
              <button class="thinking-toggle" @click="toggleThinking(idx)">
                <span class="thinking-label">
                  {{ expandedThinking.has(idx) ? '收起思考过程' : '查看思考过程' }}
                </span>
                <svg
                  class="thinking-chevron"
                  :class="{ rotated: expandedThinking.has(idx) }"
                  width="12" height="12" viewBox="0 0 12 12"
                >
                  <path d="M4 8L8 4" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linecap="round"/>
                </svg>
              </button>
              <div class="thinking-content" :class="{ expanded: expandedThinking.has(idx) }">
                <div class="thinking-content-inner">
                  <div class="thinking-text">{{ msg.thinking }}</div>
                </div>
              </div>
            </div>

            <!-- 正式回复气泡 -->
            <div class="message-bubble message-bubble--bot" v-html="renderMarkdown(msg.text)"></div>
          </div>
        </template>

        <!-- ── 流式 Thinking（text 到达前实时展示） ── -->
        <div v-if="isThinking" class="bot-message-group">
          <div class="thinking-section thinking-section--streaming">
            <div class="thinking-toggle">
              <span class="thinking-label">AI 正在思考</span>
              <span class="thinking-dots"><span></span><span></span><span></span></span>
            </div>
            <div class="thinking-content expanded">
              <div class="thinking-content-inner">
                <div class="thinking-text">
                  {{ streamingThinking }}<span class="thinking-cursor"></span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 等待提示（退路：没有 thinking 流时显示） -->
        <div v-if="showWaiting && !isThinking" class="message-bubble message-bubble--bot thinking-bubble">
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
          <button v-if="!isSending" class="btn btn-send" @click="send" :disabled="!message.trim()">
            发送
          </button>
          <button v-else class="btn btn-stop" @click="stopChat">
            <span class="stop-icon">■</span> 停止
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
/* ===== Layout ===== */
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

/* ===== Messages Area ===== */
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

/* ===== Bot message group（thinking + reply 分离） ===== */
.bot-message-group {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  align-self: flex-start;
  max-width: 85%;
}

/* ===== Message Bubbles ===== */
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

/* ===== Thinking 区块 ===== */
.thinking-section {
  position: relative;
  background: #fafaf9;
  border-radius: 0 8px 8px 0;
  padding-left: 1rem;   /* 与气泡文字起始位置对齐 */
  font-size: 0.8rem;
  color: #8a8680;
  transition: background 0.2s;
}
.thinking-section::before {
  content: '';
  position: absolute;
  left: 0;
  top: 4px;
  bottom: 4px;
  width: 3px;
  background: #ddd9d3;
  border-radius: 2px;
}

/* 流式思考中 - 略微强调 */
.thinking-section--streaming {
  background: #f8f7f5;
}
.thinking-section--streaming::before {
  background: #b3aa9e;
}

/* ---- 可点击的切换头部 ---- */
.thinking-toggle {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.35rem 0;
  border: none;
  background: none;
  cursor: pointer;
  color: #8a8680;
  font-family: inherit;
  font-size: 0.8rem;
  user-select: none;
  transition: color 0.2s;
  width: 100%;
  text-align: left;
}
.thinking-toggle:hover {
  color: #2c2c2c;
}
.thinking-section--streaming .thinking-toggle {
  cursor: default;
}

.thinking-label {
  font-weight: 450;
}

/* ---- 右箭头 ---- */
.thinking-chevron {
  margin-left: auto;
  transition: transform 0.25s ease;
  flex-shrink: 0;
}
.thinking-chevron.rotated {
  transform: rotate(-180deg);
}

/* ---- 折叠/展开内容（max-height 过渡） ---- */
.thinking-content {
  max-height: 0;
  opacity: 0;
  overflow: hidden;
  transition: max-height 0.32s cubic-bezier(0.4, 0, 0.2, 1),
              opacity 0.25s ease,
              padding 0.25s ease;
}
.thinking-content.expanded {
  max-height: 1000px;
  opacity: 1;
}

.thinking-content-inner {
  padding-bottom: 0.5rem;
  padding-right: 0.5rem;
}

.thinking-text {
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  text-align: left;
}

/* ---- 闪烁光标 ---- */
.thinking-cursor {
  display: inline-block;
  width: 2px;
  height: 1.1em;
  background: #8a8680;
  margin-left: 1px;
  vertical-align: text-bottom;
  animation: cursor-blink 0.8s step-end infinite;
}
@keyframes cursor-blink {
  50% { opacity: 0; }
}

/* ---- AI 思考动画小点 ---- */
.thinking-dots {
  display: inline-flex;
  gap: 2px;
  align-items: center;
}
.thinking-dots span {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #b5b0a8;
  animation: dot-bounce 1.4s infinite both;
}
.thinking-dots span:nth-child(2) { animation-delay: 0.2s; }
.thinking-dots span:nth-child(3) { animation-delay: 0.4s; }

@keyframes dot-bounce {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

/* ===== Input Area ===== */
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

.btn-stop {
  background: #e05050;
  color: #fff;
  font-weight: 450;
  letter-spacing: 0.03em;
}
.btn-stop:hover {
  background: #c43a3a;
}
.stop-icon {
  font-size: 0.65rem;
  display: inline-block;
  vertical-align: middle;
  margin-right: 0.1rem;
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
