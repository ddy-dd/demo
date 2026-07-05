import axios, { type AxiosResponse } from 'axios';

/** 后端 API 基础地址 */
const BaseURL = 'http://localhost:8888/api';

/**
 * HTTP 工具类（基于 Axios）
 *
 * 封装了常规 REST 请求方法，统一管理 baseURL、超时和请求头。
 * 如需生产环境，建议加上请求/响应拦截器实现统一错误处理和 Token 注入。
 */
class AxiosUtils {
  private static instance = axios.create({
    baseURL: BaseURL,
    timeout: 10000,  // 10s 超时，文件上传可能需要更大
    headers: {
      'Content-Type': 'application/json',
    },
  });

  static async get<T>(url: string): Promise<T> {
    const response: AxiosResponse<T> = await this.instance.get(url);
    return response.data;
  }

  static async post<T>(url: string, data?: any): Promise<T> {
    const response: AxiosResponse<T> = await this.instance.post(url, data);
    return response.data;
  }

  static async put<T>(url: string, data?: any): Promise<T> {
    const response: AxiosResponse<T> = await this.instance.put(url, data);
    return response.data;
  }

  static async delete<T>(url: string): Promise<T> {
    const response: AxiosResponse<T> = await this.instance.delete(url);
    return response.data;
  }

  /**
   * 上传文件并自动向量化存入知识库
   * 请求头自动设为 multipart/form-data
   */
  static async uploadAndVectorize<T>(data: FormData): Promise<T> {
    const url = '/milvus/upload';
    const response: AxiosResponse<T> = await this.instance.post(url, data, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    });
    return response.data;
  }

  /** 上传 skill .md 文件 */
  static async uploadSkill<T>(data: FormData): Promise<T> {
    const url = '/skill/upload';
    const response: AxiosResponse<T> = await this.instance.post(url, data, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    });
    return response.data;
  }

  /** 上传小说文件供 AI 分析 */
  static async uploadNovel<T>(data: FormData): Promise<T> {
    const url = '/file/novel-upload';
    const response: AxiosResponse<T> = await this.instance.post(url, data, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    });
    return response.data;
  }

  /** ASR 语音转写（上传音频 → 返回转写文本） */
  static async asrTranscribe<T>(data: FormData): Promise<T> {
    const url = '/asr/transcribe';
    const response: AxiosResponse<T> = await this.instance.post(url, data, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    });
    return response.data;
  }

  // ── 对话相关 ─────────────────────────────────────────────

  /** 获取所有对话列表 */
  static async listConversations<T>(): Promise<T> {
    return this.get<T>('/conversations');
  }

  /** 获取对话的详细消息 */
  static async getConversationMessages<T>(conversationId: string): Promise<T> {
    return this.get<T>(`/conversations/${conversationId}/messages`);
  }

  /** 删除对话 */
  static async deleteConversation(conversationId: string): Promise<void> {
    return this.delete(`/conversations/${conversationId}`);
  }

  // ── Skill 记录相关 ───────────────────────────────────────

  /** 获取所有 Skill 上传记录 */
  static async listSkillRecords<T>(): Promise<T> {
    return this.get<T>('/skill-records');
  }

  /** 删除一条 Skill 记录 */
  static async deleteSkillRecord(id: string): Promise<void> {
    return this.delete(`/skill-records/${id}`);
  }

  // ── 知识库文件记录相关 ───────────────────────────────────

  /** 获取所有知识库文件上传记录 */
  static async listKnowledgeFiles<T>(): Promise<T> {
    return this.get<T>('/knowledge-files');
  }

  /** 删除一条知识库文件记录 */
  static async deleteKnowledgeFile(id: string): Promise<void> {
    return this.delete(`/knowledge-files/${id}`);
  }
}

export default AxiosUtils;
