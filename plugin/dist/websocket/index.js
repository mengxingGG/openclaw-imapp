/**
 * OpenClaw IMApp WebSocket 消息处理
 * 与 OpenClaw Agent 深度集成
 */
import { WebSocketServer, WebSocket } from 'ws';
import { randomUUID } from 'node:crypto';
import { getDb } from '../db/index.js';
import { verifySession, getBaseUrl } from '../auth/index.js';
import { logger } from '../util/logger.js';
import { trimMessages } from '../routes/index.js';
const clients = new Map();
let wss = null;
let channelRuntime = null;
let config = null;
/**
 * 初始化 WebSocket 服务器
 */
export function initWebSocket(server, runtime, cfg) {
    channelRuntime = runtime;
    config = cfg;
    wss = new WebSocketServer({ server, path: '/imapp/ws' });
    wss.on('connection', async (ws, req) => {
        const url = new URL(req.url || '', `http://${req.headers.host}`);
        const token = url.searchParams.get('token');
        const userId = url.searchParams.get('user_id') || 'user-001';
        if (!token) {
            sendError(ws, 'AUTH_INVALID', '缺少认证 token');
            ws.close();
            return;
        }
        // 验证 token
        const session = await verifySession(token);
        if (!session.valid) {
            sendError(ws, 'AUTH_INVALID', '无效的认证 token');
            ws.close();
            return;
        }
        // 使用会话中的用户 ID（优先于 query 参数）
        const authenticatedUserId = session.userId || userId;
        // 注册客户端
        const client = {
            ws,
            userId: authenticatedUserId,
            deviceId: session.deviceId || `device-${Date.now()}`,
            contextToken: generateContextToken(),
            lastPing: Date.now(),
        };
        clients.set(ws, client);
        logger.info(`WebSocket connected: user=${authenticatedUserId}`);
        // 发送欢迎消息（作为时间戳同步锚点，不存入数据库）
        const welcomeTs = Date.now();
        send(ws, {
            type: 'message',
            id: `welcome-${welcomeTs}`,
            from: 'system',
            timestamp: welcomeTs,
            content: { type: 'text', text: '🤖 连接成功，开始对话吧！' },
        });
        ws.on('message', (data) => {
            try {
                const msg = JSON.parse(data.toString());
                handleMessage(ws, client, msg);
            }
            catch (err) {
                logger.error(`Failed to parse message: ${err}`);
                sendError(ws, 'INVALID_MESSAGE', '消息格式错误');
            }
        });
        ws.on('close', () => {
            clients.delete(ws);
            // 清理处理中标记
            client.processing = false;
            logger.info(`WebSocket disconnected: user=${client.userId}`);
        });
        ws.on('error', (err) => {
            logger.error(`WebSocket error: ${err}`);
            clients.delete(ws);
            client.processing = false;
        });
    });
    // 心跳检测 - 5分钟超时，处理中的客户端跳过检查
    setInterval(() => {
        const now = Date.now();
        const dead = [];
        for (const [ws, client] of clients) {
            if (client.processing) continue;
            if (now - client.lastPing > 300000) {
                logger.warn(`Client timeout: user=${client.userId}`);
                dead.push(ws);
            }
        }
        for (const ws of dead) {
            try { ws.terminate(); } catch (_) {}
            clients.delete(ws);
        }
    }, 30000);
    return wss;
}
/**
 * 处理收到的消息
 */
async function handleMessage(ws, client, msg) {
    switch (msg.type) {
        case 'ping':
            client.lastPing = Date.now();
            send(ws, { type: 'pong' });
            break;
        case 'pong':
            client.lastPing = Date.now();
            break;
        case 'message':
            if (msg.content) {
                await handleUserMessage(ws, client, msg);
            }
            break;
        default:
            sendError(ws, 'INVALID_MESSAGE', `未知消息类型: ${msg.type}`);
    }
}
/**
 * 处理用户消息 - 与 OpenClaw Agent 集成
 */
async function handleUserMessage(ws, client, msg) {
    if (!channelRuntime || !config) {
        sendError(ws, 'NOT_READY', '服务尚未就绪');
        return;
    }
    // 标记客户端正在处理消息，心跳检测跳过
    client.processing = true;
    const db = await getDb();
    const messageId = msg.id || randomUUID();
    const now = Date.now();
    // 提取文本内容，并为非文本消息构建描述性 Body
    const baseUrl = getBaseUrl();
    let textBody = '';
    let mediaUrl;
    if (msg.content.type === 'text') {
        textBody = msg.content.text || '';
    }
    else if (msg.content.url) {
        // 将相对路径转为绝对 URL，便于 Agent 访问
        const rawUrl = msg.content.url;
        mediaUrl = rawUrl.startsWith('http') ? rawUrl : `${baseUrl.replace(/\/$/, '')}${rawUrl}`;
        if (msg.content.type === 'image') {
            textBody = `[用户发送了一张图片，URL: ${mediaUrl}]`;
        }
        else if (msg.content.type === 'voice') {
            textBody = `[用户发送了一段语音消息，URL: ${mediaUrl}。当前未配置语音识别，无法转写内容，请告知用户以文字描述需求。]`;
        }
        else if (msg.content.type === 'video') {
            textBody = `[用户发送了一段视频，URL: ${mediaUrl}。当前不支持视频内容分析，请告知用户。]`;
        }
        else if (msg.content.type === 'file') {
            const filename = msg.content.filename || '文件';
            textBody = `[用户分享了文件「${filename}」，下载地址: ${mediaUrl}]`;
        }
    }
    // 存储用户消息
    await db.run(`
    INSERT INTO messages (id, conversation_id, from_type, from_id, content_type, content, context_token, created_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
  `, messageId, 'main', 'user', client.userId, msg.content.type, JSON.stringify(msg.content), client.contextToken, now);
    logger.info(`User message: ${messageId} from=${client.userId} type=${msg.content.type} text="${textBody.slice(0, 80)}"`);
    // 构建 OpenClaw 消息上下文（From/To 使用 @imapp 格式，以便 outbound 路由回正确客户端）
    const peerId = `${client.userId}@imapp`;
    const ctx = {
        From: peerId,
        To: peerId,
        Body: textBody,
        Timestamp: now,
        MessageId: messageId,
        CommandAuthorized: true,
        SessionKey: client.sessionKey,
        // 媒体支持（使用完整 URL）
        MediaUrl: mediaUrl,
    };
    // 解析 Agent 路由
    const route = channelRuntime.routing.resolveAgentRoute({
        cfg: config,
        channel: 'openclaw-imapp',
        accountId: 'default',
        peer: { kind: 'direct', id: peerId },
    });
    client.sessionKey = route.sessionKey;
    ctx.SessionKey = route.sessionKey;
    // 记录入站会话（确保 Agent 有正确的会话上下文）
    const storePath = channelRuntime.session.resolveStorePath(config.session?.store, {
        agentId: route.agentId,
    });
    const finalizedCtx = channelRuntime.reply.finalizeInboundContext(ctx);
    await channelRuntime.session.recordInboundSession({
        storePath,
        sessionKey: route.sessionKey,
        ctx: finalizedCtx,
        updateLastRoute: {
            sessionKey: route.mainSessionKey,
            channel: 'openclaw-imapp',
            to: peerId,
            accountId: 'default',
        },
        onRecordError: (err) => logger.error(`recordInboundSession: ${err}`),
    });
    // 发送输入状态
    send(ws, { type: 'typing', status: 'typing' });
    // 长时间处理时定期重发 typing 信号，防止客户端超时清除指示器
    // 同时更新 lastPing，防止心跳检测误杀正在处理的客户端
    const typingKeepalive = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) {
            send(ws, { type: 'typing', status: 'typing' });
            client.lastPing = Date.now();
        }
    }, 5000);
    // 创建回复分发器
    let streamingMessageId = null;
    const { dispatcher, replyOptions, markDispatchIdle } = channelRuntime.reply.createReplyDispatcherWithTyping({
        humanDelay: { minMs: 100, maxMs: 300 },
        typingCallbacks: {
            onReplyStart: async () => send(ws, { type: 'typing', status: 'typing' }),
            onIdle: () => send(ws, { type: 'typing', status: 'idle' }),
        },
        deliver: async (payload, { kind }) => {
            const text = payload.text || '';
            const mediaUrl = payload.mediaUrl || payload.mediaUrls?.[0];

            if (kind === 'tool') {
                // 工具调用结果，不推送给用户，只发 typing 保活
                return;
            }

            if (kind === 'block') {
                // 中间文本块 — 流式推送
                if (!streamingMessageId) {
                    streamingMessageId = randomUUID();
                }
                send(ws, {
                    type: 'message',
                    id: streamingMessageId,
                    from: 'agent',
                    timestamp: Date.now(),
                    stream: 'continue',
                    content: { type: 'text', text },
                });
                return;
            }

            // kind === 'final' — 最终回复，存储到数据库
            const replyId = streamingMessageId || randomUUID();
            const db = await getDb();
            await db.run(`
                INSERT INTO messages (id, conversation_id, from_type, from_id, content_type, content, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            `, replyId, 'main', 'agent', 'agent', mediaUrl ? 'image' : 'text', JSON.stringify({ type: mediaUrl ? 'image' : 'text', text, url: mediaUrl }), Date.now());

            send(ws, {
                type: 'message',
                id: replyId,
                from: 'agent',
                timestamp: Date.now(),
                stream: streamingMessageId ? 'end' : 'start',
                content: { type: mediaUrl ? 'image' : 'text', text, url: mediaUrl },
            });
            streamingMessageId = null;
        },
        onError: (err, info) => {
            const errStr = String(err);
            logger.error(`Reply error (${info.kind}): ${errStr}`);
            // 检测模型不支持图片的错误
            const isImageUnsupported = /image|vision|multimodal|visual|picture|photo/i.test(errStr) &&
                /not support|unsupport|invalid|cannot/i.test(errStr);
            const userMsg = isImageUnsupported
                ? '当前模型不支持图片分析，请发送文字消息'
                : `Agent 处理失败，请稍后重试`;
            sendError(ws, isImageUnsupported ? 'MODEL_NO_VISION' : 'AGENT_ERROR', userMsg);
        },
    });
    try {
        // 调用 Agent 处理消息
        await channelRuntime.reply.withReplyDispatcher({
            dispatcher,
            run: () => channelRuntime.reply.dispatchReplyFromConfig({
                ctx: finalizedCtx,
                cfg: config,
                dispatcher,
                replyOptions,
            }),
        });
        // 发送已读回执
        send(ws, {
            type: 'read',
            message_ids: [messageId],
        });
    }
    catch (err) {
        logger.error(`Agent dispatch failed: ${err}`);
        sendError(ws, 'AGENT_ERROR', `处理消息失败，请重试`);
    }
    finally {
        clearInterval(typingKeepalive);
        // 确保客户端清除 typing 状态
        send(ws, { type: 'typing', status: 'idle' });
        markDispatchIdle();
        // 处理完成，清除标记并更新心跳时间
        client.processing = false;
        client.lastPing = Date.now();
        // 异步清理超量消息
        trimMessages().catch(() => {});
    }
}
/**
 * 向客户端发送消息
 */
export function send(ws, msg) {
    if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(msg));
    }
}
/**
 * 发送错误消息
 */
function sendError(ws, code, message) {
    send(ws, {
        type: 'error',
        error: { code, message },
    });
}
/**
 * 广播消息给所有连接的用户
 */
export function broadcast(msg, excludeWs) {
    for (const [ws] of clients) {
        if (ws !== excludeWs) {
            send(ws, msg);
        }
    }
}
/**
 * 发送消息给特定用户
 */
export function sendToUser(userId, msg) {
    for (const [ws, client] of clients) {
        if (client.userId === userId) {
            send(ws, msg);
        }
    }
}
/**
 * 生成上下文 token
 */
function generateContextToken() {
    return `ctx-${randomUUID()}`;
}
/**
 * 获取在线用户 ID 集合
 */
export function getOnlineUserIds() {
    const ids = new Set();
    for (const [, client] of clients) {
        ids.add(client.userId);
    }
    return ids;
}
/**
 * 获取在线客户端数量
 */
export function getOnlineCount() {
    return clients.size;
}
/**
 * 获取 WebSocket 服务器
 */
export function getWss() {
    return wss;
}
//# sourceMappingURL=index.js.map