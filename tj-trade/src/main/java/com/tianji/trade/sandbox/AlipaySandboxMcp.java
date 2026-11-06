package com.tianji.trade.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.common.exceptions.BizIllegalException;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Fixed official tools in a sandbox-only child process. No model or chat context. */
@Component
@ConditionalOnProperty(name = "tj.trade.sandbox-checkout.enabled", havingValue = "true")
public class AlipaySandboxMcp {
    static final String CREATE = "create-web-page-alipay-payment";
    static final String QUERY = "query-alipay-payment";
    private static final Set<String> TOOLS = Set.of(CREATE, QUERY);
    private static final Set<String> HOSTS = Set.of("openapi.alipaydev.com", "openapi-sandbox.dl.alipaydev.com");
    private static final Pattern STATUS = Pattern.compile(
            "^交易状态: (WAIT_BUYER_PAY|TRADE_SUCCESS|TRADE_FINISHED|TRADE_CLOSED) , 交易金额: ([0-9]+(?:\\.[0-9]{1,2})?) , 支付宝交易号: ([0-9]{1,64})$");
    private final SandboxCheckoutProperties settings;
    private final ObjectMapper json;
    private final Semaphore permits = new Semaphore(2);

    public AlipaySandboxMcp(SandboxCheckoutProperties settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
        Assert.hasText(settings.getServerScript(), "Sandbox MCP script is required");
        Assert.isTrue(Path.of(settings.getServerScript()).isAbsolute(), "Sandbox MCP script must be absolute");
        Assert.hasText(settings.getAppId(), "Sandbox app ID is required");
        Assert.hasText(settings.getAppKey(), "Sandbox application key is required");
        Assert.hasText(settings.getAlipayPublicKey(), "Sandbox Alipay public key is required");
    }

    public String create(String orderNo, int cents, String title) {
        if (cents <= 0 || title == null || title.isBlank() || title.length() > 256) throw failure();
        String text = call(CREATE, Map.of("outTradeNo", orderNo, "totalAmount", money(cents), "orderTitle", title));
        return paymentUrl(text, orderNo, cents);
    }

    public PaymentState query(String orderNo, int cents) {
        return paymentState(call(QUERY, Map.of("outTradeNo", orderNo)), cents);
    }

    protected String call(String tool, Map<String, Object> arguments) {
        // SDK 0.17 stdio readers use the JVM default charset; MCP JSON is UTF-8.
        if (!StandardCharsets.UTF_8.equals(Charset.defaultCharset())) throw failure();
        if (!TOOLS.contains(tool) || !permits.tryAcquire()) throw failure();
        try {
            var env = new HashMap<String, String>();
            env.put("AP_APP_ID", settings.getAppId());
            env.put("AP_APP_KEY", settings.getAppKey());
            env.put("AP_PUB_KEY", settings.getAlipayPublicKey());
            env.put("AP_CURRENT_ENV", "sandbox");
            env.put("AP_SELECT_TOOLS", String.join(",", TOOLS));
            env.put("AP_LOG_ENABLED", "false");
            var parameters = ServerParameters.builder(settings.getNodeCommand())
                    .args(settings.getServerScript()).env(env).build();
            var transport = new StdioClientTransport(parameters, new JacksonMcpJsonMapper(json)) {
                @Override protected ProcessBuilder getProcessBuilder() {
                    var builder = new ProcessBuilder();
                    builder.environment().clear();
                    return builder;
                }
            };
            transport.setStdErrorHandler(ignored -> { });
            try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(12))
                    .initializationTimeout(Duration.ofSeconds(8)).build()) {
                var initialized = client.initialize();
                if (initialized.serverInfo() == null || !"mcp-server-alipay".equals(initialized.serverInfo().name())) throw failure();
                var tools = client.listTools().tools();
                if (tools.size() != 2 || !tools.stream().map(McpSchema.Tool::name).collect(Collectors.toSet()).equals(TOOLS)) throw failure();
                var result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
                if (Boolean.TRUE.equals(result.isError()) || result.content() == null || result.content().size() != 1
                        || !(result.content().get(0) instanceof McpSchema.TextContent text) || text.text().length() > 32768) throw failure();
                return text.text();
            }
        } catch (Exception ignored) {
            // SDK/provider error bodies can contain signed links or merchant data.
            throw failure();
        } finally {
            permits.release();
        }
    }

    String paymentUrl(String text, String orderNo, int cents) {
        String prefix = "网页支付链接: [点击完成支付](";
        if (!text.startsWith(prefix) || !text.endsWith(")")) throw failure();
        String value = text.substring(prefix.length(), text.length() - 1);
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost()) || uri.getPort() != -1
                    || uri.getUserInfo() != null || uri.getFragment() != null || !"/gateway.do".equals(uri.getPath())) throw failure();
            var query = new HashMap<String, String>();
            for (String pair : uri.getRawQuery().split("&")) {
                String[] item = pair.split("=", 2);
                String name = URLDecoder.decode(item[0], StandardCharsets.UTF_8);
                String field = URLDecoder.decode(item[1], StandardCharsets.UTF_8);
                if (query.putIfAbsent(name, field) != null) throw failure();
            }
            var business = json.readTree(query.get("biz_content"));
            if (!settings.getAppId().equals(query.get("app_id")) || !"alipay.trade.page.pay".equals(query.get("method"))
                    || !"RSA2".equals(query.get("sign_type")) || query.getOrDefault("sign", "").isBlank()
                    || !orderNo.equals(business.path("out_trade_no").asText())
                    || new BigDecimal(business.path("total_amount").asText()).compareTo(BigDecimal.valueOf(cents, 2)) != 0) throw failure();
            return value;
        } catch (Exception ignored) { throw failure(); }
    }

    PaymentState paymentState(String text, int expectedCents) {
        var match = STATUS.matcher(text);
        if (match.matches()) {
            if (new BigDecimal(match.group(2)).compareTo(money(expectedCents)) != 0) throw failure();
            return new PaymentState(match.group(1), match.group(3));
        }
        try {
            if (text.startsWith("交易查询失败. 查询结果: ")) {
                var error = json.readTree(text.substring(text.indexOf('{')));
                if ("40004".equals(error.path("code").asText())
                        && "ACQ.TRADE_NOT_EXIST".equals(error.path("subCode").asText())) return new PaymentState("NOT_CREATED", null);
            }
        } catch (Exception ignored) { /* Fail closed below. */ }
        throw failure();
    }

    static BigDecimal money(int cents) { return BigDecimal.valueOf(cents, 2); }
    static BizIllegalException failure() { return new BizIllegalException("支付宝沙箱暂不可用，请稍后重试。"); }
    public record PaymentState(String status, String tradeNo) { }
}
