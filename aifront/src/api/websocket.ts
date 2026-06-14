import type {WsMessage} from '@/type/request.ts';

const BASE_WS_URL = `ws://localhost:8080/api/websocket/`;

/**
 * WebSocket 客户端（单例）
 *
 * 管理 AI 对话的 WebSocket 连接生命周期，包括：
 * - 连接建立 / 关闭
 * - 心跳保活（每 20s 发送 ping）
 * - 消息发送
 *
 * ⚠️ 注意：SimpleChat.vue 中的 initWebSocketListener() 也会建立连接并管理心跳。
 *   当前 design 下两者功能有重叠。如需统一，建议后续将所有 WS 管理收拢到此单例中。
 */
class WebsocketClient {
    private static instance: WebsocketClient | null = null;
    private ws: WebSocket | null = null;
    public interval: ReturnType<typeof setInterval> | null = null;

    /**
     * 发送心跳 ping（由定时器调用）
     */
    ping() {
        if (this.ws?.readyState !== WebSocket.OPEN) return;
        const pingMsg: WsMessage = { type: 'ping', data: null };
        this.ws.send(JSON.stringify(pingMsg));
    }

    /**
     * 初始化 WebSocket 连接
     * @param chatId 会话标识，用于服务端区分不同对话
     */
    public Init(chatId: string) {
        const WebSocketURL = `${BASE_WS_URL}${chatId}`;
        this.ws = new WebSocket(WebSocketURL);

        this.ws.onopen = () => {
            console.log('WebSocket 连接成功');
            // 启动心跳：每 20s 发送一次 ping
            this.interval = setInterval(() => this.ping(), 20000);
        };

        this.ws.onmessage = (event) => {
            const msg: WsMessage = JSON.parse(event.data);
            switch (msg.type) {
                case 'pong':
                    console.debug('心跳正常');
                    break;
                case 'stop':
                    this.close();
                    break;
                default:
                    console.debug('收到消息:', msg);
                    break;
            }
        };

        this.ws.onclose = () => {
            console.log('WebSocket 连接已关闭');
            this.cleanup();
        };

        this.ws.onerror = (error) => {
            console.error('WebSocket 连接错误', error);
            this.cleanup();
        };
    }

    /** 清理资源 */
    private cleanup() {
        if (this.interval) {
            clearInterval(this.interval);
            this.interval = null;
        }
        this.ws = null;
        WebsocketClient.instance = null;
    }

    /**
     * 获取单例实例
     * 如果实例不存在则创建新连接
     */
    public static getInstance(chatId?: string): WebsocketClient | null {
        if (!WebsocketClient.instance) {
            if (!chatId) {
                console.error('chatId 为空，无法创建连接');
                return null;
            }
            WebsocketClient.instance = new WebsocketClient();
            WebsocketClient.instance.Init(chatId);
        }
        return WebsocketClient.instance;
    }

    /** 获取底层 WebSocket 对象 */
    public getWsConnection(): WebSocket {
        if (!WebsocketClient.instance?.ws) {
            throw new Error('WebSocket 未初始化');
        }
        return WebsocketClient.instance.ws;
    }

    /**
     * 发送消息（JSON 格式）
     * @param data 要发送的数据对象
     */
    public send(data: Record<string, unknown>) {
        if (this.ws?.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify(data));
        } else {
            console.warn('WebSocket 未连接，消息未发送');
        }
    }

    /**
     * 关闭连接
     * 发送 stop 消息告知服务端，然后关闭底层连接
     */
    public close() {
        if (WebsocketClient.instance) {
            if (this.ws?.readyState === WebSocket.OPEN) {
                this.send({ type: 'stop', data: null });
                this.ws.close();
            } else if (this.ws?.readyState === WebSocket.CONNECTING
                || this.ws?.readyState === WebSocket.CLOSING) {
                this.ws.close();
            }
            this.cleanup();
        }
    }
}

export default WebsocketClient;
