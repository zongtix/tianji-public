package com.tianji.aigc.memory;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.chat.messages.*;

/**
 * 消息转换工具类，提供消息对象与持久化JSON字符串之间的转换功能。
 */
public class MessageUtil {
    public static final String GENERATION_STATUS = "generationStatus";
    public static final String STOPPED = "stopped";
    public static final String COURSE_CARDS = "courseCards";

    public static boolean isStopped(Message message) {
        return message.getMessageType() == MessageType.ASSISTANT
                && STOPPED.equals(message.getMetadata().get(GENERATION_STATUS));
    }

    /**
     * 将Message对象转换为持久化使用的JSON字符串
     *
     * @param message 需要转换的原始消息对象
     * @return 包含消息类型、正文与元数据的JSON字符串
     */
    public static String toJson(Message message) {
        var myMessage = BeanUtil.toBean(message, MyMessage.class);
        // 设置消息内容
        myMessage.setTextContent(message.getText());
        if (message instanceof AssistantMessage assistantMessage) {
            myMessage.setToolCalls(assistantMessage.getToolCalls());
        }

        if (message instanceof ToolResponseMessage toolResponseMessage) {
            myMessage.setToolResponses(toolResponseMessage.getResponses());
        }

        return JSONUtil.toJsonStr(myMessage);
    }

    /**
     * 将持久化的JSON字符串反序列化为对应的Message对象
     *
     * @param json JSON格式消息数据
     * @return 对应类型的Message对象
     * @throws RuntimeException 当无法识别的消息类型时抛出异常
     */
    public static Message toMessage(String json) {
        var myMessage = JSONUtil.toBean(json, MyMessage.class);
        var messageType = MessageType.valueOf(myMessage.getMessageType());
        switch (messageType) {
            case SYSTEM -> {
                return new SystemMessage(myMessage.getTextContent());
            }
            case USER -> {
                return UserMessage.builder()
                        .text(myMessage.getTextContent())
                        .metadata(myMessage.getMetadata())
                        .media(myMessage.getMedia())
                        .build();
            }
            case ASSISTANT -> {
                return AssistantMessage.builder()
                        .content(myMessage.getTextContent())
                        .properties(myMessage.getMetadata())
                        .toolCalls(myMessage.getToolCalls())
                        .media(myMessage.getMedia())
                        .build();
            }
            case TOOL -> {
                return ToolResponseMessage.builder()
                        .responses(myMessage.getToolResponses())
                        .metadata(myMessage.getMetadata())
                        .build();
            }
        }

        throw new RuntimeException("Message data conversion failed.");
    }

}
