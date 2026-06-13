import {defineStore} from "pinia";
import {ref, computed} from "vue";

export interface ConversationGroup {
  uuid: string;
  userMessage: string;
  thinking: string;
  response: string;
  status: 'thinking' | 'responding' | 'complete';
}

export const chatStore = defineStore('chat', () => {
  const conversations = ref<ConversationGroup[]>([])
  const currentUuid = ref('')
  const expandedThinking = ref<Set<string>>(new Set())

  const currentConversation = computed(() =>
      conversations.value.find(c => c.uuid === currentUuid.value)
  )

  const isThinking = computed(() =>
      currentConversation.value?.status === 'thinking'
  )

  function startConversation(uuid: string, userMessage: string) {
    currentUuid.value = uuid
    conversations.value.push({
      uuid,
      userMessage,
      thinking: '',
      response: '',
      status: 'thinking',
    })
  }

  function appendThinking(uuid: string, chunk: string) {
    const conv = conversations.value.find(c => c.uuid === uuid)
    // 已完成的对话不再接收数据块，避免打断后残留块撕开内容
    if (conv && conv.status !== 'complete') {
      conv.thinking += chunk
      conv.status = 'thinking'
    }
  }

  function appendResponse(uuid: string, chunk: string) {
    const conv = conversations.value.find(c => c.uuid === uuid)
    if (conv && conv.status !== 'complete') {
      // 从 thinking → responding 时，自动展开思考过程
      if (conv.status === 'thinking') {
        expandedThinking.value.add(uuid)
      }
      conv.response += chunk
      conv.status = 'responding'
    }
  }

  function completeConversation(uuid?: string) {
    const conv = uuid
        ? conversations.value.find(c => c.uuid === uuid)
        : currentConversation.value
    if (conv && conv.status !== 'complete') {
      conv.status = 'complete'
    }
  }

  function setCurrentUuid(uuid: string) {
    currentUuid.value = uuid
  }

  function toggleThinking(uuid: string) {
    const next = new Set(expandedThinking.value)
    if (next.has(uuid)) next.delete(uuid)
    else next.add(uuid)
    expandedThinking.value = next
  }

  function clearConversations() {
    conversations.value = []
    currentUuid.value = ''
    expandedThinking.value = new Set()
  }

  return {
    conversations,
    currentUuid,
    expandedThinking,
    currentConversation,
    isThinking,
    startConversation,
    appendThinking,
    appendResponse,
    completeConversation,
    setCurrentUuid,
    toggleThinking,
    clearConversations,
  }
})
