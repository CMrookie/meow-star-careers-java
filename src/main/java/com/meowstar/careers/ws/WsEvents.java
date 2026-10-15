package com.meowstar.careers.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meowstar.careers.model.application.ApplicationView;
import com.meowstar.careers.model.chat.Message;
import com.meowstar.careers.model.interview.InterviewView;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * WebSocket 报文构造（与 Rust 版 ws.rs 的下行 JSON 完全一致）：
 * <pre>
 * {"type":"message","conversationId":"...","message":{...}}
 * {"type":"ack","conversationId":"..."}
 * {"type":"pong"}
 * {"type":"error","code":"...","message":"..."}
 * {"type":"interview-signal","interviewId":"...","from":"...","payload":{...}}
 * {"type":"interview-updated","interview":{...}}
 * {"type":"application-updated","application":{...}}
 * {"type":"conversation-read","conversationId":"...","userId":"..."}
 * </pre>
 */
@Component
public class WsEvents {

    private final ObjectMapper mapper;

    public WsEvents(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 通用事件：把 type 与附加字段合成一个 JSON 对象。 */
    public String event(String type, ObjectNode extra) {
        ObjectNode node = extra == null ? mapper.createObjectNode() : extra;
        node.put("type", type);
        return node.toString();
    }

    public String pong() {
        return event("pong", mapper.createObjectNode());
    }

    public String ack(UUID conversationId) {
        ObjectNode node = mapper.createObjectNode();
        node.put("conversationId", conversationId.toString());
        return event("ack", node);
    }

    public String error(String code, String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("code", code);
        node.put("message", message);
        return event("error", node);
    }

    public String messageEvent(Message message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("conversationId", message.conversationId().toString());
        node.set("message", mapper.valueToTree(message));
        return event("message", node);
    }

    public String interviewSignal(UUID interviewId, UUID from, Object payload) {
        ObjectNode node = mapper.createObjectNode();
        node.put("interviewId", interviewId.toString());
        node.put("from", from.toString());
        node.set("payload", mapper.valueToTree(payload));
        return event("interview-signal", node);
    }

    public String interviewUpdated(InterviewView interview) {
        ObjectNode node = mapper.createObjectNode();
        node.set("interview", mapper.valueToTree(interview));
        return event("interview-updated", node);
    }

    public String applicationUpdated(ApplicationView application) {
        ObjectNode node = mapper.createObjectNode();
        node.set("application", mapper.valueToTree(application));
        return event("application-updated", node);
    }

    public String conversationRead(UUID conversationId, UUID reader) {
        ObjectNode node = mapper.createObjectNode();
        node.put("conversationId", conversationId.toString());
        node.put("userId", reader.toString());
        return event("conversation-read", node);
    }
}
