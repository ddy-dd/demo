import axios, {type AxiosResponse } from 'axios'
const BaseURL = 'http://localhost:8080/api'

class AxiosUtils {
    private static instance = axios.create({
            baseURL: BaseURL,
            timeout: 5000,
            headers: {
                'Content-Type': 'application/json',
            },
        },
    )

    static async get<T>(url: string): Promise<T> {
        const response: AxiosResponse<T> = await this.instance.get(url)
        return response.data
    }

    static async post<T>(url: string, data?: any): Promise<T> {
        const response: AxiosResponse<T> = await this.instance.post(url, data)
        return response.data
    }

    static async put<T>(url: string, data?: any): Promise<T> {
        const response: AxiosResponse<T> = await this.instance.put(url, data)
        return response.data
    }

    static async delete<T>(url: string): Promise<T> {
        const response: AxiosResponse<T> = await this.instance.delete(url)
        return response.data
    }

    static async uploadAndVectorize<T>(data: FormData): Promise<T> {
        const url = '/milvus/upload'
        const response: AxiosResponse<T> = await this.instance.post(url, data, {
            headers: {
                'Content-Type': 'multipart/form-data',
            },
        })
        return response.data
    }


}

export default AxiosUtils
