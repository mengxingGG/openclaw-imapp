/**
 * OpenClaw IMApp 认证模块
 * 处理 Token 登录、会话管理、设备绑定
 */
import { randomUUID } from 'node:crypto';
import { createHash } from 'node:crypto';
import { getDb } from '../db/index.js';
import { logger } from '../util/logger.js';

const SESSION_EXPIRY_MS = 365 * 24 * 60 * 60 * 1000; // 1 年
const MAX_TOKENS = 5; // 最多5个设备

// 运行时可配置的 baseUrl
let runtimeBaseUrl = null;

export function setBaseUrl(url) {
    runtimeBaseUrl = url;
}

export function getBaseUrl() {
    return runtimeBaseUrl || process.env.IMAPP_BASE_URL || 'http://127.0.0.1:3100';
}

/**
 * 创建设备 Token
 * @param deviceName 设备名称
 * @param deviceId 设备ID（可选）
 * @returns { token, deviceCode }
 */
export async function createToken(deviceName, deviceId = null) {
    const db = await getDb();
    const now = Date.now();

    const count = await db.get('SELECT COUNT(*) as c FROM sessions');
    if (count.c >= MAX_TOKENS) {
        return { success: false, error: `设备数量已达上限（${MAX_TOKENS}个），请先撤销不需要的设备` };
    }

    const token = `imapp_${randomUUID().replace(/-/g, '')}`;
    const deviceCode = randomUUID().substring(0, 8).toUpperCase();
    const finalUserId = deviceId || `device_${randomUUID().substring(0, 8)}`;

    const user = await db.get('SELECT * FROM users WHERE id = ?', finalUserId);
    if (!user) {
        await db.run(`
            INSERT INTO users (id, name, role, created_at, updated_at)
            VALUES (?, ?, 'device', ?, ?)
        `, finalUserId, deviceName, now, now);
    }

    const sessionId = randomUUID();
    const tokenHash = createHash('sha256').update(token).digest('hex');
    const expiresAt = now + SESSION_EXPIRY_MS;
    await db.run(`
        INSERT INTO sessions (
            id, user_id, device_id, raw_token, token_hash,
            device_name, device_code, created_at, last_active_at, last_used, expires_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `,
        sessionId,
        finalUserId,
        deviceId,
        token,
        tokenHash,
        deviceName,
        deviceCode,
        now,
        now,
        now,
        expiresAt
    );

    logger.info(`Token created for device: ${deviceName}, deviceCode: ${deviceCode}`);

    return {
        success: true,
        valid: true,
        token,
        sessionToken: token,
        deviceCode,
        deviceId: finalUserId,
        deviceName,
        userId: finalUserId,
        userName: deviceName,
        user: {
            id: finalUserId,
            name: deviceName,
            role: 'device',
        },
        message: `Token 创建成功！设备码：${deviceCode}`,
    };
}

/**
 * 验证 Token 并绑定设备
 * @param token 登录Token
 * @param deviceCode 设备码（用于绑定验证）
 * @returns 验证结果
 */
export async function verifyToken(token, deviceCode = null) {
    const db = await getDb();
    const now = Date.now();

    const session = await db.get(`
        SELECT s.*, u.name as user_name, u.role 
        FROM sessions s 
        JOIN users u ON s.user_id = u.id 
        WHERE s.raw_token = ?
    `, token);

    if (!session) {
        return { success: false, error: 'Token 无效' };
    }

    if (session.expires_at && session.expires_at < now) {
        return { success: false, error: 'Token 已过期' };
    }

    if (deviceCode && session.device_code !== deviceCode) {
        return { success: false, error: '设备码不匹配' };
    }

    await db.run('UPDATE sessions SET last_active_at = ?, last_used = ? WHERE id = ?', now, now, session.id);

    logger.info(`Token verified for device: ${session.device_name}`);

    return {
        success: true,
        valid: true,
        sessionToken: token,
        userId: session.user_id,
        userName: session.user_name,
        role: session.role,
        deviceId: session.device_id || session.user_id,
        deviceCode: session.device_code,
        user: {
            id: session.user_id,
            name: session.user_name,
            role: session.role,
        },
    };
}

export async function verifySession(token) {
    const result = await verifyToken(token);
    if (!result.success) {
        return {
            valid: false,
            error: result.error,
        };
    }
    return {
        valid: true,
        userId: result.userId,
        userName: result.userName,
        role: result.role,
        deviceId: result.deviceId,
        deviceCode: result.deviceCode,
        sessionToken: result.sessionToken,
    };
}

/**
 * 获取所有已登录设备
 */
export async function listDevices(currentToken = null) {
    const db = await getDb();
    const currentSession = currentToken ? await db.get('SELECT device_code FROM sessions WHERE raw_token = ?', currentToken) : null;
    const devices = await db.all(`
        SELECT s.id, s.device_name, s.device_code, s.created_at, s.last_active_at, u.name as user_name
        FROM sessions s 
        JOIN users u ON s.user_id = u.id 
        ORDER BY s.last_active_at DESC
    `);

    return devices.map(d => ({
        id: d.id,
        name: d.device_name,
        deviceName: d.device_name,
        deviceCode: d.device_code,
        createdAt: d.created_at,
        created_at: d.created_at,
        lastActive: d.last_active_at ?? d.created_at,
        lastActiveAt: d.last_active_at ?? d.created_at,
        last_active: d.last_active_at ?? d.created_at,
        isCurrent: Boolean(currentSession?.device_code && currentSession.device_code === d.device_code),
        is_current: Boolean(currentSession?.device_code && currentSession.device_code === d.device_code),
    }));
}

/**
 * 撤销设备 Token
 */
export async function revokeToken(tokenOrDeviceCode) {
    const db = await getDb();

    let session = await db.get('SELECT * FROM sessions WHERE raw_token = ?', tokenOrDeviceCode);
    if (!session) {
        session = await db.get('SELECT * FROM sessions WHERE device_code = ?', tokenOrDeviceCode);
    }
    
    if (!session) {
        return { success: false, error: 'Token 或设备码不存在' };
    }

    await db.run('DELETE FROM sessions WHERE id = ?', session.id);

    logger.info(`Token revoked for device: ${session.device_name}`);

    return { success: true, message: `已撤销设备：${session.device_name}` };
}

/**
 * 获取当前设备数量
 */
export async function getDeviceCount() {
    const db = await getDb();
    const count = await db.get('SELECT COUNT(*) as c FROM sessions');
    return count.c;
}

export function getFcmServerKey() {
    return process.env.IMAPP_FCM_SERVER_KEY || '';
}
