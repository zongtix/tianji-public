package com.tianji.upgrade;

import lombok.Data;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class TransitionApplication {
    @RestController static class Controller {
        @GetMapping("/probe") Payload probe() {
            Payload result = new Payload();
            result.setValue("java17-transition");
            return result;
        }
    }
    @Data public static class Payload { private String value; }
}
