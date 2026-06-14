import axios, { type AxiosResponse } from 'axios';

/** 后端 API 基础地址 */
const BaseURL = 'http://localhost:8080/api';

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
}

export default AxiosUtils;
