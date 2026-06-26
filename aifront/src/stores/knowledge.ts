import { defineStore } from "pinia";
import { ref } from "vue";

/**
 * 知识库文件记录
 */
export interface KnowledgeRecord {
  id: string;
  /** 文件名 */
  name: string;
  /** 文件大小（字节），可能拿不到 */
  size?: number;
  /** 上传时间 */
  uploadTime: string;
  /** 状态 */
  status: 'success' | 'error';
}

/**
 * 知识库文件状态管理
 *
 * 记录用户上传到知识库的所有文件，方便用户查看和管理。
 */
export const useKnowledgeStore = defineStore('knowledge', () => {
  /** 已上传的文件列表 */
  const files = ref<KnowledgeRecord[]>([]);

  /**
   * 添加一条上传记录
   */
  function addRecord(record: KnowledgeRecord) {
    files.value.unshift(record);
  }

  /**
   * 删除一条上传记录
   */
  function removeRecord(id: string) {
    const idx = files.value.findIndex(f => f.id === id);
    if (idx !== -1) files.value.splice(idx, 1);
  }

  /** 清空所有记录 */
  function clearRecords() {
    files.value = [];
  }

  return {
    files,
    addRecord,
    removeRecord,
    clearRecords,
  };
});
