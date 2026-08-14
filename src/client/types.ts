/**
 * Client 层对外类型入口。
 *
 * 当前为本子任务的最小 re-export：把 ws-client.ts 中导出的类型整理为
 * 客户端统一类型入口，不发明新类型。
 */
export type {
  AgentWorkflowEvent,
  ChatWorkflowEvent,
  WsClientOptions,
} from './ws-client'
export { WsClient, defaultWsUrl } from './ws-client'
