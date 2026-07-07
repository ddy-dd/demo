package com.example.demo.ai.calling.model;

import lombok.Data;

/**
 * 通话 WebSocket 通信消息体
 *
 * <h3>消息类型</h3>
 * <ul>
 *   <li><b>client → server:</b></li>
 *   <ul>
 *     <li><code>audio_chunk</code> — 音频二进制帧</li>
 *     <li><code>audio_end</code> — 语音输入结束</li>
 *     <li><code>interrupt</code> — 打断 AI 回复</li>
 *     <li><code>ping</code> — 心跳</li>
 *   </ul>
 *   <li><b>server → client:</b></li>
 *   <ul>
 *     <li><code>asr_partial</code> — ASR 中间识别结果</li>
 *     <li><code>asr_final</code> — ASR 最终识别结果</li>
 *     <li><code>agent_thinking</code> — AI 思考过程（流式）</li>
 *     <li><code>agent_response</code> — AI 回复（流式）</li>
 *     <li><code>agent_done</code> — AI 回复结束</li>
 *     <li><code>call_started</code> — 通话开始确认</li>
 *     <li><code>call_ended</code> — 通话结束</li>
 *     <li><code>error</code> — 错误信息</li>
 *     <li><code>pong</code> — 心跳回复</li>
 *   </ul>
 * </ul>
 */
@Data
public class CallCommunication {
    private String type;
    private Object data;
    private String callId;
}
