package com.profer.pocket;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * agent_event 载荷 → 通知内容（宽松容错）
 *
 * payload 结构：{ kind: 'profer_event', event: { type, request?, ... } }
 * 事件类型与 useGlobalAgentListeners 判定一致；同时兼容设计文档命名的简写
 * （ask_user / exit_plan_mode）与实际服务端广播的完整名（ask_user_request /
 * exit_plan_mode_request），保证无论哪端事件都能命中。
 *
 * 容错原则：任何字段缺失/类型不符/解析异常 → 返回 null（无需提醒），绝不抛异常。
 */
public final class MessageEventParser {

    /** 解析结果：通知标题 + 正文 + 事件类型（供通知点击导航透传给前端） */
    public static final class NotifyInfo {
        public final String title;
        public final String body;
        /** 原始事件类型（permission_request / ask_user_request / exit_plan_mode_request / run_completed） */
        public final String type;

        public NotifyInfo(String title, String body, String type) {
            this.title = title;
            this.body = body;
            this.type = type;
        }
    }

    private MessageEventParser() {
    }

    /** 解析 agent_event 载荷；返回 null = 无需提醒 */
    public static NotifyInfo parse(String sessionId, JSONObject payload,
                                   Map<String, String> sessionTitles) {
        if (payload == null) return null;
        try {
            JSONObject event = payload.optJSONObject("event");
            if (event == null) return null;
            String type = event.optString("type", "");
            if (type.isEmpty()) return null;
            String title = sessionTitles == null ? null : sessionTitles.get(sessionId);
            switch (type) {
                case "permission_request":
                    return permissionRequest(event, title, type);
                case "ask_user":
                case "ask_user_request":
                    return askUser(event, title, type);
                case "exit_plan_mode":
                case "exit_plan_mode_request":
                    return new NotifyInfo(
                            "Agent 计划待审批",
                            "Agent 已完成计划，等待你的审批",
                            type);
                case "run_completed":
                    return runCompleted(event, title, type);
                default:
                    // 未知事件类型：忽略
                    return null;
            }
        } catch (Exception e) {
            // 任何异常都忽略，绝不崩溃
            return null;
        }
    }

    /** 权限请求：标题「需要权限确认」，正文含工具名与（可选的）会话名 */
    private static NotifyInfo permissionRequest(JSONObject event, String title, String type) {
        String toolName = null;
        JSONObject request = event.optJSONObject("request");
        if (request != null) {
            toolName = request.optString("toolName", null);
        }
        String body;
        if (toolName != null && !toolName.isEmpty()) {
            body = "Agent 请求使用工具: " + toolName + sessionSuffix(title);
        } else {
            body = "Agent 需要你的权限确认" + sessionSuffix(title);
        }
        return new NotifyInfo("需要权限确认", body, type);
    }

    /** 提问：标题「Agent 需要你的输入」，正文取第一个问题 */
    private static NotifyInfo askUser(JSONObject event, String title, String type) {
        String question = null;
        JSONObject request = event.optJSONObject("request");
        if (request != null) {
            JSONArray questions = request.optJSONArray("questions");
            if (questions != null && questions.length() > 0) {
                JSONObject first = questions.optJSONObject(0);
                if (first != null) {
                    question = first.optString("question", null);
                }
            }
        }
        String body = (question != null && !question.isEmpty())
                ? question
                : "Agent 有问题需要你回答";
        return new NotifyInfo("Agent 需要你的输入", body, type);
    }

    /** 运行完成：用户主动停止或仍有后台任务在飞 → 不提醒（对齐桌面语义） */
    private static NotifyInfo runCompleted(JSONObject event, String title, String type) {
        if (event.optBoolean("stoppedByUser", false)) return null;
        if (event.optBoolean("backgroundTasksPending", false)) return null;
        String body = (title != null && !title.isEmpty())
                ? title + " 运行完成"
                : "会话运行完成";
        return new NotifyInfo("会话已完成", body, type);
    }

    /** 会话名后缀；无标题时省略会话名 */
    private static String sessionSuffix(String title) {
        return (title != null && !title.isEmpty()) ? "（会话：" + title + "）" : "";
    }
}
