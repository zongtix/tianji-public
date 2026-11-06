package com.tianji.trade.sandbox;

import com.tianji.common.exceptions.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/sandbox-checkout")
@ConditionalOnProperty(name = "tj.trade.sandbox-checkout.enabled", havingValue = "true")
public class SandboxCheckoutController {
    private final SandboxCheckoutService checkout;

    @GetMapping("/health")
    public java.util.Map<String, String> health() {
        return java.util.Map.of("status", "UP", "environment", "sandbox");
    }

    @GetMapping("/preview")
    public SandboxCheckoutService.View preview(@RequestParam("courseId") Long courseId) {
        return checkout.preview(courseId);
    }

    @PostMapping("/confirm")
    public SandboxCheckoutService.View confirm(@RequestBody Confirmation body) {
        if (body == null || !Boolean.TRUE.equals(body.confirmed())) throw new BadRequestException("请先确认课程和金额。");
        return checkout.confirm(body.token(), true);
    }

    @GetMapping("/status")
    public SandboxCheckoutService.View status(@RequestParam("token") String token) {
        return checkout.status(token);
    }

    public record Confirmation(String token, Boolean confirmed) { }
}
