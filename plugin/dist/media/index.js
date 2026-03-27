/**
 * OpenClaw IMApp 媒体处理模块
 * MIME 类型检测与文件扩展名映射
 */
const MIME_TO_EXT = {
    // 图片
    'image/jpeg': 'jpg',
    'image/jpg': 'jpg',
    'image/png': 'png',
    'image/gif': 'gif',
    'image/webp': 'webp',
    'image/bmp': 'bmp',
    'image/svg+xml': 'svg',
    'image/tiff': 'tiff',
    // 音频
    'audio/mpeg': 'mp3',
    'audio/mp3': 'mp3',
    'audio/ogg': 'ogg',
    'audio/wav': 'wav',
    'audio/webm': 'weba',
    'audio/aac': 'aac',
    'audio/flac': 'flac',
    'audio/amr': 'amr',
    // 视频
    'video/mp4': 'mp4',
    'video/webm': 'webm',
    'video/ogg': 'ogv',
    'video/quicktime': 'mov',
    'video/x-msvideo': 'avi',
    'video/3gpp': '3gp',
    // 文档
    'application/pdf': 'pdf',
    'application/zip': 'zip',
    'application/x-zip-compressed': 'zip',
    'application/x-rar-compressed': 'rar',
    'application/x-7z-compressed': '7z',
    'application/msword': 'doc',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'docx',
    'application/vnd.ms-excel': 'xls',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'xlsx',
    'application/vnd.ms-powerpoint': 'ppt',
    'application/vnd.openxmlformats-officedocument.presentationml.presentation': 'pptx',
    'text/plain': 'txt',
    'text/csv': 'csv',
    'application/json': 'json',
    // 二进制兜底
    'application/octet-stream': 'bin',
};
const EXT_TO_MIME = {};
for (const [mime, ext] of Object.entries(MIME_TO_EXT)) {
    if (!EXT_TO_MIME[ext])
        EXT_TO_MIME[ext] = mime;
}
/**
 * 根据 MIME 类型获取文件扩展名（不含点）
 */
export function extForMime(mimeType) {
    // 去掉参数部分，如 "image/png; charset=utf-8" -> "image/png"
    const base = mimeType.split(';')[0].trim().toLowerCase();
    return MIME_TO_EXT[base] ?? 'bin';
}
/**
 * 根据文件扩展名获取 MIME 类型
 */
export function mimeForExt(ext) {
    return EXT_TO_MIME[ext.toLowerCase().replace(/^\./, '')] ?? 'application/octet-stream';
}
/**
 * 根据 MIME 类型判断媒体分类目录
 */
export function mediaDirForMime(mimeType) {
    const base = mimeType.split(';')[0].trim().toLowerCase();
    if (base.startsWith('image/'))
        return 'images';
    if (base.startsWith('audio/'))
        return 'voices';
    if (base.startsWith('video/'))
        return 'videos';
    return 'files';
}
/**
 * 判断是否为有效的媒体类型
 */
export function isValidMediaMime(mimeType) {
    const base = mimeType.split(';')[0].trim().toLowerCase();
    return (base.startsWith('image/') ||
        base.startsWith('audio/') ||
        base.startsWith('video/') ||
        MIME_TO_EXT[base] !== undefined);
}
/**
 * 根据媒体类型字符串（image/voice/video/file）获取目录
 */
export function mediaDirForType(type) {
    switch (type) {
        case 'image': return 'images';
        case 'voice':
        case 'audio': return 'voices';
        case 'video': return 'videos';
        default: return 'files';
    }
}
//# sourceMappingURL=index.js.map