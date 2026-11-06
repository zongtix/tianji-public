package com.tianji.aigc.controller;

import com.tianji.aigc.service.AudioService;
import com.tianji.common.annotations.NoWrapper;
import com.tianji.common.exceptions.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/audio")
@RequiredArgsConstructor
public class AudioController {
    private final AudioService service;

    public record Transcription(String text) { }

    @GetMapping("/options")
    public AudioService.Options options() { return service.options(); }

    @PostMapping(value = "/stt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Transcription stt(@RequestParam(value = "audioFile", required = false) MultipartFile file) {
        if (file == null) throw new BadRequestException("请选择录音文件");
        return new Transcription(service.stt(file));
    }

    @NoWrapper
    @PostMapping(value = "/tts", consumes = MediaType.TEXT_PLAIN_VALUE, produces = "audio/mpeg")
    public ResponseEntity<byte[]> tts(@RequestBody String text) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg"))
                .cacheControl(CacheControl.noStore()).body(service.tts(text));
    }
}
