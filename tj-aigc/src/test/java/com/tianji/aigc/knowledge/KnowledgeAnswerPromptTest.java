package com.tianji.aigc.knowledge;

import com.tianji.aigc.config.KnowledgeRagProperties;
import com.tianji.aigc.config.KnowledgeStoreConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeAnswerPromptTest {
    @Test
    void legacyResourceMatchesTheOriginalInlinePromptExactly() throws Exception {
        byte[] bytes = new ClassPathResource("prompts/knowledge-retrieval-v1.txt")
                .getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .isEqualTo("f665a110ee32b9ab7766d86757d5901d0c596d1129c8f848fa84dee7a4f2c4c3");
        String question = "学习积分今天已有45分，再来一次10分事件会入账多少？";
        String rendered = KnowledgeAnswerPrompt.create(KnowledgeAnswerPrompt.LEGACY).render(Map.of(
                "query", question, "question_answer_context", "完整资料"));
        assertThat(rendered.replace("\r\n", "\n")).startsWith("用户问题：\n" + question).contains("完整资料");
    }

    @Test
    void preparesTheKnownLearningScenarioWithCompleteEvidenceWithoutCallingARewriteOrAnswerModel() throws Exception {
        var vectors = mock(VectorStore.class);
        var model = mock(ChatModel.class);
        var chunks = new KnowledgeDocumentService(null, null, "text-embedding-v4").readMarkdown(
                new ClassPathResource("knowledge/tianji-points.md"), "tianji-points-guide", "天机学堂积分与签到规则");
        Document learning = chunks.stream().filter(d -> Integer.valueOf(2).equals(d.getMetadata().get("chunkIndex")))
                .findFirst().orElseThrow();
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(java.util.List.of(learning));
        var beans = new DefaultListableBeanFactory();
        var properties = new KnowledgeRagProperties();
        properties.setMode(KnowledgeRagProperties.Mode.A);
        properties.setPromptVersion(KnowledgeAnswerPrompt.CONDITIONS);
        var config = new KnowledgeStoreConfig();
        var pipeline = config.knowledgeRetrievalPipeline(vectors, 3, 0.6, "tj_aigc_test",
                beans.getBeanProvider(co.elastic.clients.elasticsearch.ElasticsearchClient.class),
                beans.getBeanProvider(io.micrometer.observation.ObservationRegistry.class), properties);
        var advisor = config.knowledgeAdvisor(vectors, model, pipeline, properties);
        String question = "学习积分今天已有45分，再来一次10分事件会入账多少？";
        var original = new ChatClientRequest(new Prompt(question), Map.of(
                QuestionAnswerAdvisor.FILTER_EXPRESSION, "documentId in ['tianji-points-guide']"));
        var prepared = advisor.before(original, null);
        String text = prepared.prompt().getUserMessage().getText().replace("\r\n", "\n");
        assertThat(text).contains(question, learning.getText(), "不要把已知条件当成缺失信息",
                "不代表你已查询或核实其账户", "缺少业务规则依据时仍需明确说明资料不足");
        assertThat(original.prompt().getUserMessage().getText()).isEqualTo(question);
        assertThat(prepared.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS)).isEqualTo(java.util.List.of(learning));
        verify(vectors).similaritySearch(argThat((SearchRequest r) -> question.equals(r.getQuery()) && r.getTopK() == 3));
        verifyNoInteractions(model);
    }

    @Test
    void versionsSelectActualDifferentPromptsAndUnknownVersionsFail() {
        Map<String,Object> input = Map.of("query", "没有说明积分类型的问题", "question_answer_context", "");
        assertThat(KnowledgeAnswerPrompt.create(KnowledgeAnswerPrompt.CONDITIONS).render(input))
                .contains("只有尚缺信息确实会改变本题结论时才澄清")
                .isNotEqualTo(KnowledgeAnswerPrompt.create(KnowledgeAnswerPrompt.LEGACY).render(input));
        assertThat(KnowledgeAnswerPrompt.create(KnowledgeAnswerPrompt.EVIDENCE).render(input))
                .contains("没有说明积分类型的问题", "“未提及禁止”不能证明允许", "不要先肯定回答", "可以根据资料支持的规则和用户条件计算结果")
                .isNotEqualTo(KnowledgeAnswerPrompt.create(KnowledgeAnswerPrompt.CONDITIONS).render(input));
        assertThatThrownBy(() -> KnowledgeAnswerPrompt.create("unknown"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {KnowledgeAnswerPrompt.FOCUSED, KnowledgeAnswerPrompt.PRECISE})
    void answerVariantsPreserveTheEntireLegacyPromptAndEvidenceBoundary(String version) {
        var input = Map.<String,Object>of("query", "资料未提供的问题", "question_answer_context", "完整原始资料");
        String legacy = KnowledgeAnswerPrompt.create(KnowledgeAnswerPrompt.LEGACY).render(input);
        String focused = KnowledgeAnswerPrompt.create(version).render(input);
        assertThat(focused).startsWith(legacy)
                .contains("完整原始资料", "资料不足以确认项目事实时明确说明");
    }

    @ParameterizedTest
    @ValueSource(strings = {KnowledgeAnswerPrompt.CONDITIONS, KnowledgeAnswerPrompt.FOCUSED, KnowledgeAnswerPrompt.PRECISE})
    void additionalInstructionsAreIncludedInTheFinalBudget(String version) {
        var vectors = mock(VectorStore.class);
        var model = mock(ChatModel.class);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(java.util.List.of(new Document("完整规则与条件")));
        var request = new ChatClientRequest(new Prompt("学习积分场景"), Map.of(
                QuestionAnswerAdvisor.FILTER_EXPRESSION, "documentId in ['tianji-points-guide']"));
        var legacy = new KnowledgeRagProperties(); legacy.setPromptVersion(KnowledgeAnswerPrompt.LEGACY);
        var prepared = advisor(vectors, model, legacy).before(request, null);
        int budget = new KnowledgeContextSelector(legacy.getContext()).requestTokens(prepared)
                + legacy.getContext().getOutputReserve() + legacy.getContext().getSafetyMargin();
        legacy.getContext().setWindowTokens(budget);
        assertThat(advisor(vectors, model, legacy).before(request, null)).isNotNull();
        var conditions = new KnowledgeRagProperties();
        conditions.setPromptVersion(version);
        conditions.getContext().setWindowTokens(budget);
        assertThatThrownBy(() -> advisor(vectors, model, conditions).before(request, null))
                .isInstanceOf(KnowledgeContextSelector.ContextTooLongException.class);
        verifyNoInteractions(model);
    }

    @Test
    void experimentalConditionPromptStaysOptInInBothDefaultsAndApplicationConfiguration() throws Exception {
        var defaults = new KnowledgeRagProperties();
        assertThat(defaults.getPromptVersion()).isEqualTo(KnowledgeAnswerPrompt.LEGACY);
        assertThat(defaults.getMode()).isEqualTo(KnowledgeRagProperties.Mode.B);
        assertThat(defaults.getRetrieval().getContextTopK()).isEqualTo(3);
        var yaml = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        assertThat(yaml.get(0).getProperty("tj.ai.knowledge.rag.mode")).isEqualTo("B");
        assertThat(yaml.get(0).getProperty("tj.ai.knowledge.rag.prompt-version")).isEqualTo(KnowledgeAnswerPrompt.LEGACY);
    }

    private KnowledgeRetrievalAdvisor advisor(VectorStore vectors, ChatModel model, KnowledgeRagProperties properties) {
        properties.setMode(KnowledgeRagProperties.Mode.A);
        var beans = new DefaultListableBeanFactory();
        var config = new KnowledgeStoreConfig();
        var pipeline = config.knowledgeRetrievalPipeline(vectors, 3, 0.6, "tj_aigc_test",
                beans.getBeanProvider(co.elastic.clients.elasticsearch.ElasticsearchClient.class),
                beans.getBeanProvider(io.micrometer.observation.ObservationRegistry.class), properties);
        return config.knowledgeAdvisor(vectors, model, pipeline, properties);
    }
}
