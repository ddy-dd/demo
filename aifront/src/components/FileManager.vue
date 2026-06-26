<script setup lang="ts">
/**
 * FileManager — 文件管理面板
 *
 * 以抽屉形式展示用户已上传的知识库文件和 Skill，
 * 支持按分类切换查看和单条删除。
 */
import { ref } from "vue";
import { useKnowledgeStore } from "@/stores/knowledge.ts";
import { useSkillStore } from "@/stores/skill.ts";

defineProps<{
  visible: boolean;
}>();

const emit = defineEmits<{
  (e: "close"): void;
}>();

const knowledgeStore = useKnowledgeStore();
const skillStore = useSkillStore();

type TabKey = "knowledge" | "skill";
const activeTab = ref<TabKey>("knowledge");

function switchTab(tab: TabKey) {
  activeTab.value = tab;
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function formatSize(bytes?: number): string {
  if (!bytes) return "--";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
</script>

<template>
  <Transition name="drawer">
    <div v-if="visible" class="fm-overlay" @click.self="emit('close')">
      <aside class="fm-panel">
        <!-- 头部 -->
        <header class="fm-header">
          <h2 class="fm-title">文件管理</h2>
          <button class="fm-close-btn" @click="emit('close')" title="关闭">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <path d="M18 6L6 18M6 6l12 12"/>
            </svg>
          </button>
        </header>

        <!-- Tab 切换 -->
        <div class="fm-tabs">
          <button
            class="fm-tab"
            :class="{ active: activeTab === 'knowledge' }"
            @click="switchTab('knowledge')"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
              <polyline points="14 2 14 8 20 8"/>
              <line x1="16" y1="13" x2="8" y2="13"/>
              <line x1="16" y1="17" x2="8" y2="17"/>
            </svg>
            知识库
            <span v-if="knowledgeStore.files.length" class="fm-badge">{{ knowledgeStore.files.length }}</span>
          </button>
          <button
            class="fm-tab"
            :class="{ active: activeTab === 'skill' }"
            @click="switchTab('skill')"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <polygon points="12 2 22 8.5 22 15.5 12 22 2 15.5 2 8.5 12 2"/>
              <line x1="12" y1="22" x2="12" y2="15.5"/>
              <polyline points="22 8.5 12 15.5 2 8.5"/>
            </svg>
            Skills
            <span v-if="skillStore.skills.length" class="fm-badge">{{ skillStore.skills.length }}</span>
          </button>
        </div>

        <!-- 知识库 Tab 内容 -->
        <div v-show="activeTab === 'knowledge'" class="fm-list">
          <div v-if="knowledgeStore.files.length === 0" class="fm-empty">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#d0ceca" stroke-width="1.2" stroke-linecap="round">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
              <polyline points="14 2 14 8 20 8"/>
            </svg>
            <p>暂无上传的知识库文件</p>
          </div>
          <div
            v-for="file in knowledgeStore.files"
            :key="file.id"
            class="fm-item"
            :class="{ 'fm-item--error': file.status === 'error' }"
          >
            <div class="fm-item-icon fm-item-icon--file">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                <polyline points="14 2 14 8 20 8"/>
              </svg>
            </div>
            <div class="fm-item-body">
              <span class="fm-item-name">{{ file.name }}</span>
              <span class="fm-item-meta">
                {{ formatTime(file.uploadTime) }}
                <template v-if="file.size"> · {{ formatSize(file.size) }}</template>
                <span v-if="file.status === 'error'" class="fm-item-status fm-item-status--err">上传失败</span>
              </span>
            </div>
            <button class="fm-item-del" @click="knowledgeStore.removeRecord(file.id)" title="删除记录">✕</button>
          </div>
        </div>

        <!-- Skill Tab 内容 -->
        <div v-show="activeTab === 'skill'" class="fm-list">
          <div v-if="skillStore.skills.length === 0" class="fm-empty">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#d0ceca" stroke-width="1.2" stroke-linecap="round">
              <polygon points="12 2 22 8.5 22 15.5 12 22 2 15.5 2 8.5 12 2"/>
            </svg>
            <p>暂无上传的 Skill</p>
          </div>
          <div
            v-for="skill in skillStore.skills"
            :key="skill.id"
            class="fm-item"
            :class="{ 'fm-item--error': skill.status === 'error' }"
          >
            <div class="fm-item-icon fm-item-icon--skill">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
                <polygon points="12 2 22 8.5 22 15.5 12 22 2 15.5 2 8.5 12 2"/>
                <line x1="12" y1="22" x2="12" y2="15.5"/>
                <polyline points="22 8.5 12 15.5 2 8.5"/>
              </svg>
            </div>
            <div class="fm-item-body">
              <span class="fm-item-name">{{ skill.name }}</span>
              <span class="fm-item-meta">
                {{ skill.packageName }}
                <template v-if="skill.packageName"> · {{ formatTime(skill.uploadTime) }}</template>
                <span v-if="skill.status === 'error'" class="fm-item-status fm-item-status--err">上传失败</span>
              </span>
            </div>
            <button class="fm-item-del" @click="skillStore.removeRecord(skill.id)" title="删除记录">✕</button>
          </div>
        </div>
      </aside>
    </div>
  </Transition>
</template>

<style scoped>
/* ===== 遮罩 + 抽屉 ===== */
.fm-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.18);
  z-index: 200;
  display: flex;
  justify-content: flex-end;
  backdrop-filter: blur(1px);
}

.fm-panel {
  width: 340px;
  max-width: 85vw;
  height: 100vh;
  background: #fff;
  display: flex;
  flex-direction: column;
  box-shadow: -4px 0 20px rgba(0, 0, 0, 0.08);
}

/* ===== 入场 / 出场过渡 ===== */
.drawer-enter-active,
.drawer-leave-active {
  transition: opacity 0.2s ease;
}
.drawer-enter-active .fm-panel,
.drawer-leave-active .fm-panel {
  transition: transform 0.25s cubic-bezier(0.4, 0, 0.2, 1);
}
.drawer-enter-from {
  opacity: 0;
}
.drawer-enter-from .fm-panel {
  transform: translateX(100%);
}
.drawer-leave-to {
  opacity: 0;
}
.drawer-leave-to .fm-panel {
  transform: translateX(100%);
}

/* ===== 头部 ===== */
.fm-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem 1.25rem;
  border-bottom: 1px solid #f0eeeb;
  flex-shrink: 0;
}
.fm-title {
  font-size: 1rem;
  font-weight: 500;
  color: #2c2c2c;
  margin: 0;
}
.fm-close-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: #8a8680;
  cursor: pointer;
  border-radius: 6px;
  transition: background 0.15s;
}
.fm-close-btn:hover {
  background: #f4f3f0;
  color: #2c2c2c;
}

/* ===== Tab 切换 ===== */
.fm-tabs {
  display: flex;
  gap: 0;
  border-bottom: 1px solid #f0eeeb;
  flex-shrink: 0;
}
.fm-tab {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.35rem;
  padding: 0.6rem 0.5rem;
  border: none;
  background: transparent;
  font-size: 0.82rem;
  font-family: inherit;
  color: #8a8680;
  cursor: pointer;
  position: relative;
  transition: color 0.15s;
}
.fm-tab:hover {
  color: #5a5750;
}
.fm-tab.active {
  color: #2c2c2c;
  font-weight: 500;
}
.fm-tab.active::after {
  content: "";
  position: absolute;
  bottom: 0;
  left: 1.5rem;
  right: 1.5rem;
  height: 2px;
  background: #2c2c2c;
  border-radius: 1px;
}

.fm-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  font-size: 0.65rem;
  font-weight: 500;
  background: #e8e7e3;
  color: #5a5750;
  border-radius: 8px;
  line-height: 1;
}
.fm-tab.active .fm-badge {
  background: #2c2c2c;
  color: #fff;
}

/* ===== 列表区域 ===== */
.fm-list {
  flex: 1;
  overflow-y: auto;
  padding: 0.5rem 0;
}
.fm-list::-webkit-scrollbar {
  width: 3px;
}
.fm-list::-webkit-scrollbar-thumb {
  background: #e0ddd8;
  border-radius: 2px;
}

/* ===== 空状态 ===== */
.fm-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 3rem 1rem;
  gap: 0.75rem;
}
.fm-empty p {
  margin: 0;
  color: #bbb8b2;
  font-size: 0.82rem;
}

/* ===== 列表项 ===== */
.fm-item {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.55rem 1.25rem;
  transition: background 0.12s;
}
.fm-item:hover {
  background: #fafaf9;
}
.fm-item--error {
  opacity: 0.6;
}

.fm-item-icon {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  flex-shrink: 0;
}
.fm-item-icon--file {
  background: #eef4fa;
  color: #4a7ba8;
}
.fm-item-icon--skill {
  background: #f0eef5;
  color: #7b6fa0;
}

.fm-item-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
}
.fm-item-name {
  font-size: 0.84rem;
  color: #2c2c2c;
  font-weight: 450;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.fm-item-meta {
  font-size: 0.72rem;
  color: #a5a19a;
}
.fm-item-status--err {
  display: inline-block;
  margin-left: 0.3rem;
  color: #c62828;
  font-weight: 450;
}

.fm-item-del {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: #c8c4bd;
  cursor: pointer;
  border-radius: 4px;
  font-size: 0.7rem;
  transition: color 0.12s, background 0.12s;
  flex-shrink: 0;
}
.fm-item-del:hover {
  color: #c62828;
  background: #fce4ec;
}
</style>
