import { defineStore } from "pinia";
import { ref } from "vue";

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
 * Skill 状态管理
 *
 * 记录用户上传的所有 Skill，方便用户查看和管理。
 */
export const useSkillStore = defineStore('skill', () => {
  /** 已上传的 skill 列表 */
  const skills = ref<SkillRecord[]>([]);

  /**
   * 添加一条上传记录
   */
  function addRecord(record: SkillRecord) {
    skills.value.unshift(record);
  }

  /**
   * 删除一条上传记录
   */
  function removeRecord(id: string) {
    const idx = skills.value.findIndex(s => s.id === id);
    if (idx !== -1) skills.value.splice(idx, 1);
  }

  /** 清空所有记录 */
  function clearRecords() {
    skills.value = [];
  }

  return {
    skills,
    addRecord,
    removeRecord,
    clearRecords,
  };
});
