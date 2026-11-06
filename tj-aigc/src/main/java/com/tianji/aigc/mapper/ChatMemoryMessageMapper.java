package com.tianji.aigc.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatMemoryMessageMapper {
    @Select("SELECT DISTINCT conversation_id FROM chat_message ORDER BY conversation_id")
    List<String> findConversationIds();

    @Select("SELECT message_json FROM chat_message WHERE conversation_id = #{conversationId} ORDER BY message_index")
    List<String> findMessages(String conversationId);

    @Delete("DELETE FROM chat_message WHERE conversation_id = #{conversationId}")
    void deleteMessages(String conversationId);

    @Insert("""
            <script>
            INSERT INTO chat_message (conversation_id, message_index, message_json) VALUES
            <foreach collection="messages" item="message" index="position" separator=",">
                (#{conversationId}, #{position}, #{message})
            </foreach>
            </script>
            """)
    void insertMessages(@Param("conversationId") String conversationId,
                        @Param("messages") List<String> messages);
}
