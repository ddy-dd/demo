import { defineStore } from "pinia";
import { ref } from "vue";
import http from "@/api/http.ts";

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
 * 后端 KnowledgeFileEntity 结构
 */
interface BackendKnowledgeFile {
  id: string;
  name: string;
  size: number;
  status: string;
  createdAt: string;
}

/**
 * 知识库文件状态管理
 *
 * 记录用户上传到知识库的所有文件，数据从后端 SQLite 获取。
 */
export const useKnowledgeStore = defineStore('knowledge', () => {
  /** 已上传的文件列表 */
  const files = ref<KnowledgeRecord[]>([]);

  /**
   * 从后端加载知识库文件上传记录
   */
  async function loadRecords() {
    try {
      const records: BackendKnowledgeFile[] = await http.listKnowledgeFiles();
      files.value = records.map(r => ({
        id: r.id,
        name: r.name,
        size: r.size,
        uploadTime: r.createdAt,
        status: r.status as 'success' | 'error',
      }));
    } catch (e) {
      console.warn('加载知识库文件记录失败:', e);
    }
  }

  /**
   * 添加一条上传记录（上传时前端先加一条，后续 loadRecords 会同步后端）
   */
  function addRecord(record: KnowledgeRecord) {
    files.value.unshift(record);
  }

  /**
   * 从后端删除一条上传记录
   */
  async function removeRecord(id: string) {
    try {
      await http.deleteKnowledgeFile(id);
      const idx = files.value.findIndex(f => f.id === id);
      if (idx !== -1) files.value.splice(idx, 1);
    } catch (e) {
      console.warn('删除知识库文件记录失败:', e);
    }
  }

  /** 清空所有记录 */
  function clearRecords() {
    files.value = [];
  }

  return {
    files,
    loadRecords,
    addRecord,
    removeRecord,
    clearRecords,
  };
});
