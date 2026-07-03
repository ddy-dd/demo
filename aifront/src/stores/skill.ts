import { defineStore } from "pinia";
import { ref } from "vue";
import http from "@/api/http.ts";

/**
 * Skill 上传记录
 */
export interface SkillRecord {
  id: string;
  /** 文件名 */
  name: string;
  /** 包名（用户输入的 packageName） */
  packageName: string;
  /** 上传时间 */
  uploadTime: string;
  /** 状态 */
  status: 'success' | 'error';
}

/**
 * 后端 SkillRecordEntity 结构
 */
interface BackendSkillRecord {
  id: string;
  name: string;
  packageName: string;
  status: string;
  createdAt: string;
}

/**
 * Skill 状态管理
 *
 * 记录用户上传的所有 Skill，数据从后端 SQLite 获取。
 */
export const useSkillStore = defineStore('skill', () => {
  /** 已上传的 skill 列表 */
  const skills = ref<SkillRecord[]>([]);

  /**
   * 从后端加载 Skill 上传记录
   */
  async function loadRecords() {
    try {
      const records: BackendSkillRecord[] = await http.listSkillRecords();
      skills.value = records.map(r => ({
        id: r.id,
        name: r.name,
        packageName: r.packageName,
        uploadTime: r.createdAt,
        status: r.status as 'success' | 'error',
      }));
    } catch (e) {
      console.warn('加载 Skill 记录失败:', e);
    }
  }

  /**
   * 添加一条上传记录（上传时前端先加一条，后续 loadRecords 会同步后端）
   */
  function addRecord(record: SkillRecord) {
    skills.value.unshift(record);
  }

  /**
   * 从后端删除一条上传记录
   */
  async function removeRecord(id: string) {
    try {
      await http.deleteSkillRecord(id);
      const idx = skills.value.findIndex(s => s.id === id);
      if (idx !== -1) skills.value.splice(idx, 1);
    } catch (e) {
      console.warn('删除 Skill 记录失败:', e);
    }
  }

  /** 清空所有记录 */
  function clearRecords() {
    skills.value = [];
  }

  return {
    skills,
    loadRecords,
    addRecord,
    removeRecord,
    clearRecords,
  };
});
