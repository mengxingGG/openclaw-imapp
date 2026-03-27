/**
 * OpenClaw IMApp 数据库模块
 * 使用 better-sqlite3，启用 WAL 模式
 */
import Database from 'better-sqlite3';
/**
 * 初始化数据库
 */
export declare function initDatabase(): Database.Database;
/**
 * 获取数据库实例
 */
export declare function getDb(): Database.Database;
/**
 * 关闭数据库
 */
export declare function closeDatabase(): void;
/**
 * 获取媒体存储路径
 */
export declare function getMediaPath(type: 'images' | 'voices' | 'videos' | 'files'): string;
//# sourceMappingURL=index.d.ts.map