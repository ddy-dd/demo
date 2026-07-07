<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { v4 as uuidv4 } from 'uuid'
import { useRouter } from 'vue-router'
import { renderMarkdown } from '@/composables/useMarkdown'
import http from '@/api/http'

const router = useRouter()
const WS_BASE = 'ws://localhost:8888/api/calling/'

const callId = ref('')
const ws = ref<WebSocket | null>(null)
const isCalling = ref(false)
const isSpeaking = ref(false)
const messages = ref<{ role: string; text: string }[]>([])

let mediaRecorder: MediaRecorder | null = null
let audioCtx: AudioContext | null = null
let analyser: AnalyserNode | null = null
let vadTimer: ReturnType<typeof setInterval> | null = null
let silenceMs = 0
let speaking = false
// 整个通话生命周期只保留一个音频流，参照 big-model-five 项目的做法
let micStream: MediaStream | null = null

// ── WebSocket ────────────────────────────────────────────

function connect(): Promise<void> {
  return new Promise((resolve, reject) => {
    const id = uuidv4()
    callId.value = id
    const socket = new WebSocket(`${WS_BASE}${id}`)

    socket.onopen = () => {
      ws.value = socket; resolve()
    }
    socket.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data)
        switch (msg.type) {
          case 'asr_final':
            messages.value.push({ role: 'user', text: msg.data?.text || '' })
            break
          case 'agent_response':
            const last = messages.value[messages.value.length - 1]
            if (last?.role === 'assistant') last.text += msg.data?.text || ''
            else messages.value.push({ role: 'assistant', text: msg.data?.text || '' })
            break
          case 'error':
            ElMessage.error(msg.data?.message || '通话异常')
            break
        }
      } catch { /* binary */ }
    }
    socket.onclose = () => { isCalling.value = false }
    socket.onerror = () => { ws.value = null; reject(new Error('连接失败')) }
  })
}

// ── VAD + 录音 ──────────────────────────────────────────

function startVad(stream: MediaStream, ctx: AudioContext) {
  audioCtx = ctx
  const source = audioCtx.createMediaStreamSource(stream)
  analyser = audioCtx.createAnalyser()
  analyser.fftSize = 512               // 更大的 FFT → 更稳定的 RMS
  source.connect(analyser)

  const data = new Uint8Array(analyser.frequencyBinCount)
  // 平滑用的环形缓冲区，存最近 3 帧（300ms）的 RMS
  const rmsHistory: number[] = []
  const HISTORY_LEN = 3

  // 语音起止用不同阈值（滞后），防止在边界来回抖动
  const THRESHOLD_ON  = 4              // RMS > 18 判定为有人声
  const THRESHOLD_OFF = 2              // RMS < 12 判定为静音

  vadTimer = setInterval(async () => {
    // AudioContext 可能被浏览器挂起（切 tab 等），自动恢复
    if (audioCtx?.state === 'suspended') {
      await audioCtx.resume()
    }

    analyser!.getByteTimeDomainData(data)
    const rms = Math.sqrt(data.reduce((s, v) => s + (v - 128) ** 2, 0) / data.length)

    // 平滑：取最近 3 帧的均值，避免单帧瞬态误判
    rmsHistory.push(rms)
    if (rmsHistory.length > HISTORY_LEN) rmsHistory.shift()
    const smoothRms = rmsHistory.reduce((a, b) => a + b, 0) / rmsHistory.length

    if (speaking) {
      // ── 说话中 ──────────────────────────
      if (smoothRms < THRESHOLD_OFF) {
        silenceMs += 100
        if (silenceMs > 300) {
          // 持续沉默超过 1 秒 → 这句话说完了
          speaking = false
          isSpeaking.value = false
          stopUtterance()
        }
      } else {
        silenceMs = 0                      // 又检测到声音，重置沉默计时
      }
    } else {
      // ── 静音中 ──────────────────────────
      if (smoothRms > THRESHOLD_ON) {
        silenceMs = 0
        speaking = true
        isSpeaking.value = true
        startUtterance()
      }
    }
  }, 100)
}

function startUtterance() {
  if (!micStream) {
    console.warn('micStream 不存在，无法录音')
    return
  }
  const chunks: Blob[] = []
  // 使用同一个 micStream，不重新调用 getUserMedia
  mediaRecorder = new MediaRecorder(micStream, { mimeType: 'audio/webm' })
  mediaRecorder.ondataavailable = (e) => { if (e.data.size > 0) chunks.push(e.data) }
  mediaRecorder.onstop = () => {
    // 不再停止 micStream 的轨道 —— 整个通话共用同一个流
    if (chunks.length === 0) return
    const blob = new Blob(chunks, { type: 'audio/webm' })
    blob.arrayBuffer().then(buf => {
      ws.value?.send(buf)
      ws.value?.send(JSON.stringify({ type: 'audio_end', callId: callId.value }))
    })
  }
  mediaRecorder.start()
}

function stopUtterance() {
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    mediaRecorder.stop()
    mediaRecorder = null
  }
}

// ── 通话控制 ────────────────────────────────────────────

async function toggleCall() {
  if (isCalling.value) {
    // 结束通话
    isCalling.value = false
    stopUtterance()
    cleanupVad()
    if (micStream) {
      micStream.getTracks().forEach(t => t.stop())
      micStream = null
    }
    return
  }

  // AudioContext 必须在任何 await 之前创建（用户手势上下文中），
  // 否则 Chrome 会以 suspended 状态启动，getByteTimeDomainData 永远返回静音
  audioCtx = new AudioContext()

  // 开始通话
  try {
    if (!ws.value || ws.value.readyState !== WebSocket.OPEN) await connect()
    isCalling.value = true
    isSpeaking.value = false
    silenceMs = 0
    speaking = false

    // 只调用一次 getUserMedia，整个通话生命周期共用
    micStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    startVad(micStream, audioCtx)
  } catch {
    ElMessage.error('连接失败')
  }
}

function cleanupVad() {
  if (vadTimer) { clearInterval(vadTimer); vadTimer = null }
  if (audioCtx) { audioCtx.close(); audioCtx = null }
  analyser = null
  speaking = false
  isSpeaking.value = false
}

function hangup() {
  isCalling.value = false
  stopUtterance()
  cleanupVad()
  // 通话结束时才释放麦克风流
  if (micStream) {
    micStream.getTracks().forEach(t => t.stop())
    micStream = null
  }
  ws.value?.close()
  ws.value = null
}

function goToChat() { hangup(); router.push('/chat') }

/** 挂载时加载最近通话的历史消息 */
async function loadHistory() {
  try {
    const latest: any = await http.getCallHistoryLatest()
    const msgs: any[] = latest?.messages
    if (msgs && msgs.length > 0) {
      messages.value = msgs.map((m: any) => ({
        role: m.role === 'assistant' ? 'assistant' : 'user',
        text: m.content || '',
      }))
    }
  } catch (e) {
    console.warn('加载通话历史失败（后端可能未重启）:', e)
  }
}

onMounted(() => { loadHistory() })

onUnmounted(() => { hangup() })
</script>

<template>
  <div class="calling-page">
    <header class="calling-header">
      <button class="btn-back" @click="goToChat">← 返回对话</button>
      <h1>语音通话</h1>
      <span class="status-badge" :class="{ active: isCalling }">
        {{ isCalling ? (isSpeaking ? '讲话中' : '通话中') : '已就绪' }}
      </span>
    </header>

    <div class="call-indicator" v-if="isCalling">
      <span class="vad-dot" :class="{ speaking: isSpeaking }"></span>
      <span>{{ isSpeaking ? '正在听你说话...' : '等待说话...' }}</span>
    </div>

    <div class="messages-area">
      <div v-if="messages.length === 0" class="empty-hint">
        <p>点击下方按钮开始语音对话</p>
      </div>
      <div v-for="(msg, i) in messages" :key="i" class="msg-item" :class="'msg--' + msg.role">
        <span class="msg-label">{{ msg.role === 'user' ? '你' : 'AI' }}</span>
        <span class="msg-text" v-html="renderMarkdown(msg.text)"></span>
      </div>
    </div>

    <div class="bottom-bar">
      <button class="call-btn" :class="{ active: isCalling }" @click="toggleCall">
        <span class="call-btn-icon">{{ isCalling ? '⏹' : '🎤' }}</span>
        <span>{{ isCalling ? '结束通话' : '开始通话' }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.calling-page { height: 100vh; display: flex; flex-direction: column; background: #f7f6f3; font-family: "PingFang SC","Noto Sans SC",sans-serif; }
.calling-header { display: flex; align-items: center; gap: 1rem; padding: 0.875rem 1.5rem; background: #fff; border-bottom: 1px solid #f0eeeb; flex-shrink: 0; }
.calling-header h1 { font-size: 1rem; font-weight: 500; margin: 0; flex: 1; }
.btn-back { border: 1px solid #e5e3df; border-radius: 8px; padding: 0.35rem 0.75rem; font-size: 0.8rem; background: #fff; color: #5a5750; cursor: pointer; }
.btn-back:hover { background: #f4f3f0; }
.status-badge { font-size: 0.72rem; padding: 0.2rem 0.6rem; border-radius: 10px; background: #e0ddd8; color: #8a8680; }
.status-badge.active { background: #e8f5e9; color: #2e7d32; }
.call-indicator { display: flex; align-items: center; justify-content: center; gap: 0.5rem; padding: 0.5rem; color: #8a8680; font-size: 0.8rem; flex-shrink: 0; }
.vad-dot { width: 8px; height: 8px; border-radius: 50%; background: #bbb8b2; transition: all 0.15s; }
.vad-dot.speaking { background: #4caf50; box-shadow: 0 0 6px rgba(76,175,80,0.5); }
.messages-area { flex: 1; overflow-y: auto; padding: 1.25rem 1.5rem; display: flex; flex-direction: column; gap: 0.5rem; }
.empty-hint { flex: 1; display: flex; align-items: center; justify-content: center; color: #bbb8b2; font-size: 0.85rem; }
.msg-item { display: flex; gap: 0.5rem; padding: 0.5rem 0.75rem; background: #fff; border-radius: 10px; max-width: 80%; }
.msg--user { align-self: flex-end; background: #d6e4f0; }
.msg--assistant { align-self: flex-start; background: #fff; }
.msg-label { font-size: 0.7rem; font-weight: 600; color: #8a8680; flex-shrink: 0; margin-top: 0.1rem; }
.msg-text { font-size: 0.9rem; line-height: 1.5; color: #2c2c2c; word-break: break-word; text-align: left; }

/* ── 底部通话按钮 ── */
.bottom-bar { display: flex; justify-content: center; align-items: center; padding: 1rem 1.5rem; background: #fff; border-top: 1px solid #f0eeeb; flex-shrink: 0; }
.call-btn { display: flex; align-items: center; gap: 0.5rem; padding: 0.85rem 2.5rem; border: none; border-radius: 40px; font-size: 1rem; font-family: inherit; background: #2c2c2c; color: #fff; cursor: pointer; transition: all 0.2s; }
.call-btn:hover { background: #444; }
.call-btn.active { background: #e05050; }
.call-btn-icon { font-size: 1.2rem; }
</style>
