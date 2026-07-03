import { defineStore } from "pinia";
import { ref, computed } from "vue";
import http from "@/api/http.ts";

/**
 * 后端 ChatMessageEntity 消息结构
 */
interface BackendMessage {
  id: string;
  conversationId: string;
  role: 'user' | 'assistant';
  content: string;
  thinking: string;
  createdAt: string;
}

/** 后端 ConversationEntity 结构 */
interface BackendConversation {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  messageCount: number;
}

/**
 * 单轮对话的数据结构
 */
export interface ConversationGroup {
  /** 唯一标识（使用 nanoid 生成） */
  uuid: string;
  /** 用户发送的消息原文 */
  userMessage: string;
  /** AI 的思考链内容（reasoning_content，可折叠） */
  thinking: string;
  /** AI 的文本回复（content） */
  response: string;
  /** 对话状态：
   *  - 'thinking':  等待 AI 思考中
   *  - 'responding': AI 正在流式输出回复
   *  - 'complete':   本轮对话已完成
   */
  status: 'thinking' | 'responding' | 'complete';
}

/**
 * 对话状态管理
 *
 * 管理所有对话轮次的状态，包括：
 * - 追加流式 thinking / response 片段
 * - 对话完成/打断标记
 * - 思考过程折叠/展开控制
 * - 从后端 SQLite 加载历史对话
 */
export const chatStore = defineStore('chat', () => {
  /** 所有对话轮次（按发送顺序排列） */
  const conversations = ref<ConversationGroup[]>([]);

  /** 当前正在处理的对话 UUID */
  const currentUuid = ref('');

  /** 当前加载的对话 ID（来自后端） */
  const currentConversationId = ref('');

  /** 已展开思考过程的会话 UUID 集合 */
  const expandedThinking = ref<Set<string>>(new Set());

  /** 当前对话的快捷引用 */
  const currentConversation = computed(() =>
    conversations.value.find(c => c.uuid === currentUuid.value)
  );

  /** 当前是否处于 AI 思考阶段 */
  const isThinking = computed(() =>
    currentConversation.value?.status === 'thinking'
  );

  /**
   * 从后端加载最新对话的消息
   */
  async function loadLatestConversation() {
    try {
      const convs: BackendConversation[] = await http.listConversations();
      if (!convs || convs.length === 0) return;

      // 加载所有对话的消息，按时间正序排列（最旧的在上，最新的在下）
      const allMessages: { msg: BackendMessage; index: number }[] = [];
      // 反转成从旧到新，这样加消息时最早的对话放前面
      const reversed = convs.slice().reverse();
      for (const conv of reversed) {
        const msgs: BackendMessage[] = await http.getConversationMessages(conv.id);
        msgs.forEach(m => allMessages.push({ msg: m, index: allMessages.length }));
      }

      if (allMessages.length === 0) return;

      // 按时间排序（后端返回的已经是正序了）
      const sorted = allMessages.map(m => m.msg);

      // 将消息成对解析为 ConversationGroup
      const groups: ConversationGroup[] = [];
      for (let i = 0; i < sorted.length; i++) {
        const msg = sorted[i];
        if (msg.role === 'user') {
          const nextMsg = i + 1 < sorted.length ? sorted[i + 1] : null;
          groups.push({
            uuid: msg.id,
            userMessage: msg.content,
            thinking: (nextMsg?.role === 'assistant') ? (nextMsg.thinking || '') : '',
            response: (nextMsg?.role === 'assistant') ? (nextMsg.content || '') : '',
            status: 'complete',
          });
        }
      }

      conversations.value = groups;
    } catch (e) {
      console.warn('加载历史对话失败:', e);
    }
  }

  /**
   * 开始一轮新对话
   * @param uuid 会话唯一标识
   * @param userMessage 用户消息
   */
  function startConversation(uuid: string, userMessage: string) {
    currentUuid.value = uuid;
    conversations.value.push({
      uuid,
      userMessage,
      thinking: '',
      response: '',
      status: 'thinking',
    });
    
  }

  /**
   * 追加思考链内容（流式）
   * 已完成的对话不再接收数据，避免打断后残留数据块破坏内容
   */
  function appendThinking(uuid: string, chunk: string) {
    const conv = conversations.value.find(c => c.uuid === uuid);
    if (conv && conv.status !== 'complete') {
      conv.thinking += chunk;
      conv.status = 'thinking';
    }
  }

  /**
   * 追加 AI 回复内容（流式）
   * 从思考阶段切换到回复阶段时，自动展开思考过程以供用户查看
   */
  function appendResponse(uuid: string, chunk: string) {
    const conv = conversations.value.find(c => c.uuid === uuid);
    if (conv && conv.status !== 'complete') {
      // 首次收到回复时，自动展开思考过程，让用户看到 AI "想"了什么
      if (conv.status === 'thinking') {
        expandedThinking.value.add(uuid);
      }
      conv.response += chunk;
      conv.status = 'responding';
    }
  }

  /**
   * 标记对话完成
   * @param uuid 指定会话 ID，未指定则标记当前会话
   */
  function completeConversation(uuid?: string) {
    const conv = uuid
      ? conversations.value.find(c => c.uuid === uuid)
      : currentConversation.value;
    if (conv && conv.status !== 'complete') {
      conv.status = 'complete';
    }
  }

  function setCurrentUuid(uuid: string) {
    currentUuid.value = uuid;
  }

  /** 切换思考过程的展开/折叠状态 */
  function toggleThinking(uuid: string) {
    const next = new Set(expandedThinking.value);
    if (next.has(uuid)) next.delete(uuid);
    else next.add(uuid);
    expandedThinking.value = next;
  }

  /** 清空所有对话记录 */
  function clearConversations() {
    conversations.value = [];
    currentUuid.value = '';
    currentConversationId.value = '';
    expandedThinking.value = new Set();
  }

  // 创建 store 时自动加载历史对话
  loadLatestConversation();

  return {
    conversations,
    currentUuid,
    currentConversationId,
    expandedThinking,
    currentConversation,
    isThinking,
    loadLatestConversation,
    startConversation,
    appendThinking,
    appendResponse,
    completeConversation,
    setCurrentUuid,
    toggleThinking,
    clearConversations,
  };
});
