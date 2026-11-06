package com.tianji.learning.controller.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.api.client.learning.LearningClient;
import com.tianji.api.config.RequestIdRelayConfiguration;
import com.tianji.authsdk.resource.config.ResourceAuthProperties;
import com.tianji.authsdk.resource.config.ResourceInterceptorConfiguration;
import com.tianji.common.autoconfigure.mvc.advice.CommonExceptionAdvice;
import com.tianji.common.constants.Constant;
import com.tianji.learning.service.interaction.IInteractionReplyService;
import feign.Feign;
import feign.FeignException;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 使用随机本机端口、真实 Tomcat/MVC/Feign；不加载 Nacos 或业务数据库。 */
class ReplyExistenceHttpTest {
    private static AnnotationConfigServletWebServerApplicationContext context;
    private static String baseUrl;
    private static IInteractionReplyService service;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void startServer() {
        context = new AnnotationConfigServletWebServerApplicationContext();
        context.register(ServerConfig.class);
        context.refresh();
        baseUrl = "http://127.0.0.1:" + context.getWebServer().getPort();
        service = context.getBean(IInteractionReplyService.class);
    }

    @AfterAll
    static void stopServer() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetService() {
        reset(service);
        when(service.queryExistingReplyIds(anyList())).thenAnswer(invocation ->
                new HashSet<>(invocation.getArgument(0, List.class)));
    }

    @Test
    void fiveHundredLongIdsExceedTheActualTomcatLimit() {
        TomcatWebServer server = (TomcatWebServer) context.getWebServer();
        AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>)
                server.getTomcat().getConnector().getProtocolHandler();
        assertEquals(8192, protocol.getMaxHttpHeaderSize());
        AtomicInteger urlLength = new AtomicInteger();
        LearningClient client = client(urlLength);

        FeignException failure = assertThrows(FeignException.class,
                () -> client.queryExistingReplyIds(longIds(500)));

        assertEquals(400, failure.status());
        assertTrue(urlLength.get() > 8192);
        verifyNoInteractions(service);
    }

    @Test
    void aHundredLongIdsWorkThroughTheRealFeignContractWithoutAUser() {
        AtomicInteger urlLength = new AtomicInteger();
        List<Long> ids = longIds(100);

        assertEquals(new HashSet<>(ids), client(urlLength).queryExistingReplyIds(ids));
        assertTrue(urlLength.get() < 4096);
    }

    @Test
    void gatewayRequestsCannotUseTheInternalEndpoint() throws Exception {
        HttpResponse<String> response = request("ids=2", Constant.GATEWAY_ORIGIN_NAME);

        // 项目对网关异常返回 HTTP 200，真正的拒绝状态在业务 code 中。
        assertEquals(200, response.statusCode());
        assertEquals(403, JSON.readTree(response.body()).path("code").asInt(-1));
        verifyNoInteractions(service);
    }

    @Test
    void unmarkedRequestsCannotUseTheInternalEndpoint() throws Exception {
        assertEquals(403, request("ids=2", null).statusCode());
        verifyNoInteractions(service);
    }

    @Test
    void sizeValidationIsActiveBeforeTheServiceIsCalled() throws Exception {
        String query = LongStream.rangeClosed(1, 501).mapToObj(id -> "ids=" + id)
                .collect(Collectors.joining("&"));
        HttpResponse<String> response = request(query, Constant.FEIGN_ORIGIN_NAME);

        // 沿用现有 ConstraintViolationException 响应规范，不修改公共异常组件。
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("500"));
        verifyNoInteractions(service);
    }

    private LearningClient client(AtomicInteger urlLength) {
        return Feign.builder().contract(new SpringMvcContract())
                .requestInterceptor(new RequestIdRelayConfiguration().requestIdInterceptor())
                .requestInterceptor(template -> urlLength.set(template.url().length()))
                .decoder((response, type) -> JSON.readValue(response.body().asInputStream(),
                        JSON.getTypeFactory().constructType(type)))
                .target(LearningClient.class, baseUrl);
    }

    private List<Long> longIds(int count) {
        return LongStream.range(0, count).map(i -> Long.MAX_VALUE - i).boxed()
                .collect(Collectors.toList());
    }

    private HttpResponse<String> request(String query, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/replies/exists?" + query));
        if (origin != null) {
            request.header(Constant.REQUEST_FROM_HEADER, origin);
        }
        return HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Configuration
    @EnableWebMvc
    @Import(ValidationAutoConfiguration.class)
    static class ServerConfig {
        @Bean
        TomcatServletWebServerFactory serverFactory() throws Exception {
            TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
            factory.setAddress(InetAddress.getByName("127.0.0.1"));
            return factory;
        }

        @Bean
        DispatcherServlet dispatcherServlet() {
            return new DispatcherServlet();
        }

        @Bean
        ServletRegistrationBean<DispatcherServlet> dispatcherRegistration(DispatcherServlet servlet) {
            return new ServletRegistrationBean<>(servlet, "/");
        }

        @Bean
        IInteractionReplyService replyService() {
            return mock(IInteractionReplyService.class);
        }

        @Bean
        InteractionReplyController controller(IInteractionReplyService replies) {
            return new InteractionReplyController(replies,
                    mock(com.tianji.learning.service.interaction.BotReplyService.class));
        }

        @Bean
        CommonExceptionAdvice exceptionAdvice() {
            return new CommonExceptionAdvice();
        }

        @Bean
        ResourceInterceptorConfiguration resourceInterceptors() {
            ResourceAuthProperties properties = new ResourceAuthProperties();
            properties.setEnable(true);
            properties.setExcludeLoginPaths(List.of("/replies/exists"));
            return new ResourceInterceptorConfiguration(properties);
        }

    }
}
