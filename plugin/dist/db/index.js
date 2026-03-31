/**
 * OpenClaw IMApp 数据库模块
 * 使用 better-sqlite3，启用 WAL 模式
 */
import { createClient } from '@libsql/client';
import os from 'node:os';
import path from 'node:path';
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { logger } from '../util/logger.js';
const DB_DIR = process.env.IMAPP_DATA_DIR || (process.platform === 'win32'
    ? path.join(process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local'), 'openclaw-imapp')
    : '/var/lib/openclaw-imapp');
const DB_PATH = path.join(DB_DIR, 'imapp.db');
const MEDIA_DIR = path.join(DB_DIR, 'media');
const MESSAGE_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;
let dbClient = null;

class LibsqlWrapper {
    constructor(client) {
        this.client = client;
    }
    
    async exec(sql) {
        await this.client.execute(sql);
    }
    
    async run(sql, ...args) {
        await this.client.execute({ sql, args });
    }
    
    async get(sql, ...args) {
        const rs = await this.client.execute({ sql, args });
        if (rs.rows.length === 0) return undefined;
        const row = rs.rows[0];
        const obj = {};
        for (let i = 0; i < rs.columns.length; i++) {
            obj[rs.columns[i]] = row[i];
        }
        return obj;
    }
    
    async all(sql, ...args) {
        const rs = await this.client.execute({ sql, args });
        return rs.rows.map(row => {
            const obj = {};
            for (let i = 0; i < rs.columns.length; i++) {
                obj[rs.columns[i]] = row[i];
            }
            return obj;
        });
    }
    
    async close() {
        this.client.close();
    }
}

/**
 * 初始化数据库
 */
export async function initDatabase() {
    // 确保目录存在
    if (!fs.existsSync(DB_DIR)) {
        fs.mkdirSync(DB_DIR, { recursive: true });
        logger.info(`Created database directory: ${DB_DIR}`);
    }
    // 创建媒体目录
    for (const type of ['images', 'voices', 'videos', 'files']) {
        const dir = path.join(MEDIA_DIR, type);
        if (!fs.existsSync(dir)) {
            fs.mkdirSync(dir, { recursive: true });
        }
    }
    
    const client = createClient({
        url: pathToFileURL(DB_PATH).href,
    });
    dbClient = new LibsqlWrapper(client);

    // 创建表
    await createTables(dbClient);
    logger.info(`Database initialized at ${DB_PATH}`);
    return dbClient;
}
/**
 * 获取数据库实例
 */
export async function getDb() {
    if (!dbClient) {
        return await initDatabase();
    }
    return dbClient;
}
/**
 * 关闭数据库
 */
export async function closeDatabase() {
    if (dbClient) {
        await dbClient.close();
        dbClient = null;
        logger.info('Database closed');
    }
}
/**
 * 获取媒体存储路径
 */
export function getMediaPath(type) {
    return path.join(MEDIA_DIR, type);
}

export async function pruneExpiredMessages(now = Date.now()) {
    const db = await getDb();
    const cutoff = now - MESSAGE_RETENTION_MS;

    // 1. 收集即将被删除的消息所关联的 media_file ids
    const orphanedMediaRows = await db.all(
        'SELECT id, storage_path FROM media_files WHERE message_id NOT IN (SELECT id FROM messages) AND message_id IS NOT NULL'
    );
    let mediaFreed = 0;
    for (const media of orphanedMediaRows) {
        try {
            if (media.storage_path && fs.existsSync(media.storage_path)) {
                fs.unlinkSync(media.storage_path);
            }
            await db.run('DELETE FROM media_files WHERE id = ?', media.id);
            mediaFreed++;
        } catch (e) {
            logger.warn(`Failed to prune media ${media.id}: ${e.message}`);
        }
    }

    // 2. 删除过期消息
    const row = await db.get('SELECT COUNT(*) AS c FROM messages WHERE created_at < ?', cutoff);
    const deleted = Number(row?.c || 0);
    if (deleted > 0) {
        await db.run('DELETE FROM messages WHERE created_at < ?', cutoff);
        logger.info(`Pruned ${deleted} expired messages, freed ${mediaFreed} orphaned media files`);
    }
    return deleted;
}
/**
 * 创建所有表
 */
async function createTables(db) {
    // 用户表
    await db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      role TEXT NOT NULL DEFAULT 'user',
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    )
  `);
    // 设备表
    await db.exec(`
    CREATE TABLE IF NOT EXISTS devices (
      id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL,
      name TEXT NOT NULL,
      device_fingerprint TEXT,
      last_active INTEGER,
      created_at INTEGER NOT NULL,
      FOREIGN KEY (user_id) REFERENCES users(id)
    )
  `);
    await db.exec(`CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id)`);
    // 会话表
    await db.exec(`
    CREATE TABLE IF NOT EXISTS sessions (
      id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL,
      device_id TEXT,
      token_hash TEXT NOT NULL UNIQUE,
      raw_token TEXT,
      expires_at INTEGER,
      last_used INTEGER,
      created_at INTEGER NOT NULL
    )
  `);
    // 兼容旧表：若 raw_token 列不存在则添加
    try {
        await db.exec(`ALTER TABLE sessions ADD COLUMN raw_token TEXT`);
    }
    catch {
    }
    try {
        await db.exec(`ALTER TABLE sessions ADD COLUMN device_name TEXT`);
    }
    catch {
    }
    try {
        await db.exec(`ALTER TABLE sessions ADD COLUMN device_code TEXT`);
    }
    catch {
    }
    try {
        await db.exec(`ALTER TABLE sessions ADD COLUMN last_active_at INTEGER`);
    }
    catch {
    }
    await db.exec(`CREATE INDEX IF NOT EXISTS idx_sessions_token ON sessions(token_hash)`);
    await db.exec(`CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id)`);
    await db.exec(`DROP TABLE IF EXISTS login_requests`);
    // 消息表
    await db.exec(`
    CREATE TABLE IF NOT EXISTS messages (
      id TEXT PRIMARY KEY,
      conversation_id TEXT NOT NULL,
      from_type TEXT NOT NULL,
      from_id TEXT,
      content_type TEXT NOT NULL,
      content TEXT NOT NULL,
      context_token TEXT,
      read_at INTEGER,
      created_at INTEGER NOT NULL
    )
  `);
    await db.exec(`CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id, created_at DESC)`);
    await db.exec(`CREATE INDEX IF NOT EXISTS idx_messages_context ON messages(context_token)`);
    await db.exec(`CREATE INDEX IF NOT EXISTS idx_messages_created_at ON messages(created_at)`);
    // 媒体文件表
    await db.exec(`
    CREATE TABLE IF NOT EXISTS media_files (
      id TEXT PRIMARY KEY,
      message_id TEXT,
      type TEXT NOT NULL,
      filename TEXT,
      mime_type TEXT,
      size INTEGER NOT NULL,
      width INTEGER,
      height INTEGER,
      duration_ms INTEGER,
      storage_path TEXT NOT NULL,
      encryption_key TEXT,
      created_at INTEGER NOT NULL,
      FOREIGN KEY (message_id) REFERENCES messages(id)
    )
  `);
    await db.exec(`CREATE INDEX IF NOT EXISTS idx_media_message ON media_files(message_id)`);
    // FCM token 表（用于离线推送）
    await db.exec(`
    CREATE TABLE IF NOT EXISTS fcm_tokens (
      id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL,
      fcm_token TEXT NOT NULL UNIQUE,
      device_name TEXT,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL,
      FOREIGN KEY (user_id) REFERENCES users(id)
    )
  `);
    await db.exec(`CREATE INDEX IF NOT EXISTS idx_fcm_user ON fcm_tokens(user_id)`);
    await db.exec(`
    CREATE TABLE IF NOT EXISTS conversation_contexts (
      id TEXT PRIMARY KEY,
      conversation_id TEXT NOT NULL DEFAULT 'main',
      last_message_id TEXT,
      message_count INTEGER DEFAULT 0,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    )
  `);
    // 插入默认用户（如果不存在）
    const now = Date.now();
    await db.run(`
    INSERT OR IGNORE INTO users (id, name, role, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?)
  `, 'user-001', '罗', 'owner', now, now);
    // 创建默认对话上下文
    await db.run(`
    INSERT OR IGNORE INTO conversation_contexts (id, conversation_id, created_at, updated_at)
    VALUES (?, ?, ?, ?)
  `, 'ctx-main', 'main', now, now);
    logger.info('Database tables created/verified');
}
//# sourceMappingURL=index.js.map
