/**
 * useRecorder — 浏览器录音 + ASR 语音转文字
 *
 * 基于 MediaRecorder API 录制浏览器麦克风音频，
 * 上传到后端 ASR 服务转写为文本。
 */
import { ref } from 'vue'
import http from '@/api/http'

export function useRecorder() {
  const isRecording = ref(false)
  const isProcessing = ref(false)
  const error = ref<string | null>(null)

  let mediaRecorder: MediaRecorder | null = null
  let chunks: Blob[] = []

  /** 支持的音频 MIME 类型（优先 webm） */
  const AUDIO_MIME = (() => {
    const types = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus']
    for (const t of types) {
      if (MediaRecorder.isTypeSupported(t)) return t
    }
    return 'audio/webm'
  })()

  /**
   * 开始录音
   */
  async function startRecording(): Promise<void> {
    error.value = null
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      chunks = []

      mediaRecorder = new MediaRecorder(stream, { mimeType: AUDIO_MIME })
      mediaRecorder.ondataavailable = (e) => {
        if (e.data.size > 0) chunks.push(e.data)
      }
      mediaRecorder.start()
      isRecording.value = true
    } catch (e: any) {
      const msg = e.name === 'NotAllowedError'
        ? '麦克风权限被拒绝'
        : e.name === 'NotFoundError'
          ? '未检测到麦克风'
          : `启动录音失败: ${e.message || e}`
      error.value = msg
      throw e
    }
  }

  /**
   * 停止录音并上传转写
   *
   * @returns 转写后的文本
   */
  function stopRecording(): Promise<string> {
    return new Promise((resolve, reject) => {
      if (!mediaRecorder || mediaRecorder.state === 'inactive') {
        const msg = '未在录音中'
        error.value = msg
        reject(new Error(msg))
        return
      }

      mediaRecorder.onstop = async () => {
        // 释放麦克风
        mediaRecorder!.stream.getTracks().forEach(t => t.stop())
        isRecording.value = false

        const blob = new Blob(chunks, { type: AUDIO_MIME })
        if (blob.size === 0) {
          const msg = '未录制到音频'
          error.value = msg
          reject(new Error(msg))
          return
        }

        isProcessing.value = true
        error.value = null
        try {
          const form = new FormData()
          form.append('file', blob, 'recording.webm')

          const res: any = await http.asrTranscribe(form)
          resolve(res.text || '')
        } catch (e: any) {
          const msg = e?.response?.data?.error || e.message || '语音转写失败'
          error.value = msg
          reject(new Error(msg))
        } finally {
          isProcessing.value = false
        }
      }

      mediaRecorder.stop()
    })
  }

  /**
   * 取消录音（放弃本次录音结果）
   */
  function cancelRecording() {
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
      mediaRecorder.stream.getTracks().forEach(t => t.stop())
      mediaRecorder.stop()
    }
    isRecording.value = false
    isProcessing.value = false
    chunks = []
  }

  return {
    isRecording,
    isProcessing,
    error,
    startRecording,
    stopRecording,
    cancelRecording,
  }
}
