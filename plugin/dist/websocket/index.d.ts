/**
 * OpenClaw IMApp WebSocket 消息处理
 * 与 OpenClaw Agent 深度集成
 */
import { WebSocketServer, WebSocket } from 'ws';
import type { PluginRuntime, OpenClawConfig } from 'openclaw/plugin-sdk';
export interface IncomingMessage {
    type: 'message' | 'ping';
    id?: string;
    content?: any;
}
export interface OutgoingMessage {
    type: 'message' | 'pong' | 'read' | 'typing' | 'error';
    id?: string;
    from?: string;
    timestamp?: number;
    content?: any;
    message_ids?: string[];
    status?: string;
    error?: {
        code: string;
        message: string;
    };
}
/**
 * 初始化 WebSocket 服务器
 */
export declare function initWebSocket(server: any, runtime: PluginRuntime['channel'], cfg: OpenClawConfig): WebSocketServer;
/**
 * 向客户端发送消息
 */
export declare function send(ws: WebSocket, msg: OutgoingMessage): void;
/**
 * 广播消息给所有连接的用户
 */
export declare function broadcast(msg: OutgoingMessage, excludeWs?: WebSocket): void;
/**
 * 发送消息给特定用户
 */
export declare function sendToUser(userId: string, msg: OutgoingMessage): void;
/**
 * 获取在线用户 ID 集合
 */
export declare function getOnlineUserIds(): Set<string>;
/**
 * 获取在线客户端数量
 */
export declare function getOnlineCount(): number;
/**
 * 获取 WebSocket 服务器
 */
export declare function getWss(): WebSocketServer | null;
//# sourceMappingURL=index.d.ts.map