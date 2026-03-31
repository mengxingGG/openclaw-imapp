/**
 * OpenClaw IMApp 插件入口
 *
 * 专属 App 即时通讯系统，支持：
 * - Token 登录
 * - WebSocket 实时消息
 * - 媒体传输
 * - 本地存储
 * - 与 OpenClaw Agent 深度集成
 */
import { keepHttpServerTaskAlive } from 'openclaw/plugin-sdk/channel-lifecycle';
import { buildChannelConfigSchema } from 'openclaw/plugin-sdk/channel-config-primitives';
import { z } from 'zod';
import { createServer } from 'node:http';
import os from 'node:os';
import path from 'node:path';
import express from 'express';
import fs from 'node:fs';
import { randomUUID } from 'node:crypto';
import { initDatabase, closeDatabase, getDb, getMediaPath } from './db/index.js';
import { initWebSocket, closeWebSocketServer, sendToUser, getOnlineCount, getOnlineUserIds } from './websocket/index.js';
import { createImappRoutes } from './routes/index.js';
import { createToken, getBaseUrl, setBaseUrl } from './auth/index.js';
import { extForMime, mediaDirForMime, mimeForExt } from './media/index.js';
import { logger } from './util/logger.js';
// 运行时状态（模块级，单账号）
let pluginRuntime = null;
let appConfig = null;
let httpServer = null;
let retentionTimer = null;
// ==================== ChannelPlugin 定义 ====================
export const imappPlugin = {
    id: 'openclaw-imapp',
    meta: {
        id: 'openclaw-imapp',
        label: 'openclaw-imapp',
        selectionLabel: 'openclaw-imapp (专属App)',
        docsPath: '/channels/openclaw-imapp',
        docsLabel: 'openclaw-imapp',
        blurb: '专属 App 即时通讯，Token 登录，本地存储',
        order: 80,
    },
    configSchema: buildChannelConfigSchema(
        z.object({
            baseUrl: z.string().optional().describe('服务器公网地址，如 http://1.2.3.4:3100，供移动端输入后连接'),
        }).passthrough()
    ),
    capabilities: {
        chatTypes: ['direct'],
        media: true,
    },
    messaging: {
        targetResolver: {
            looksLikeId: (raw) => raw.endsWith('@imapp'),
        },
    },
    agentPrompt: {
        messageToolHints: (_params) => [
            'IMApp 是专属 App 即时通讯渠道。',
            '用户消息通过 WebSocket 实时推送。',
            '支持文本、图片、语音、视频、文件。',
            '用户的 IMApp ID 格式为 xxx@imapp。',
        ],
    },
    reload: { configPrefixes: ['channels.openclaw-imapp'] },
    // ==================== 配置管理 ====================
    config: {
        listAccountIds: (_cfg) => ['default'],
        resolveAccount: (_cfg, accountId) => ({
            accountId: accountId || 'default',
            name: 'IMApp Default',
            enabled: true,
            configured: true,
            userId: 'user-001',
        }),
        isConfigured: (account) => account.configured,
        describeAccount: (account) => ({
            accountId: account.accountId,
            name: account.name,
            enabled: account.enabled,
            configured: account.configured,
        }),
    },
    // ==================== 消息发送 ====================
    outbound: {
        deliveryMode: 'direct',
        textChunkLimit: 4000,
        sendText: async (ctx) => {
            const userId = ctx.to.replace('@imapp', '');
            const onlineIds = getOnlineUserIds();
            // 存储消息到数据库（无论是否在线）
            try {
                const db = await import('./db/index.js').then(m => m.getDb());
                const replyId = `msg-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
                await db.run(`
                    INSERT INTO messages (id, conversation_id, from_type, from_id, content_type, content, created_at)
                    VALUES (?, 'main', 'agent', 'agent', 'text', ?, ?)
                `, replyId, JSON.stringify({ type: 'text', text: ctx.text }), Date.now());
            } catch (err) {
                const { logger: log } = await import('./util/logger.js');
                log.warn(`Failed to persist outbound message: ${err}`);
            }
            if (onlineIds.has(userId)) {
                sendToUser(userId, {
                    type: 'message',
                    from: 'agent',
                    timestamp: Date.now(),
                    content: { type: 'text', text: ctx.text },
                });
            }
            else {
                await sendFcmNotification(userId, ctx.text || '');
            }
            return { channel: 'openclaw-imapp', messageId: `msg-${Date.now()}` };
        },
        sendMedia: async (ctx) => {
            const userId = ctx.to.replace('@imapp', '');
            const mediaUrl = ctx.mediaUrl || '';

            // 判断是本地路径还是远程 URL
            const isRemote = /^https?:\/\//i.test(mediaUrl);
            const isLocalPath = !isRemote && mediaUrl.length > 0;

            let fileUrl = mediaUrl;
            let mediaType = 'file';
            let filename = '';
            let fileSize = 0;

            if (isRemote) {
                // 远程 URL：先下载到媒体目录，再当本地文件处理
                try {
                    const ext = mediaUrl.split('/').pop()?.match(/\.[^.]+$/)?.[0] || '';
                    const response = await fetch(mediaUrl);
                    if (!response.ok) throw new Error(`HTTP ${response.status}: ${response.statusText}`);
                    const arrayBuffer = await response.arrayBuffer();
                    const buffer = Buffer.from(arrayBuffer);
                    fileSize = buffer.length;
                    filename = decodeURIComponent(mediaUrl.split('/').pop()) || 'file';
                    const mimeType = mimeForExt(ext) || 'application/octet-stream';
                    mediaType = mimeType.startsWith('image/') ? 'image'
                        : mimeType.startsWith('audio/') ? 'voice'
                        : mimeType.startsWith('video/') ? 'video'
                        : 'file';
                    const fileId = `${randomUUID()}.${extForMime(mimeType)}`;
                    const dir = mediaDirForMime(mimeType);
                    const storagePath = getMediaPath(path.join(dir, fileId));
                    await fs.promises.writeFile(storagePath, buffer);
                    const db = await getDb();
                    await db.run(`
                        INSERT OR REPLACE INTO media_files (id, type, filename, mime_type, size, storage_path, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                    `, fileId, mediaType, filename, mimeType, fileSize, storagePath, Date.now());
                    fileUrl = `/imapp/media/${fileId}`;
                    logger.info(`Remote media downloaded: ${filename} (${fileSize} bytes) -> ${fileId}`);
                } catch (err) {
                    logger.error(`Failed to download remote media ${mediaUrl}: ${err}`);
                    return { channel: 'openclaw-imapp', messageId: '', error: new Error(`无法下载远程文件: ${err.message}`) };
                }
            } else if (isLocalPath) {
                // 本地文件路径：读取并保存到媒体目录
                try {
                    const buffer = await fs.promises.readFile(mediaUrl);
                    fileSize = buffer.length;
                    filename = path.basename(mediaUrl);
                    const mimeType = mimeForExt(path.extname(filename)) || 'application/octet-stream';
                    mediaType = mimeType.startsWith('image/') ? 'image'
                        : mimeType.startsWith('audio/') ? 'voice'
                        : mimeType.startsWith('video/') ? 'video'
                        : 'file';
                    const fileId = `${randomUUID()}.${extForMime(mimeType)}`;
                    const dir = mediaDirForMime(mimeType);
                    const storagePath = getMediaPath(path.join(dir, fileId));
                    await fs.promises.writeFile(storagePath, buffer);
                    const db = await getDb();
                    await db.run(`
                        INSERT OR REPLACE INTO media_files (id, type, filename, mime_type, size, storage_path, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                    `, fileId, mediaType, filename, mimeType, fileSize, storagePath, Date.now());
                    fileUrl = `/imapp/media/${fileId}`;
                    logger.info(`Local file uploaded: ${filename} (${fileSize} bytes) -> ${fileId}`);
                } catch (err) {
                    logger.error(`Failed to process local media file ${mediaUrl}: ${err}`);
                    return { channel: 'openclaw-imapp', messageId: '', error: new Error(`无法读取本地文件: ${err.message}`) };
                }
            }

            const content = {
                type: mediaType,
                url: fileUrl,
                text: ctx.text || null,
                filename: filename || null,
                size: fileSize || null,
            };

            const payload = {
                type: 'message',
                from: 'agent',
                timestamp: Date.now(),
                content,
            };

            // 存储媒体消息到数据库
            try {
                const db = await getDb();
                const replyId = `msg-${Date.now()}-${randomUUID().slice(0, 8)}`;
                await db.run(`
                    INSERT INTO messages (id, conversation_id, from_type, from_id, content_type, content, created_at)
                    VALUES (?, 'main', 'agent', 'agent', ?, ?, ?)
                `, replyId, mediaType, JSON.stringify(content), Date.now());
            } catch (err) {
                logger.warn(`Failed to persist outbound media: ${err}`);
            }

            const onlineIds = getOnlineUserIds();
            if (onlineIds.has(userId)) {
                sendToUser(userId, payload);
            } else {
                await sendFcmNotification(userId, ctx.text || `[${mediaType}] ${filename || '文件'}`);
            }
            return { channel: 'openclaw-imapp', messageId: `msg-${Date.now()}` };
        },
    },
    // ==================== 状态管理 ====================
    status: {
        defaultRuntime: {
            accountId: '',
            lastError: null,
            lastInboundAt: null,
            lastOutboundAt: null,
        },
        collectStatusIssues: () => [],
        buildChannelSummary: ({ snapshot }) => ({
            configured: snapshot.configured ?? true,
            lastError: snapshot.lastError ?? null,
            lastInboundAt: snapshot.lastInboundAt ?? null,
            lastOutboundAt: snapshot.lastOutboundAt ?? null,
            onlineClients: getOnlineCount(),
        }),
        buildAccountSnapshot: ({ account, runtime }) => ({
            ...runtime,
            accountId: account.accountId,
            name: account.name,
            enabled: account.enabled,
            configured: account.configured,
        }),
    },
    // ==================== Gateway ====================
    gateway: {
        startAccount: async (ctx) => {
            logger.info('Starting IMApp gateway...');
            if (!ctx) {
                logger.warn('gateway.startAccount: called with undefined ctx');
                return;
            }
            if (ctx.channelRuntime)
                pluginRuntime = ctx.channelRuntime;
            if (ctx.cfg)
                appConfig = ctx.cfg;
            const { pruneExpiredMessages } = await import('./db/index.js');
            await initDatabase();
            await pruneExpiredMessages();
            logger.info('Database initialized');
            // 设置公网 baseUrl（供移动端连接）
            // 配置路径：channels.openclaw-imapp
            const channelCfg = ctx.cfg?.['channels.openclaw-imapp'];
            const configuredBaseUrl = channelCfg?.baseUrl;
            if (configuredBaseUrl) {
                setBaseUrl(configuredBaseUrl);
                logger.info(`IMApp baseUrl set to: ${configuredBaseUrl}`);
            }
            else if (process.env.IMAPP_BASE_URL) {
                setBaseUrl(process.env.IMAPP_BASE_URL);
            }
            const app = express();
            app.use(express.json());
            app.use('/imapp', createImappRoutes(pluginRuntime, appConfig));
            logger.info('HTTP routes registered at /imapp');
            const port = process.env.IMAPP_PORT ? parseInt(process.env.IMAPP_PORT, 10) : 3100;
            httpServer = createServer(app);
            if (pluginRuntime && appConfig) {
                initWebSocket(httpServer, pluginRuntime, appConfig);
                logger.info('WebSocket server initialized');
            }
            else {
                logger.warn('channelRuntime or config not available, WebSocket may not work');
            }
            // 监听，端口冲突给出明确错误
            await new Promise((resolve, reject) => {
                httpServer.once('error', (err) => {
                    if (err.code === 'EADDRINUSE') {
                        reject(new Error(`IMApp: port ${port} is already in use. Set IMAPP_PORT env var to use a different port.`));
                    }
                    else {
                        reject(err);
                    }
                });
                httpServer.listen(port, '0.0.0.0', () => {
                    logger.info(`HTTP server listening on 0.0.0.0:${port}`);
                    resolve();
                });
            });
            ctx.setStatus?.({
                accountId: 'default',
                running: true,
                lastStartAt: Date.now(),
            });
            ctx.log?.info?.(`[imapp] Gateway started on port ${port}`);
            logger.info(`IMApp gateway started on port ${port}`);
            if (retentionTimer) {
                clearInterval(retentionTimer);
            }
            retentionTimer = setInterval(() => {
                pruneExpiredMessages().catch((err) => {
                    logger.warn(`Failed to prune expired indexed messages: ${err}`);
                });
            }, 6 * 60 * 60 * 1000);
            // 保持运行直到 gateway 关闭信号
            await keepHttpServerTaskAlive({
                server: httpServer,
                abortSignal: ctx.abortSignal,
                onAbort: () => {
                    logger.info('IMApp: abort signal received, closing server...');
                    if (retentionTimer) {
                        clearInterval(retentionTimer);
                        retentionTimer = null;
                    }
                    closeWebSocketServer();
                    httpServer?.close();
                    closeDatabase();
                    pluginRuntime = null;
                    appConfig = null;
                    httpServer = null;
                },
            });
        },
        stopAccount: async (_ctx) => {
            logger.info('Stopping IMApp gateway (stopAccount)...');
            if (httpServer) {
                await new Promise((resolve) => {
                    httpServer.close(() => {
                        logger.info('HTTP server closed');
                        resolve();
                    });
                });
                httpServer = null;
            }
            if (retentionTimer) {
                clearInterval(retentionTimer);
                retentionTimer = null;
            }
            closeWebSocketServer();
            closeDatabase();
            pluginRuntime = null;
            appConfig = null;
        },
    },
    // ==================== 认证 ====================
    auth: {
        login: async ({ cfg: _cfg, accountId, runtime, args, verbose: _verbose }) => {
            const log = (msg) => runtime.log?.(msg);
            
            // 解析命令参数
            const parts = (args || '').split(' ').filter(Boolean);
            const subCmd = parts[0] || 'create';
            
            // 导入 auth 函数
            const { createToken, listDevices, revokeToken, getDeviceCount } = await import('./auth/index.js');
            
            if (subCmd === 'list' || subCmd === 'ls') {
                // 列出所有设备
                const devices = await listDevices();
                log?.(`\n📱 已登录设备 (共 ${devices.length}/5)：\n`);
                log?.('  设备码     设备名称              最后活跃时间');
                log?.('  --------   ------------------   ------------------');
                for (const d of devices) {
                    const lastActive = new Date(d.lastActiveAt).toLocaleString('zh-CN');
                    log?.(`  ${d.deviceCode}   ${d.deviceName.padEnd(18)} ${lastActive}`);
                }
                log?.('');
                return;
            }
            
            if (subCmd === 'revoke' || subCmd === 'rm' || subCmd === 'delete') {
                // 撤销设备
                const target = parts[1];
                if (!target) {
                    log?.('用法: openclaw channels login --channel openclaw-imapp revoke <token或设备码>');
                    return;
                }
                const result = await revokeToken(target);
                if (result.success) {
                    log?.(`\n✅ ${result.message}\n`);
                } else {
                    log?.(`\n❌ ${result.error}\n`);
                }
                return;
            }
            
            if (subCmd === 'create' || subCmd === 'add' || !subCmd) {
                // 创建新 Token
                const deviceName = parts.slice(1).join(' ') || 'Android Device';
                const result = await createToken(deviceName, accountId || null);
                
                if (!result.success) {
                    log?.(`\n❌ ${result.error}\n`);
                    return;
                }
                
                log?.('\n');
                log?.('═══════════════════════════════════════════════════');
                log?.('              🎉 新设备 Token 已生成！              ');
                log?.('═══════════════════════════════════════════════════');
                log?.('');
                log?.(`  设备名称：${result.deviceName}`);
                log?.(`  设备码：  ${result.deviceCode}`);
                log?.(`  Token：  ${result.token}`);
                log?.('');
                log?.('═══════════════════════════════════════════════════');
                log?.('');
                log?.('请将以上 Token（令牌）复制到 APP 中进行登录。');
                log?.('⚠️  Token 仅显示一次，请妥善保管！');
                log?.('');
                
                // 保存 Token 到文件
                const tokenPath = path.join(os.tmpdir(), `imapp_token_${result.deviceCode}.txt`);
                const tokenContent = `OpenClaw IMApp 登录 Token
========================

设备名称：${result.deviceName}
设备码：  ${result.deviceCode}
Token：  ${result.token}

服务器地址：${getBaseUrl()}

请在 APP 中输入以上 Token 完成登录。
`;
                try {
                    const fs = await import('fs');
                    fs.writeFileSync(tokenPath, tokenContent);
                    log?.(`📄 Token 已保存到: ${tokenPath}`);
                } catch (e) {
                    logger.warn('Failed to save token file:', e.message);
                }
                log?.('');
                return;
            }
            
            // 未知命令
            log?.('用法：');
            log?.('  openclaw channels login --channel openclaw-imapp           # 创建新 Token');
            log?.('  openclaw channels login --channel openclaw-imapp list      # 列出所有设备');
            log?.('  openclaw channels login --channel openclaw-imapp revoke    # 撤销设备');
        },
    },
};
// ==================== FCM 推送辅助 ====================
async function sendFcmNotification(userId, text) {
    try {
        const { getDb } = await import('./db/index.js');
        const db = await getDb();
        const tokens = await db.all('SELECT fcm_token FROM fcm_tokens WHERE user_id = ?', userId);
        if (!tokens.length)
            return;
        const { getFcmServerKey } = await import('./auth/index.js');
        const serverKey = getFcmServerKey();
        if (!serverKey) {
            logger.warn('FCM server key not configured, cannot push notification');
            return;
        }
        const preview = text.length > 80 ? text.slice(0, 80) + '…' : text;
        for (const row of tokens) {
            try {
                const res = await fetch('https://fcm.googleapis.com/fcm/send', {
                    method: 'POST',
                    headers: {
                        'Authorization': `key=${serverKey}`,
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify({
                        to: row.fcm_token,
                        notification: {
                            title: 'OpenClaw IMApp',
                            body: preview,
                            sound: 'default',
                            click_action: 'OPEN_CHAT',
                        },
                        data: { type: 'message', text: preview },
                    }),
                });
                if (!res.ok) {
                    logger.warn(`FCM push failed for user=${userId}: HTTP ${res.status}`);
                }
            }
            catch (err) {
                logger.warn(`FCM push error for user=${userId}: ${err}`);
            }
        }
    }
    catch (err) {
        logger.warn(`sendFcmNotification error: ${err}`);
    }
}
// ==================== 插件入口（openclaw 插件规范） ====================
const pluginDefinition = {
    id: 'openclaw-imapp',
    name: 'OpenClaw IMApp',
    description: '专属 App 即时通讯系统，自托管，完全掌控',
    register(api) {
        api.registerChannel({ plugin: imappPlugin });
    },
};
export default pluginDefinition;
// 兼容旧式导出
export function register(api) {
    if (api)
        api.registerChannel({ plugin: imappPlugin });
    return imappPlugin;
}
//# sourceMappingURL=index.js.map
