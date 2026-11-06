package com.tianji.aigc.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchAiSearchFilterExpressionConverter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchExactMetadataFilterConverterTest {
    private final FilterExpressionTextParser parser = new FilterExpressionTextParser();

    @Test
    void documentIdFiltersUseKeywordSubfieldInsteadOfAnalyzedText() {
        var exact = new ElasticsearchExactMetadataFilterConverter();
        var stock = new ElasticsearchAiSearchFilterExpressionConverter();
        String inFilter = exact.convertExpression(parser.parse("documentId in ['tianji-points-guide']"));
        String eqFilter = exact.convertExpression(parser.parse("documentId == 'tianji-coupon-guide'"));
        String hashFilter = exact.convertExpression(parser.parse("contentHash == 'abc'"));

        assertThat(inFilter).contains("metadata.documentId.keyword:");
        assertThat(eqFilter).contains("metadata.documentId.keyword:");
        assertThat(hashFilter).contains("metadata.contentHash.keyword:");
        assertThat(stock.convertExpression(parser.parse("documentId in ['tianji-points-guide']")))
                .contains("metadata.documentId:")
                .doesNotContain("metadata.documentId.keyword:");
    }
}
