/**
 * OpenClaw IMApp HTTP 路由
 */
import express, { Router } from 'express';
import { verifyToken, listDevices, revokeToken, getDeviceCount } from '../auth/index.js';
import { getDb, getMediaPath } from '../db/index.js';
import { extForMime, mediaDirForMime, mimeForExt } from '../media/index.js';
import { logger } from '../util/logger.js';
import { randomUUID } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { getOnlineCount } from '../websocket/index.js';

function getTokenFromRequest(req) {
    const auth = req.headers.authorization;
    if (auth && auth.startsWith('Bearer ')) {
        return auth.substring(7);
    }
    if (typeof req.query.token === 'string') {
        return req.query.token;
    }
    return null;
}

function requireSession(req, res) {
    const token = getTokenFromRequest(req);
    if (!token) {
        res.status(401).json({ error: 'Unauthorized' });
        return null;
    }
    const sessionPromise = verifyToken(token);
    return { token, sessionPromise };
}

function normalizeMessageRow(row) {
    let parsedContent;
    try {
        parsedContent = JSON.parse(row.content);
    }
    catch {
        parsedContent = null;
    }
    const content = parsedContent && typeof parsedContent === 'object'
        ? parsedContent
        : { type: row.content_type || 'text', text: String(row.content || '') };
    return {
        id: row.id,
        from: row.from_type,
        timestamp: row.created_at,
        content,
        read: Boolean(row.read_at),
    };
}

export function createImappRoutes(channelRuntime, appConfig) {
    const router = Router();
    router.post('/auth/verify', async (req, res) => {
        try {
            const { token, session_token, device_code } = req.body || {};
            const actualToken = token || session_token;
            if (!actualToken) {
                return res.status(400).json({ error: 'token is required' });
            }

            const result = await verifyToken(actualToken, device_code);
            if (result.success) {
                res.json({
                    success: true,
                    valid: true,
                    session_token: result.sessionToken || actualToken,
                    userId: result.userId,
                    user_id: result.userId,
                    userName: result.userName,
                    user_name: result.userName,
                    user: {
                        id: result.userId,
                        name: result.userName,
                        role: result.role,
                    },
                    deviceCode: result.deviceCode || null,
                });
            }
            else {
                res.status(401).json({
                    success: false,
                    valid: false,
                    error: result.error,
                    errorMsg: result.error,
                });
            }
        }
        catch (err) {
            logger.error(`Failed to verify token: ${err}`);
            res.status(500).json({ error: 'Failed to verify token' });
        }
    });

    router.get('/health', (req, res) => {
        res.json({
            status: 'ok',
            online_clients: getOnlineCount(),
            timestamp: Date.now(),
        });
    });

    router.post('/messages/history', async (req, res) => {
        try {
            const authCtx = requireSession(req, res);
            if (!authCtx) return;
            const session = await authCtx.sessionPromise;
            if (!session.success) {
                return res.status(401).json({ error: session.error || 'Invalid token' });
            }

            const { limit = 50, before } = req.body;

            // 从 OpenClaw session 文件读取真实对话记录
            const messages = await getSessionMessages(channelRuntime, appConfig, { limit, before });
            res.json({
                messages,
                has_more: messages.length === limit,
            });
        }
        catch (err) {
            logger.error(`Failed to get messages: ${err}`);
            res.status(500).json({ error: 'Failed to get messages' });
        }
    });

    router.post('/messages/send', async (req, res) => {
        try {
            const authCtx = requireSession(req, res);
            if (!authCtx) return;
            const sessionResult = await authCtx.sessionPromise;
            if (!sessionResult.success) {
                return res.status(401).json({ error: sessionResult.error || 'Invalid token' });
            }
            const session = sessionResult;

            const { content, type = 'text' } = req.body;
            if (!content) {
                return res.status(400).json({ error: 'content is required' });
            }

            const db = await getDb();
            const messageId = randomUUID();
            const now = Date.now();
            const normalizedContent = typeof content === 'object' && content !== null
                ? content
                : { type, text: String(content) };

            await db.run(`
                INSERT INTO messages (id, conversation_id, from_type, from_id, content_type, content, created_at)
                VALUES (?, 'main', 'user', ?, ?, ?, ?)
            `, messageId, session.userId, normalizedContent.type || type, JSON.stringify(normalizedContent), now);

            res.json({ id: messageId, status: 'sent' });
        }
        catch (err) {
            logger.error(`Failed to send message: ${err}`);
            res.status(500).json({ error: 'Failed to send message' });
        }
    });

    router.get('/devices', async (req, res) => {
        try {
            const authCtx = requireSession(req, res);
            if (!authCtx) return;
            const sessionResult = await authCtx.sessionPromise;
            if (!sessionResult.success) {
                return res.status(401).json({ error: sessionResult.error || 'Invalid token' });
            }
            
            const rawDevices = await listDevices(authCtx.token);
            const devices = rawDevices.map((device) => ({
                id: device.id,
                name: device.name,
                last_active: device.last_active,
                is_current: device.is_current,
                deviceCode: device.deviceCode,
            }));
            const currentDeviceCount = await getDeviceCount();
            res.json({ devices, maxDevices: 5, currentDeviceCount });
        }
        catch (err) {
            logger.error(`Failed to get devices: ${err}`);
            res.status(500).json({ error: 'Failed to get devices' });
        }
    });

    router.delete('/devices/:deviceId?', async (req, res) => {
        try {
            const authCtx = requireSession(req, res);
            if (!authCtx) return;
            const sessionResult = await authCtx.sessionPromise;
            if (!sessionResult.success) {
                return res.status(401).json({ error: sessionResult.error || 'Invalid token' });
            }

            const revokeTarget = req.params.deviceId
                || req.body?.deviceId
                || req.body?.token
                || req.query?.deviceId
                || req.query?.token;
            if (!revokeTarget) {
                return res.status(400).json({ success: false, error: 'deviceId or token is required' });
            }
            const result = await revokeToken(revokeTarget);

            if (result.success) {
                res.json(result);
            }
            else {
                res.status(400).json(result);
            }
        }
        catch (err) {
            logger.error(`Failed to revoke device: ${err}`);
            res.status(500).json({ error: 'Failed to revoke device' });
        }
    });

    router.get('/media/:fileId', async (req, res) => {
        try {
            const authCtx = requireSession(req, res);
            if (!authCtx) return;
            const sessionResult = await authCtx.sessionPromise;
            if (!sessionResult.success) {
                return res.status(401).json({ error: sessionResult.error || 'Invalid token' });
            }
            
            const { fileId } = req.params;
            const db = await getDb();
            const media = await db.get(`
                SELECT id, mime_type, storage_path
                FROM media_files
                WHERE id = ?
            `, fileId);
            if (!media || !media.storage_path || !fs.existsSync(media.storage_path)) {
                return res.status(404).json({ error: 'Media not found' });
            }

            const mimeType = media.mime_type || mimeForExt(path.extname(fileId)) || 'application/octet-stream';
            res.setHeader('Content-Type', mimeType);
            res.setHeader('Cache-Control', 'public, max-age=31536000');
            fs.createReadStream(media.storage_path).pipe(res);
        }
        catch (err) {
            logger.error(`Failed to get media: ${err}`);
            res.status(500).json({ error: 'Failed to get media' });
        }
    });

    router.post('/media/upload-url', async (req, res) => {
        try {
            const authCtx = requireSession(req, res);
            if (!authCtx) return;
            const sessionResult = await authCtx.sessionPromise;
            if (!sessionResult.success) {
                return res.status(401).json({ error: sessionResult.error || 'Invalid token' });
            }
            
            const mimeType = (req.body?.mime_type || req.body?.type || 'application/octet-stream').toLowerCase();
            const fileId = `${randomUUID()}.${extForMime(mimeType)}`;
            const type = mimeType.startsWith('image/')
                ? 'image'
                : mimeType.startsWith('audio/')
                    ? 'voice'
                    : mimeType.startsWith('video/')
                        ? 'video'
                        : 'file';
            res.json({
                file_id: fileId,
                upload_url: `/imapp/media/upload/${fileId}`,
                expires_at: Date.now() + 15 * 60 * 1000,
                type,
            });
        }
        catch (err) {
            logger.error(`Failed to create upload URL: ${err}`);
            res.status(500).json({ error: 'Failed to create upload URL' });
        }
    });

    router.post('/media/upload/:fileId', express.raw({ type: '*/*', limit: '500mb' }), async (req, res) => {
        try {
            const authCtx = requireSession(req, res);
            if (!authCtx) return;
            const sessionResult = await authCtx.sessionPromise;
            if (!sessionResult.success) {
                return res.status(401).json({ error: sessionResult.error || 'Invalid token' });
            }
            
            const { fileId } = req.params;
            const mimeType = (req.headers['content-type'] || mimeForExt(path.extname(fileId)) || 'application/octet-stream').split(';')[0].trim();
            const type = mimeType.startsWith('image/')
                ? 'image'
                : mimeType.startsWith('audio/')
                    ? 'voice'
                    : mimeType.startsWith('video/')
                        ? 'video'
                        : 'file';
            const dir = mediaDirForMime(mimeType);
            const storagePath = getMediaPath(path.join(dir, fileId));
            const body = Buffer.isBuffer(req.body) ? req.body : Buffer.from(req.body || []);
            fs.writeFileSync(storagePath, body);

            const db = await getDb();
            await db.run(`
                INSERT OR REPLACE INTO media_files (id, type, filename, mime_type, size, storage_path, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            `, fileId, type, fileId, mimeType, body.length, storagePath, Date.now());

            res.json({
                file_id: fileId,
                url: `/imapp/media/${fileId}`,
                mime_type: mimeType,
                size: body.length,
            });
        }
        catch (err) {
            logger.error(`Failed to upload media by fileId: ${err}`);
            res.status(500).json({ error: 'Failed to upload media' });
        }
    });

    router.post('/media/upload', async (req, res) => {
        try {
            const authCtx = requireSession(req, res);
            if (!authCtx) return;
            const sessionResult = await authCtx.sessionPromise;
            if (!sessionResult.success) {
                return res.status(401).json({ error: sessionResult.error || 'Invalid token' });
            }

            if (!req.body || !req.body.file) {
                return res.status(400).json({ error: 'No file provided' });
            }

            const { file, filename, mimeType } = req.body;
            const ext = extForMime(mimeType || 'application/octet-stream');
            const fileId = `${randomUUID()}.${ext}`;
            const mediaPath = getMediaPath(path.join(mediaDirForMime(mimeType || 'application/octet-stream'), fileId));
            const buffer = Buffer.from(file, 'base64');
            fs.writeFileSync(mediaPath, buffer);
            const db = await getDb();
            await db.run(`
                INSERT OR REPLACE INTO media_files (id, type, filename, mime_type, size, storage_path, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            `,
                fileId,
                (mimeType || '').startsWith('image/')
                    ? 'image'
                    : (mimeType || '').startsWith('audio/')
                        ? 'voice'
                        : (mimeType || '').startsWith('video/')
                            ? 'video'
                            : 'file',
                filename || fileId,
                mimeType || 'application/octet-stream',
                buffer.length,
                mediaPath,
                Date.now()
            );
            logger.info(`Media uploaded: ${fileId}`);

            res.json({
                file_id: fileId,
                url: `/imapp/media/${fileId}`,
                mime_type: mimeType || 'application/octet-stream',
                size: buffer.length,
            });
        }
        catch (err) {
            logger.error(`Failed to upload media: ${err}`);
            res.status(500).json({ error: 'Failed to upload media' });
        }
    });

    router.post('/fcm/register', async (req, res) => {
        try {
            const authCtx = requireSession(req, res);
            if (!authCtx) return;
            const sessionResult = await authCtx.sessionPromise;
            if (!sessionResult.success) {
                return res.status(401).json({ error: sessionResult.error || 'Invalid token' });
            }
            const session = sessionResult;
            
            const fcmToken = req.body?.fcm_token;
            const deviceName = req.body?.device_name || session.userName || 'Android Device';
            if (!fcmToken) {
                return res.status(400).json({ error: 'fcm_token is required' });
            }

            const now = Date.now();
            const db = await getDb();
            await db.run(`
                INSERT INTO fcm_tokens (id, user_id, fcm_token, device_name, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(fcm_token) DO UPDATE SET
                    user_id = excluded.user_id,
                    device_name = excluded.device_name,
                    updated_at = excluded.updated_at
            `, randomUUID(), session.userId, fcmToken, deviceName, now, now);

            res.json({ success: true });
        }
        catch (err) {
            logger.error(`Failed to register FCM token: ${err}`);
            res.status(500).json({ error: 'Failed to register FCM token' });
        }
    });

    router.get('/ws', (req, res) => {
        res.status(404).json({ error: 'WebSocket should connect to /imapp/ws' });
    });

    return router;
}

/**
 * 从 OpenClaw session JSONL 文件读取真实对话记录
 */
async function getSessionMessages(runtime, cfg, { limit = 50, before }) {
    const fs = await import('node:fs');
    const path = await import('node:path');

    if (!runtime || !cfg) {
        // fallback 到本地 SQLite
        const db = await getDb();
        const rows = await db.all('SELECT * FROM messages WHERE conversation_id = ? ORDER BY created_at DESC LIMIT ?', 'main', limit);
        return rows.map(normalizeMessageRow).reverse();
    }

    try {
        const storePath = runtime.session.resolveStorePath(cfg.session?.store, { agentId: 'main' });
        // 找到最新的 session 文件
        const sessionDir = path.dirname(storePath);
        if (!fs.existsSync(sessionDir)) {
            return [];
        }

        const files = fs.readdirSync(sessionDir)
            .filter(f => f.endsWith('.jsonl'))
            .map(f => ({ name: f, mtime: fs.statSync(path.join(sessionDir, f)).mtimeMs }))
            .sort((a, b) => b.mtime - a.mtime);

        if (files.length === 0) return [];

        // 只读取最新的 session 文件（当前活跃对话）
        const latestFile = files[0];
        const filePath = path.join(sessionDir, latestFile.name);
        const content = fs.readFileSync(filePath, 'utf-8');
        const allMessages = [];
        for (const line of content.split('\n')) {
                if (!line.trim()) continue;
                try {
                    const obj = JSON.parse(line);
                    if (obj.type !== 'message') continue;
                    const msg = obj.message;
                    if (!msg || !['user', 'assistant'].includes(msg.role)) continue;

                    const contentList = msg.content;
                    let text = '';
                    if (typeof contentList === 'string') {
                        text = contentList;
                    } else if (Array.isArray(contentList)) {
                        // 只取 text 类型，跳过 thinking/toolCall/toolResult
                        const textParts = contentList.filter(c => c.type === 'text' && c.text).map(c => c.text);
                        text = textParts.join('\n');
                    }
                    if (!text.trim()) continue;

                    // 过滤 OpenClaw 内部消息（心跳、系统消息、relevant-memories 等）
                    const trimmed = text.trim();
                    if (trimmed.startsWith('HEARTBEAT_OK') || trimmed === 'NO_REPLY') continue;
                    if (trimmed.startsWith('Read HEARTBEAT.md')) continue;
                    if (trimmed.startsWith('<relevant-memories>') || trimmed.startsWith('[UNTRUSTED DATA')) continue;
                    if (/^Conversation info \(untrusted metadata\)/.test(trimmed)) continue;
                    if (/^System:/.test(trimmed)) continue;
                    if (/^\[Fri|Mon|Tue|Wed|Thu|Sat|Sun 2026/.test(trimmed)) continue;
                    // 跳过纯工具调用的 assistant 消息（没有实际文本内容，只有 thinking）
                    if (msg.role === 'assistant' && trimmed.length < 2) continue;

                    const ts = obj.timestamp ? new Date(obj.timestamp).getTime() : 0;

                    allMessages.push({
                        id: obj.id || `sess-${Date.now()}-${Math.random().toString(36).slice(2,6)}`,
                        from: msg.role === 'assistant' ? 'agent' : 'user',
                        timestamp: ts,
                        content: { type: 'text', text: text.trim() },
                        read: true,
                    });
                } catch {}
            }

        // 按时间排序去重
        allMessages.sort((a, b) => a.timestamp - b.timestamp);

        // 过滤 before
        if (before) {
            const beforeTs = typeof before === 'number' ? before : parseInt(before) || 0;
            const idx = allMessages.findIndex(m => m.timestamp >= beforeTs);
            if (idx > 0) allMessages.splice(0, idx);
        }

        // 返回最新的 limit 条
        return allMessages.slice(-limit);
    } catch (err) {
        logger.error(`getSessionMessages error: ${err}`);
        // fallback
        const db = await getDb();
        const rows = await db.all('SELECT * FROM messages WHERE conversation_id = ? ORDER BY created_at DESC LIMIT ?', 'main', limit);
        return rows.map(normalizeMessageRow).reverse();
    }
}
