package com.tianji.aigc.knowledge;

import com.tianji.aigc.config.KnowledgeRagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class KnowledgeContextSelectorTest {
    final KnowledgeRagProperties.Context settings = new KnowledgeRagProperties.Context();
    final KnowledgeContextSelector selector = new KnowledgeContextSelector(settings);
    ChatClientRequest assemble(List<Document> docs) {
        return new ChatClientRequest(new Prompt(List.of(new SystemMessage("仅根据完整资料回答"),
                new UserMessage("先前的必要条件"), new AssistantMessage("前文"),
                new UserMessage("问题\n" + docs.stream().map(Document::getText).reduce("", (a,b) -> a + "\n" + b)))), Map.of());
    }
    RagCandidate candidate(String id, String hash, String text) {
        var doc = new Document(id, text, Map.of("documentId", "rules", "contentHash", hash, "chunkIndex", 1));
        return new RagCandidate(doc, RagCandidate.identity(doc), Map.of(), 0.01, null);
    }

    @Test
    void shortEvidencePreservesAllConditionsAndDifferentVersions() {
        var a = candidate("a", "v1", "基础1分，仅恰好第7天额外10分，不是此后每天奖励。");
        var b = candidate("a", "v2", "不同来源或版本不得合并。");
        assertThat(selector.select(List.of(a, a, b), 3, this::assemble)).containsExactly(a.document(), b.document());
    }

    @Test
    void budgetSelectsWholeChunksAndRetainsLaterEvidenceThatFits() {
        var big = candidate("big", "v1", "完整条件不可截断，".repeat(200));
        var small = candidate("small", "v1", "上限为0表示不限额，不表示不能获得积分。");
        settings.setWindowTokens(selector.requestTokens(assemble(List.of(small.document()))) + settings.getOutputReserve() + settings.getSafetyMargin());
        var result = selector.select(List.of(big, small), 3, this::assemble);
        assertThat(result).containsExactly(small.document());
        assertThat(result.get(0).getText()).endsWith("不表示不能获得积分。");
    }

    @Test
    void oversizedQuestionAndHistoryFailWithoutSilentlyDeletingThem() {
        settings.setWindowTokens(100);
        assertThatThrownBy(() -> selector.select(List.of(), 3, this::assemble))
                .isInstanceOf(KnowledgeContextSelector.ContextTooLongException.class);
    }
}
