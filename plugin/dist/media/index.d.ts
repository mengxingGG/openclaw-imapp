/**
 * OpenClaw IMApp 媒体处理模块
 * MIME 类型检测与文件扩展名映射
 */
/**
 * 根据 MIME 类型获取文件扩展名（不含点）
 */
export declare function extForMime(mimeType: string): string;
/**
 * 根据文件扩展名获取 MIME 类型
 */
export declare function mimeForExt(ext: string): string;
/**
 * 根据 MIME 类型判断媒体分类目录
 */
export declare function mediaDirForMime(mimeType: string): 'images' | 'voices' | 'videos' | 'files';
/**
 * 判断是否为有效的媒体类型
 */
export declare function isValidMediaMime(mimeType: string): boolean;
/**
 * 根据媒体类型字符串（image/voice/video/file）获取目录
 */
export declare function mediaDirForType(type: string): 'images' | 'voices' | 'videos' | 'files';
//# sourceMappingURL=index.d.ts.map