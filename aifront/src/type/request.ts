export interface WsMessage {
    type: string // 消息类型 number, ping, stop, text, webrtc-offer
    data: any
}