package com.example.demo.ai.object;

import lombok.Data;

/**
 * WebSocket 通信消息体
 *
 * 前后端通过 WebSocket 交换统一格式的 JSON 消息，所有字段均使用该结构。
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li><b>type</b> — 消息类型：text / thinking / over / error / ping / pong / tools / stop</li>
 *   <li><b>data</b> — 消息负载：字符串或对象，取决于 type</li>
 *   <li><b>uuid</b> — 会话唯一标识，用于关联用户消息与 AI 回复</li>
 * </ul>
 *
 * @see com.example.demo.ai.serviceImpl.WebsocketService 消息处理逻辑
 */
@Data
public class Communication {
    /** 消息类型 */
    public String type;

    /** 消息数据 */
    public Object data;

    /** 对话 UUID（用于关联请求与响应） */
    public String uuid;
}
