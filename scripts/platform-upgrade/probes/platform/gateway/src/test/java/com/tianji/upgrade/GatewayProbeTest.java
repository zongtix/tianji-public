package com.tianji.upgrade;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@DirtiesContext
class GatewayProbeTest {
    @Value("${local.server.port}") int port;
    static HttpServer upstream;
    @BeforeAll static void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 19198), 0);
        upstream.createContext("/identity", exchange -> {
            byte[] body = exchange.getRequestHeaders().getFirst("user-info").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
    }
    @AfterAll static void stopUpstream() { if (upstream != null) upstream.stop(0); }
    @Test void forwardsAndStripsPrefixWithoutLosingIdentity() throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/probe/identity"))
            .header("user-info", "900000001").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("900000001", response.body());
    }
}
