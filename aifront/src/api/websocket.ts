import type {WsMessage} from '@/type/request.ts';
const BASE_WS_URL =`ws://localhost:8080/api/websocket/`;

class WebsocketClient {
    private static instance: WebsocketClient;
    private ws: WebSocket
    public interval: any
    ping() {
        console.log('发送ping消息')
        const pingMsg: WsMessage = {
            type: 'ping',
            data: null,
        }
        this.ws.send(JSON.stringify(pingMsg))
    }

    // 初始化连接
    public Init(chatId :String) {
        const WebSocketURL = `${BASE_WS_URL}${chatId}`;
        this.ws = new WebSocket(WebSocketURL)

        this.ws.onopen = () => {
            console.log('连接成功')
            // 连接成功后，发送 ping 消息
            this.interval = setInterval(() => {
                this.ping()
            }, 20000)
        }

        this.ws.onmessage = (event) => {
            const msg: WsMessage = JSON.parse(event.data)
            console.log('收到消息', msg)

            switch (msg.type) {
                case 'pong':
                    console.log('websocket 连接正常------------')
                    break
                case 'stop':
                    clearInterval(this.interval)
                    this.ws.close()
                    break
                default:
                    break
            }
        }

        this.ws.onclose = () => {
            console.log('连接关闭')
            clearInterval(this.interval)
            this.ws.close()
            WebsocketClient.instance = null
        }

        this.ws.onerror = (error) => {
            console.log('连接错误', error)
            clearInterval(this.interval)
            this.ws.close()
        }
    }

    //发起对话，打电话的
    public static getInstance(chatId?: String): WebsocketClient | null {
        if (!WebsocketClient.instance) {
            if (chatId) {
                WebsocketClient.instance = new WebsocketClient()
                WebsocketClient.instance.Init(chatId)
            }else{
                console.log('chatId is null')
                return null
            }
        }
        return WebsocketClient.instance
    }

    public getWsConnection(): WebSocket {
        return WebsocketClient.instance.ws
    }

    // ...existing code...
    public send(data: any) {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify(data))
        } else {
            console.warn('WebSocket 未连接，无法发送消息')
        }
    }

    public close() {
        if (WebsocketClient.instance) {
            // 只有 OPEN 状态下才发送 stop
            if (this.ws && this.ws.readyState === WebSocket.OPEN) {
                this.send({
                    type: 'stop',
                    data: null,
                })
                this.ws.close()
            } else if (this.ws && (this.ws.readyState === WebSocket.CONNECTING || this.ws.readyState === WebSocket.CLOSING)) {
                // 直接关闭
                this.ws.close()
            }
            WebsocketClient.instance = null
        }
    }
}
export default WebsocketClient