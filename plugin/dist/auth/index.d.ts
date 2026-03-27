/**
 * OpenClaw IMApp 认证模块
 * 处理扫码登录、会话管理
 */
export declare function setBaseUrl(url: string): void;
export declare function getBaseUrl(): string;
export declare function getFcmServerKey(): string | null;
export declare function setFcmServerKey(key: string): void;
export interface QrCodeResult {
    sessionKey: string;
    qrUrl: string;
    qrImage: string;
    expiresAt: number;
}
export interface PollResult {
    status: 'waiting' | 'scanned' | 'confirmed' | 'expired';
    message: string;
    sessionToken?: string;
    user?: {
        id: string;
        name: string;
        role: string;
    };
}
/**
 * 生成登录二维码
 */
export declare function generateQrCode(deviceName: string, deviceId?: string): Promise<QrCodeResult>;
/**
 * 轮询登录状态
 */
export declare function pollLoginStatus(sessionKey: string): PollResult;
/**
 * 扫描二维码（管理员操作）
 */
export declare function scanQrCode(qrToken: string, adminUserId: string): boolean;
/**
 * 确认登录（管理员操作）
 */
export declare function confirmLogin(qrToken: string, adminUserId: string): {
    success: boolean;
    sessionToken?: string;
    error?: string;
};
/**
 * 验证会话
 */
export declare function verifySession(token: string): {
    valid: boolean;
    userId?: string;
    deviceId?: string;
};
/**
 * 获取用户的所有设备
 */
export declare function getUserDevices(userId: string): any[];
/**
 * 注销设备
 */
export declare function revokeDevice(userId: string, deviceId: string): boolean;
/**
 * 清理过期的登录请求和会话
 */
export declare function cleanupExpired(): void;
//# sourceMappingURL=index.d.ts.map