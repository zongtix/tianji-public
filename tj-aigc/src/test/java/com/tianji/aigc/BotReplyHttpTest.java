package com.tianji.aigc;

import com.tianji.aigc.controller.ChatController;
import com.tianji.aigc.service.BotTextService;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.common.autoconfigure.mvc.advice.CommonExceptionAdvice;
import com.tianji.common.constants.Constant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.tianji.api.client.aigc.BotReplyClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BotReplyHttpTest {
    private final BotTextService service = mock(BotTextService.class);
    private final ChatController controller = new ChatController(
            mock(ChatService.class), mock(ChatSessionService.class), service);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new CommonExceptionAdvice()).build();

    @BeforeEach
    void configureInternalCredential() {
        ReflectionTestUtils.setField(controller, "internalToken", "test-internal-token");
    }

    @Test
    void browserAndGatewayCannotSpendTokensViaInternalEndpoint() throws Exception {
        mvc.perform(post("/chat/reply").contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"问题\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/chat/reply").header(Constant.REQUEST_FROM_HEADER, Constant.GATEWAY_ORIGIN_NAME)
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"问题\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(service);
    }

    @Test
    void internalEndpointRejectsEmptyOrOversizedContextBeforeCallingModel() throws Exception {
        for (String content : new String[]{"{\"comment\":\" \"}", "{\"comment\":\"" + "问".repeat(2001) + "\"}"}) {
            mvc.perform(post("/chat/reply").header(Constant.REQUEST_FROM_HEADER, Constant.FEIGN_ORIGIN_NAME)
                    .contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void internalCallReturnsPlainTextWithoutWrapperOrSse() throws Exception {
        when(service.reply(any())).thenReturn("自调用没有经过代理。");
        mvc.perform(post("/chat/reply").header(Constant.REQUEST_FROM_HEADER, Constant.FEIGN_ORIGIN_NAME)
                .header(BotReplyClient.INTERNAL_TOKEN_HEADER, "test-internal-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"为什么失效？\"}"))
                .andExpect(status().isOk()).andExpect(content().string("自调用没有经过代理。"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN));
    }

    @Test
    void forgedFeignOriginCannotReplaceTheInternalCredential() throws Exception {
        for (String token : new String[]{"", "wrong-token"}) {
            mvc.perform(post("/chat/reply").header(Constant.REQUEST_FROM_HEADER, Constant.FEIGN_ORIGIN_NAME)
                    .header(BotReplyClient.INTERNAL_TOKEN_HEADER, token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"问题\"}"))
                    .andExpect(status().isForbidden());
        }
        ReflectionTestUtils.setField(controller, "internalToken", "");
        mvc.perform(post("/chat/reply").header(Constant.REQUEST_FROM_HEADER, Constant.FEIGN_ORIGIN_NAME)
                .header(BotReplyClient.INTERNAL_TOKEN_HEADER, "test-internal-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"问题\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void legacyUnrestrictedEmbeddingEndpointIsAbsent() throws Exception {
        var result = mvc.perform(post("/embedding").param("messages", "untrusted document")).andReturn();
        org.assertj.core.api.Assertions.assertThat(result.getHandler()).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Class.forName(
                "com.tianji.aigc.controller.EmbeddingController")).isInstanceOf(ClassNotFoundException.class);
        verifyNoInteractions(service);
    }

    @Test
    void feignClientOnlySendsTheConfiguredInternalCredential() {
        var template = new feign.RequestTemplate();
        template.header(BotReplyClient.INTERNAL_TOKEN_HEADER, "untrusted-value");
        new BotReplyClient.Config().botReplyAuthentication("test-internal-token").apply(template);
        org.assertj.core.api.Assertions.assertThat(template.headers().get(BotReplyClient.INTERNAL_TOKEN_HEADER))
                .containsExactly("test-internal-token");
        new BotReplyClient.Config().botReplyAuthentication("").apply(template);
        org.assertj.core.api.Assertions.assertThat(template.headers()).doesNotContainKey(BotReplyClient.INTERNAL_TOKEN_HEADER);
    }
}
