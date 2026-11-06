package com.tianji.trade.sandbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxCheckoutDisabledTest {
    @Test void defaultOffNeedsNoCredentialsRedisOrMcpProcess() {
        new ApplicationContextRunner().withUserConfiguration(AlipaySandboxMcp.class,
                SandboxCheckoutService.class, SandboxCheckoutController.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AlipaySandboxMcp.class);
                    assertThat(context).doesNotHaveBean(SandboxCheckoutController.class);
                });
    }
}
