/**
 * OpenClaw IMApp 日志工具
 */
const LOG_PREFIX = '[imapp]';
const LOG_LEVELS = {
    debug: 0,
    info: 1,
    warn: 2,
    error: 3,
};
let currentLevel = process.env.IMAPP_LOG_LEVEL || 'info';
function formatTime() {
    return new Date().toISOString();
}
function log(level, ...args) {
    if (LOG_LEVELS[level] < LOG_LEVELS[currentLevel]) {
        return;
    }
    const prefix = `${formatTime()} ${LOG_PREFIX} [${level.toUpperCase()}]`;
    switch (level) {
        case 'error':
            console.error(prefix, ...args);
            break;
        case 'warn':
            console.warn(prefix, ...args);
            break;
        default:
            console.log(prefix, ...args);
    }
}
export const logger = {
    setLevel: (level) => {
        currentLevel = level;
    },
    debug: (...args) => log('debug', ...args),
    info: (...args) => log('info', ...args),
    warn: (...args) => log('warn', ...args),
    error: (...args) => log('error', ...args),
};
//# sourceMappingURL=logger.js.map