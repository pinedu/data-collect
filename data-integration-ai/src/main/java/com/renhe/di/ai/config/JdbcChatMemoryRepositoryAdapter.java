package com.renhe.di.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * 自定义 ChatMemoryRepository 实现，直接使用 JdbcTemplate 操作 MySQL。
 * <p>
 * 绕过 Spring AI 1.0.0 的 JdbcChatMemoryRepository.Builder 中
 * warnIfDialectMismatch → from() 的连接泄漏 Bug。
 * <p>
 * 消息存储策略：content 列存纯文本（msg.getText()），type 列存消息类型，
 * 读取时按类型重建 Message 对象，避免 Jackson 序列化 Spring AI 不可变类的问题。
 */
@Slf4j
public class JdbcChatMemoryRepositoryAdapter implements ChatMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcChatMemoryRepositoryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT content, type FROM spring_ai_chat_memory WHERE conversation_id = ? ORDER BY timestamp",
                conversationId
        );
        List<Message> messages = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String content = (String) row.get("content");
            String type = (String) row.get("type");
            messages.add(createMessage(type, content));
        }
        return messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        // 先删后插，保证窗口内消息一致性
        jdbcTemplate.update("DELETE FROM spring_ai_chat_memory WHERE conversation_id = ?", conversationId);
        for (Message msg : messages) {
            jdbcTemplate.update(
                    "INSERT INTO spring_ai_chat_memory (conversation_id, content, type) VALUES (?, ?, ?)",
                    conversationId, msg.getText(), msg.getMessageType().name()
            );
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        jdbcTemplate.update("DELETE FROM spring_ai_chat_memory WHERE conversation_id = ?", conversationId);
    }

    @Override
    public List<String> findConversationIds() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT conversation_id FROM spring_ai_chat_memory",
                String.class
        );
    }

    /**
     * 根据类型和文本重建 Message 对象
     */
    private Message createMessage(String type, String text) {
        MessageType messageType;
        try {
            messageType = MessageType.valueOf(type);
        } catch (IllegalArgumentException e) {
            log.warn("未知消息类型: {}, 回退为 USER", type);
            return new UserMessage(text);
        }
        return switch (messageType) {
            case USER -> new UserMessage(text);
            case ASSISTANT -> new AssistantMessage(text);
            case SYSTEM -> new SystemMessage(text);
            case TOOL -> new ToolResponseMessage(List.of());
        };
    }
}
