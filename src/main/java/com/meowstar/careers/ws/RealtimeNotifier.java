package com.meowstar.careers.ws;

import com.meowstar.careers.model.application.ApplicationView;
import com.meowstar.careers.model.chat.Message;
import com.meowstar.careers.model.interview.InterviewView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 实时推送入口（对齐 Rust 版 ws.rs 的 deliver_message / notify_interview /
 * notify_application / notify_conversation_read）：REST 处理器在状态变化后调用，
 * 把事件推给相关用户的所有在线连接。
 */
@Component
public class RealtimeNotifier {

    private final RealtimeHub hub;
    private final WsEvents events;

    public RealtimeNotifier(RealtimeHub hub, WsEvents events) {
        this.hub = hub;
        this.events = events;
    }

    /** 消息入库后推给参与双方（发送者多端回执 + 接收者投递）。 */
    public void deliverMessage(Message message) {
        String payload = events.messageEvent(message);
        hub.deliver(message.senderId(), payload);
        hub.deliver(message.recipientId(), payload);
    }

    /** 面试状态变化：把 interview-updated 推送给双方参与者的所有在线连接。 */
    public void notifyInterview(InterviewView view) {
        String payload = events.interviewUpdated(view);
        hub.deliver(view.interviewerId(), payload);
        hub.deliver(view.intervieweeId(), payload);
    }

    /** 投递状态变化：推送给求职者与该企业全部招聘者（含操作者）。 */
    public void notifyApplication(ApplicationView view, List<UUID> extraRecipients) {
        String payload = events.applicationUpdated(view);
        List<UUID> seen = new ArrayList<>();
        seen.add(view.seekerId());
        hub.deliver(view.seekerId(), payload);
        if (extraRecipients == null) {
            return;
        }
        for (UUID userId : extraRecipients) {
            if (userId != null && !seen.contains(userId)) {
                seen.add(userId);
                hub.deliver(userId, payload);
            }
        }
    }

    /** 会话已读：告知对端（readBy 是我，用于消息已读状态同步）。 */
    public void notifyConversationRead(UUID conversationId, UUID reader, UUID other) {
        hub.deliver(other, events.conversationRead(conversationId, reader));
    }
}
