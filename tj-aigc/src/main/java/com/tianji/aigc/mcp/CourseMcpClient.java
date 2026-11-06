package com.tianji.aigc.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.config.McpProperties;
import com.tianji.aigc.tools.CourseDetailReader;
import com.tianji.api.dto.course.CourseBaseInfoDTO;
import com.tianji.api.mcp.CourseDetailContract;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static com.tianji.aigc.mcp.McpToolException.Kind.*;

/** A synchronous business boundary with cancellable SDK subscriptions and no ToolContext bridge. */
public final class CourseMcpClient implements CourseDetailReader, AutoCloseable {
    private final McpProperties settings;
    private final ObjectMapper json;
    private final ObservationRegistry observations;
    private final Semaphore permits;
    private Session session;
    private boolean closed;

    public CourseMcpClient(McpProperties settings, ObjectMapper json, ObservationRegistry observations) {
        this.settings = settings;
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        this.json.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
                        com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
                        com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean,
                        com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        this.observations = observations;
        this.permits = new Semaphore(settings.getMaxConcurrentCalls());
    }

    @Override
    public CourseBaseInfoDTO read(Long id, McpCallScope scope) {
        if (scope == null) throw new McpToolException(PROTOCOL);
        scope.start(settings.getRoundBudget());
        CourseDetailContract.parseId(id.toString());
        if (!permits.tryAcquire()) throw new McpToolException(BUSY);
        try {
            Session current = readySession();
            if (current == null) {
                current = stage("initialize", scope, () -> initializedConnection(scope));
                Session initializing = current;
                try {
                    stage("discover", scope, () -> {
                        var tools = await(initializing.tools(), scope, false);
                        try {
                            validateTools(tools);
                        } catch (McpToolException invalid) {
                            invalidate(initializing);
                            throw invalid;
                        }
                        return true;
                    });
                    current.ready = true;
                } catch (McpToolException error) {
                    discard(current);
                    throw error;
                }
            }
            Session connected = current;
            return stage("call", scope, () -> {
                scope.check();
                // Only the ID is transmitted. No user identity, history, scope or local state in _meta.
                var result = await(connected.client.callTool(new McpSchema.CallToolRequest(
                        CourseDetailContract.TOOL_NAME, Map.of("courseId", id.toString())))
                        .timeout(settings.getRequestTimeout()).toFuture(), scope, true);
                scope.check();
                return decode(result, id);
            });
        } finally {
            permits.release();
        }
    }

    @Override
    public void check(McpCallScope scope) {
        if (scope == null) throw new McpToolException(PROTOCOL);
        scope.check();
    }

    private synchronized Session readySession() {
        if (closed) throw new McpToolException(UNAVAILABLE);
        return session != null && session.ready ? session : null;
    }

    private Session initializedConnection(McpCallScope scope) {
        Session current = connection(scope);
        try {
            var initialized = await(current.initialization, scope, false);
            if (initialized.serverInfo() == null || !CourseDetailContract.SERVER_ID.equals(initialized.serverInfo().name())) {
                invalidate(current);
                throw new McpToolException(PROTOCOL);
            }
            return current;
        } catch (McpToolException error) {
            discard(current);
            throw error;
        }
    }

    private synchronized Session connection(McpCallScope scope) {
        scope.check();
        if (closed) throw new McpToolException(UNAVAILABLE);
        if (session == null) {
            URI uri = URI.create(settings.getCourseServerUrl());
            var transport = HttpClientStreamableHttpTransport.builder(
                            uri.getScheme() + "://" + uri.getRawAuthority())
                    .endpoint(uri.getRawPath()).openConnectionOnStartup(false).resumableStreams(false)
                    .connectTimeout(settings.getRequestTimeout())
                    .customizeRequest(builder -> builder.timeout(settings.getRequestTimeout())
                            .header("Authorization", "Bearer " + settings.getToken())).build();
            var client = McpClient.async(transport).requestTimeout(settings.getRequestTimeout())
                    .initializationTimeout(settings.getRequestTimeout())
                    .clientInfo(new McpSchema.Implementation("tianji-aigc", "1.0.0")).build();
            session = new Session(client, client.initialize().timeout(settings.getRequestTimeout()).toFuture());
        }
        return session;
    }

    private synchronized void discard(Session failed) {
        // A caller's budget exhaustion must never close another caller's healthy shared connection.
        if (session == failed && (failed.initialization.isCompletedExceptionally()
                || failed.discovery != null && failed.discovery.isCompletedExceptionally())) {
            session = null;
            failed.client.close();
        }
    }

    private synchronized void invalidate(Session invalid) {
        if (session == invalid) {
            session = null;
            invalid.client.close();
        }
    }

    private void validateTools(McpSchema.ListToolsResult result) {
        if (result == null || result.nextCursor() != null || result.tools() == null || result.tools().size() != 1) {
            throw new McpToolException(PROTOCOL);
        }
        var tool = result.tools().get(0);
        var schema = tool.inputSchema();
        if (!CourseDetailContract.TOOL_NAME.equals(tool.name()) || schema == null
                || !"object".equals(schema.type()) || !List.of("courseId").equals(schema.required())
                || !Boolean.FALSE.equals(schema.additionalProperties())
                || !Map.of("courseId", Map.of("type", "string", "pattern", "^[1-9][0-9]{0,18}$")).equals(schema.properties())
                || schema.defs() != null && !schema.defs().isEmpty()
                || schema.definitions() != null && !schema.definitions().isEmpty()) {
            throw new McpToolException(PROTOCOL);
        }
    }

    CourseBaseInfoDTO decode(McpSchema.CallToolResult result, Long id) {
        if (result == null || result.content() == null || result.content().size() != 1
                || !(result.content().get(0) instanceof McpSchema.TextContent text) || text.text() == null
                || text.text().getBytes(StandardCharsets.UTF_8).length > CourseDetailContract.MAX_BYTES) {
            throw new McpToolException(PROTOCOL);
        }
        try {
            if (Boolean.TRUE.equals(result.isError())) {
                var error = json.readTree(text.text());
                throw new McpToolException(switch (error.path("code").asText()) {
                    case "FORBIDDEN" -> FORBIDDEN;
                    case "BUSY" -> BUSY;
                    case "PROTOCOL", "TOO_LARGE", "INVALID_ARGUMENT" -> PROTOCOL;
                    default -> UNAVAILABLE;
                });
            }
            var envelope = json.readValue(text.text(), CourseDetailContract.Result.class);
            if (envelope.schemaVersion() != 1) throw new McpToolException(PROTOCOL);
            if ("NOT_FOUND".equals(envelope.status()) && envelope.data() == null) return null;
            if (!"FOUND".equals(envelope.status()) || envelope.data() == null) throw new McpToolException(PROTOCOL);
            var data = envelope.data();
            if (!id.toString().equals(data.id()) || data.price() != null && data.price() < 0
                    || data.validDuration() != null && data.validDuration() < 0
                    || data.catalogue() != null && (data.catalogue().size() > CourseDetailContract.MAX_CATALOGUE_ITEMS
                    || data.catalogue().stream().anyMatch(value -> value == null))) throw new McpToolException(PROTOCOL);
            return data.toDto();
        } catch (McpToolException error) {
            throw error;
        } catch (Exception error) {
            throw new McpToolException(PROTOCOL);
        }
    }

    private <T> T await(CompletableFuture<T> future, McpCallScope scope, boolean cancel) {
        try {
            // Local deadline every ~25ms; Redis renew/isCurrent only on the slower ownership cadence
            // plus the mandatory live check after a result arrives (and at stage boundaries).
            long nextRemoteCheck = 0L;
            while (true) {
                long now = System.nanoTime();
                if (now >= nextRemoteCheck) {
                    scope.check();
                    nextRemoteCheck = now + McpCallScope.REMOTE_OWNERSHIP_CHECK_NANOS;
                } else {
                    scope.checkLocalDeadline();
                }
                try {
                    T value = future.get(Math.min(scope.remainingNanos(), TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS);
                    scope.check();
                    return value;
                } catch (TimeoutException polling) {
                    // Keep waiting; ownership was sampled above on its own cadence.
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new McpCallScope.CancelledException();
        } catch (ExecutionException error) {
            scope.check();
            Throwable cause = reactor.core.Exceptions.unwrap(error.getCause());
            for (int i = 0; cause != null && i < 20; i++, cause = cause.getCause()) {
                if (cause instanceof TimeoutException || cause instanceof java.net.http.HttpTimeoutException) {
                    throw new McpToolException(TIMEOUT);
                }
            }
            throw new McpToolException(UNAVAILABLE);
        } finally {
            if (cancel && !future.isDone()) future.cancel(true);
        }
    }

    private <T> T stage(String name, McpCallScope scope, Supplier<T> work) {
        Observation observation = Observation.start("mcp." + name, observations)
                .lowCardinalityKeyValue("mcp.server", CourseDetailContract.SERVER_ID)
                .lowCardinalityKeyValue("mcp.tool", CourseDetailContract.TOOL_NAME);
        try (var ignored = observation.openScope()) {
            scope.check();
            observation.highCardinalityKeyValue("mcp.remaining.ms", Long.toString(TimeUnit.NANOSECONDS.toMillis(scope.remainingNanos())));
            T result = work.get();
            scope.check();
            observation.lowCardinalityKeyValue("mcp.status", "success");
            return result;
        } catch (CancellationException error) {
            observation.lowCardinalityKeyValue("mcp.status", "cancelled");
            throw error;
        } catch (RuntimeException error) {
            var safe = McpToolException.find(error);
            observation.lowCardinalityKeyValue("mcp.status", safe == null ? "failed" : safe.kind().name());
            observation.error(new IllegalStateException(safe == null ? "MCP_FAILED" : safe.code()));
            throw error;
        } finally {
            observation.stop();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (session != null) { session.client.close(); session = null; }
    }

    private final class Session {
        private final McpAsyncClient client;
        private final CompletableFuture<McpSchema.InitializeResult> initialization;
        private CompletableFuture<McpSchema.ListToolsResult> discovery;
        private volatile boolean ready;

        private Session(McpAsyncClient client, CompletableFuture<McpSchema.InitializeResult> initialization) {
            this.client = client; this.initialization = initialization;
        }

        synchronized CompletableFuture<McpSchema.ListToolsResult> tools() {
            if (discovery == null) discovery = client.listTools().timeout(settings.getRequestTimeout()).toFuture();
            return discovery;
        }
    }
}
