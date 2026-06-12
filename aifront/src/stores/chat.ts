import {defineStore} from "pinia";
import {ref} from "vue";

export interface ChatMessage {
  text: string;
  isUser: boolean;
  thinking?: string;
}

export const chatStore = defineStore('chat', () => {
  const communications = ref<ChatMessage[]>([])
  const currentUuid = ref('')   // 当前对话消息的 uuid，send 和 stop 共用

  function addMessage(msg: ChatMessage) {
    communications.value.push(msg)
  }

  function setCurrentUuid(uuid: string) {
    currentUuid.value = uuid
  }

  function clearCommunications() {
    communications.value = []
    currentUuid.value = ''
  }

  return {
    communications,
    currentUuid,
    addMessage,
    setCurrentUuid,
    clearCommunications,
  }
})
