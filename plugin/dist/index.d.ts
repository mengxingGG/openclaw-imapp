/**
 * OpenClaw IMApp 插件入口
 *
 * 专属 App 即时通讯系统，支持：
 * - 扫码登录
 * - WebSocket 实时消息
 * - 媒体传输
 * - 本地存储
 * - 与 OpenClaw Agent 深度集成
 */
import type { ChannelPlugin, OpenClawPluginApi } from 'openclaw/plugin-sdk';
interface ImappAccount {
    accountId: string;
    name: string;
    enabled: boolean;
    configured: boolean;
    userId?: string;
}
export declare const imappPlugin: ChannelPlugin<ImappAccount>;
declare const pluginDefinition: {
    id: string;
    name: string;
    description: string;
    register(api: OpenClawPluginApi): void;
};
export default pluginDefinition;
export declare function register(api?: OpenClawPluginApi): ChannelPlugin<ImappAccount>;
//# sourceMappingURL=index.d.ts.map