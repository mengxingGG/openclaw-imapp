/**
 * OpenClaw IMApp 日志工具
 */
type LogLevel = 'debug' | 'info' | 'warn' | 'error';
export declare const logger: {
    setLevel: (level: LogLevel) => void;
    debug: (...args: any[]) => void;
    info: (...args: any[]) => void;
    warn: (...args: any[]) => void;
    error: (...args: any[]) => void;
};
export {};
//# sourceMappingURL=logger.d.ts.map