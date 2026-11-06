package com.tianji.aigc.knowledge;

import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.UserContext;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 公共知识库发布配置。
 * {@link #requireFilter}：登录/启用校验后构建过滤器，未配置库拒绝；
 * {@link #filterFor}：仅按配置映射过滤器（无登录/启用校验），未知或空返回 null。
 */
@Data
@Component
@ConfigurationProperties(prefix = "tj.ai.knowledge")
public class KnowledgeBaseAccess {
    private boolean enabled;
    private Map<String, List<String>> publicBases = new LinkedHashMap<>();

    /**
     * 纯配置映射，不依赖 UserContext / enabled。
     * 调用方须传入可信 baseId（如服务端 domain 映射）；未知或空 → null。
     */
    public String filterFor(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isEmpty()) {
            return null;
        }
        List<String> documents = publicBases.get(knowledgeBaseId);
        if (documents == null || documents.isEmpty()) {
            return null;
        }
        // 文档范围只来自服务端发布配置，不接收客户端过滤表达式。
        if (documents.stream().anyMatch(id -> id == null || !id.matches("[a-zA-Z0-9_-]+"))) {
            throw new IllegalStateException("知识库文档范围配置不正确");
        }
        return "documentId in [" + documents.stream().map(id -> "'" + id + "'")
                .collect(Collectors.joining(",")) + "]";
    }

    public String requireFilter(String knowledgeBaseId) {
        if (knowledgeBaseId == null) return null;
        if (UserContext.getUser() == null) throw new UnauthorizedException("请先登录");
        if (!enabled) throw new BadRequestException("知识库功能未启用");
        if (!knowledgeBaseId.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new BadRequestException("知识库不存在或无权访问");
        }
        String filter = filterFor(knowledgeBaseId);
        if (filter == null) throw new BadRequestException("知识库不存在或无权访问");
        return filter;
    }
}
