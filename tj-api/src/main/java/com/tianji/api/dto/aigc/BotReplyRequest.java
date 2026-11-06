package com.tianji.api.dto.aigc;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** learning 从已保存的讨论中组装上下文；不携带聊天会话或用户身份。 */
@Data
public class BotReplyRequest {
    @Size(max = 254)
    private String title;
    @Size(max = 2000)
    private String question;
    @Size(max = 3000)
    private String answer;
    @Size(max = 2000)
    private String target;
    @NotBlank
    @Size(max = 2000)
    private String comment;
}
