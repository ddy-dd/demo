<!--
SearchDialog — 对话搜索对话框

点击聊天界面放大镜按钮后，在屏幕中央弹出。
支持搜索对话内容、高亮匹配、点击定位到对话组。
-->
<script setup lang="ts">
import { ref, computed, nextTick, watch } from 'vue'
import { chatStore } from '@/stores/chat'

const props = defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'navigate', uuid: string): void
}>()

const store = chatStore()
const searchQuery = ref('')
const searchInputRef = ref<HTMLInputElement | null>(null)

interface SearchMatch {
  uuid: string
  field: 'user' | 'ai'
  snippet: string
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')
}

const searchResults = computed(() => {
  const q = searchQuery.value.trim()
  if (!q) return []

  const results: SearchMatch[] = []
  const ctxLen = 30
  const lowerQ = q.toLowerCase()

  for (const conv of store.conversations) {
    // 搜索用户消息
    const userLower = conv.userMessage.toLowerCase()
    let pos = 0
    while (pos < conv.userMessage.length) {
      const idx = userLower.indexOf(lowerQ, pos)
      if (idx === -1) break
      const before = conv.userMessage.slice(Math.max(0, idx - ctxLen), idx)
      const match = conv.userMessage.slice(idx, idx + q.length)
      const after = conv.userMessage.slice(idx + q.length, idx + q.length + ctxLen)
      results.push({
        uuid: conv.uuid,
        field: 'user',
        snippet: `…${escapeHtml(before)}<mark>${escapeHtml(match)}</mark>${escapeHtml(after)}…`,
      })
      pos = idx + 1
    }

    // 搜索 AI 回复
    const respLower = conv.response.toLowerCase()
    pos = 0
    while (pos < conv.response.length) {
      const idx = respLower.indexOf(lowerQ, pos)
      if (idx === -1) break
      const before = conv.response.slice(Math.max(0, idx - ctxLen), idx)
      const match = conv.response.slice(idx, idx + q.length)
      const after = conv.response.slice(idx + q.length, idx + q.length + ctxLen)
      results.push({
        uuid: conv.uuid,
        field: 'ai',
        snippet: `…${escapeHtml(before)}<mark>${escapeHtml(match)}</mark>${escapeHtml(after)}…`,
      })
      pos = idx + 1
    }
  }

  return results
})

function close() {
  searchQuery.value = ''
  emit('close')
}

function onResultClick(uuid: string) {
  emit('navigate', uuid)
  close()
}

watch(() => props.visible, (val) => {
  if (val) {
    nextTick(() => searchInputRef.value?.focus())
  }
})
</script>

<template>
  <Teleport to="body">
    <transition name="s-dialog">
      <div v-if="visible" class="sd-overlay" @click.self="close">
        <div class="sd-modal">
          <!-- 头部：搜索输入 -->
          <div class="sd-header">
            <svg class="sd-search-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/></svg>
            <input
              ref="searchInputRef"
              v-model="searchQuery"
              placeholder="搜索对话内容…"
              class="sd-input"
              @keydown.escape="close"
            />
            <button class="sd-close-btn" @click="close" title="关闭搜索">✕</button>
          </div>

          <!-- 主体：搜索结果 -->
          <div class="sd-body">
            <template v-if="searchResults.length > 0">
              <div class="sd-stats">共找到 {{ searchResults.length }} 处匹配</div>
              <div
                v-for="(result, i) in searchResults"
                :key="`${result.uuid}-${i}`"
                class="sd-result-item"
                @click="onResultClick(result.uuid)"
              >
                <span class="sd-tag" :class="'sd-tag--' + result.field">
                  {{ result.field === 'user' ? '问' : '答' }}
                </span>
                <span class="sd-snippet" v-html="result.snippet"></span>
              </div>
            </template>
            <div v-else-if="searchQuery.trim()" class="sd-empty">
              未找到包含「{{ searchQuery }}」的内容
            </div>
            <div v-else class="sd-hint">
              输入关键词搜索对话中的消息
            </div>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped>
/* ===== 遮罩层 ===== */
.sd-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.30);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  backdrop-filter: blur(2px);
}

/* ===== 模态框 ===== */
.sd-modal {
  width: 560px;
  max-width: 90vw;
  max-height: 70vh;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* ===== 头部 ===== */
.sd-header {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.9rem 1rem 0.7rem;
  border-bottom: 1px solid #f0eeeb;
  flex-shrink: 0;
}

.sd-search-icon {
  flex-shrink: 0;
  color: #bbb8b2;
}

.sd-input {
  flex: 1;
  border: 1px solid #e5e3df;
  border-radius: 20px;
  padding: 0.5rem 0.9rem;
  font-size: 0.9rem;
  font-family: inherit;
  color: #2c2c2c;
  background: #fafaf9;
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.sd-input::placeholder {
  color: #c5c2bb;
}
.sd-input:focus {
  border-color: #b5b0a8;
  box-shadow: 0 0 0 3px rgba(181, 176, 168, 0.10);
  background: #fff;
}

.sd-close-btn {
  border: none;
  background: none;
  color: #bbb8b2;
  cursor: pointer;
  font-size: 1rem;
  padding: 4px 8px;
  border-radius: 6px;
  transition: background 0.15s, color 0.15s;
}
.sd-close-btn:hover {
  background: #f0eeeb;
  color: #5a5750;
}

/* ===== 主体 ===== */
.sd-body {
  flex: 1;
  overflow-y: auto;
  padding: 0.5rem 0.75rem 0.75rem;
}
.sd-body::-webkit-scrollbar {
  width: 4px;
}
.sd-body::-webkit-scrollbar-thumb {
  background: #e0ddd8;
  border-radius: 2px;
}

.sd-stats {
  font-size: 0.75rem;
  color: #999;
  padding: 0.3rem 0.5rem 0.5rem;
  border-bottom: 1px solid #f0eeeb;
  margin-bottom: 0.25rem;
}

.sd-hint {
  padding: 2.5rem 0.5rem;
  text-align: center;
  color: #bbb8b2;
  font-size: 0.85rem;
}

.sd-empty {
  padding: 2.5rem 0.5rem;
  text-align: center;
  color: #999;
  font-size: 0.85rem;
}

/* ===== 结果项 ===== */
.sd-result-item {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  padding: 0.45rem 0.6rem;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.12s;
}
.sd-result-item:hover {
  background: #f4f3f0;
}
.sd-result-item:active {
  background: #ecebe7;
}

.sd-tag {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 5px;
  font-size: 0.7rem;
  font-weight: 600;
  color: #fff;
  margin-top: 1px;
}
.sd-tag--user {
  background: #7ba5b5;
}
.sd-tag--ai {
  background: #a5b5a0;
}

.sd-snippet {
  font-size: 0.82rem;
  line-height: 1.55;
  color: #5a5750;
  word-break: break-all;
  flex: 1;
  min-width: 0;
}
.sd-snippet :deep(mark) {
  background: #fff3b0;
  color: #2c2c2c;
  border-radius: 2px;
  padding: 0 1px;
}

/* ===== 动画 ===== */
.s-dialog-enter-active {
  transition: opacity 0.2s ease;
}
.s-dialog-enter-active .sd-modal {
  transition: transform 0.22s cubic-bezier(0.18, 0.89, 0.32, 1.28), opacity 0.2s ease;
}
.s-dialog-leave-active {
  transition: opacity 0.15s ease;
}
.s-dialog-leave-active .sd-modal {
  transition: transform 0.15s ease, opacity 0.15s ease;
}
.s-dialog-enter-from {
  opacity: 0;
}
.s-dialog-enter-from .sd-modal {
  transform: scale(0.92);
  opacity: 0;
}
.s-dialog-leave-to {
  opacity: 0;
}
.s-dialog-leave-to .sd-modal {
  transform: scale(0.95);
  opacity: 0;
}
</style>
