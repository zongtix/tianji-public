package com.tianji.aigc;

import com.tianji.aigc.controller.AudioController;
import com.tianji.aigc.service.AudioService;
import com.tianji.common.autoconfigure.mvc.advice.CommonExceptionAdvice;
import com.tianji.common.autoconfigure.mvc.advice.WrapperResponseBodyAdvice;
import com.tianji.common.constants.Constant;
import com.tianji.common.exceptions.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AudioHttpTest {
    private final AudioService service = mock(AudioService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AudioController(service))
            .setControllerAdvice(new CommonExceptionAdvice(), new WrapperResponseBodyAdvice()).build();

    @Test void multipartTranscriptionReturnsWrappedObjectForGateway() throws Exception {
        when(service.stt(any())).thenReturn("语音问题");
        mvc.perform(multipart("/audio/stt").file(new MockMultipartFile("audioFile", "test.wav", "audio/wav", new byte[]{1}))
                        .header(Constant.REQUEST_FROM_HEADER, Constant.GATEWAY_ORIGIN_NAME))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.text").value("语音问题"));
    }

    @Test void speechReturnsRawMp3EvenBehindGateway() throws Exception {
        when(service.tts(anyString())).thenReturn(new byte[]{73, 68, 51, 1, 2});
        mvc.perform(post("/audio/tts").contentType("text/plain;charset=UTF-8").content("播报回答")
                        .header(Constant.REQUEST_FROM_HEADER, Constant.GATEWAY_ORIGIN_NAME))
                .andExpect(status().isOk()).andExpect(content().contentType("audio/mpeg"))
                .andExpect(content().bytes(new byte[]{73, 68, 51, 1, 2}));
        verify(service).tts("播报回答");
    }

    @Test void speechErrorRemainsReadableJsonForGateway() throws Exception {
        when(service.tts(anyString())).thenThrow(new BadRequestException("语音额度已用完"));
        mvc.perform(post("/audio/tts").contentType("text/plain;charset=UTF-8").content("回答")
                        .accept(MediaType.ALL)
                        .header(Constant.REQUEST_FROM_HEADER, Constant.GATEWAY_ORIGIN_NAME))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("语音额度已用完"));
    }

    @Test void exposesAvailabilityWithoutReturningKeysAndRejectsMissingFile() throws Exception {
        when(service.options()).thenReturn(new AudioService.Options(false, 60, 3000));
        mvc.perform(get("/audio/options").header(Constant.REQUEST_FROM_HEADER, Constant.GATEWAY_ORIGIN_NAME))
                .andExpect(jsonPath("$.data.enabled").value(false)).andExpect(jsonPath("$.data.apiKey").doesNotExist());
        mvc.perform(multipart("/audio/stt")).andExpect(status().isBadRequest());
    }
}
