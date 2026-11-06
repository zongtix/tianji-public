package com.tianji.aigc.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeImportDocumentsTest {
    @Test
    void defaultsToPointsAndExposesCouponsWithoutSharedDocumentIds() {
        var points = KnowledgeImportCommand.requireDocument(null);
        var coupons = KnowledgeImportCommand.requireDocument("coupons");
        assertThat(points.documentId()).isEqualTo("tianji-points-guide");
        assertThat(points.resourcePath()).isEqualTo("knowledge/tianji-points.md");
        assertThat(points.probes()).hasSize(3);
        assertThat(coupons.documentId()).isEqualTo("tianji-coupon-guide");
        assertThat(coupons.resourcePath()).isEqualTo("knowledge/tianji-coupons.md");
        assertThat(coupons.title()).contains("优惠券");
        assertThat(coupons.probes()).extracting(KnowledgeImportCommand.Probe::expectedChapter)
                .containsExactly("优惠券类型与金额单位", "有效期", "兑换码");
        assertThat(points.documentId()).isNotEqualTo(coupons.documentId());
    }

    @Test
    void rejectsUnknownDocumentKey() {
        assertThatThrownBy(() -> KnowledgeImportCommand.requireDocument("faq"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("faq");
    }

    @Test
    void importDocumentsMustBeListedInPublicBasesMirror() {
        KnowledgeImportCommand.requirePublishedDocument(KnowledgeImportCommand.requireDocument("points"));
        KnowledgeImportCommand.requirePublishedDocument(KnowledgeImportCommand.requireDocument("coupons"));
        var seen = new HashSet<String>();
        for (var docs : KnowledgeImportCommand.PUBLIC_BASES.values()) {
            for (String documentId : docs) {
                assertThat(seen.add(documentId)).isTrue();
            }
        }
        assertThat(seen).containsExactlyInAnyOrder("tianji-points-guide", "tianji-coupon-guide");
    }

    @Test
    void publicBasesMirrorMatchesApplicationYaml() throws Exception {
        String yaml = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);
        assertThat(yaml).contains("tianji-points:", "tianji-points-guide", "tianji-coupons:", "tianji-coupon-guide");
        for (var entry : KnowledgeImportCommand.PUBLIC_BASES.entrySet()) {
            assertThat(yaml).contains(entry.getKey() + ":");
            for (String documentId : entry.getValue()) {
                assertThat(yaml).contains(documentId);
            }
        }
    }

    @Test
    void rejectsUnpublishedDocumentId() {
        var orphan = new KnowledgeImportCommand.DocumentSpec(
                "orphan", "knowledge/missing.md", "tianji-orphan-guide", "孤儿", java.util.List.of());
        assertThatThrownBy(() -> KnowledgeImportCommand.requirePublishedDocument(orphan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public-bases");
    }
}
