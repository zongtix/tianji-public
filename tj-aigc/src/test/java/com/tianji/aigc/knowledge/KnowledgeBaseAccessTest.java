package com.tianji.aigc.knowledge;

import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeBaseAccessTest {
    private final KnowledgeBaseAccess access = new KnowledgeBaseAccess();

    @BeforeEach
    void setUp() {
        UserContext.setUser(42L);
        access.setEnabled(true);
        access.setPublicBases(Map.of(
                "tianji-points", List.of("tianji-points-guide"),
                "tianji-coupons", List.of("tianji-coupon-guide")));
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void couponsFilterContainsOnlyCouponGuide() {
        assertThat(access.requireFilter("tianji-coupons"))
                .isEqualTo("documentId in ['tianji-coupon-guide']");
        assertThat(access.requireFilter("tianji-coupons")).doesNotContain("tianji-points-guide");
    }

    @Test
    void unknownKnowledgeBaseIdIsRejected() {
        assertThatThrownBy(() -> access.requireFilter("tianji-unknown"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("知识库不存在或无权访问");
    }

    @Test
    void anonymousUserCannotAccessKnowledgeBase() {
        UserContext.removeUser();
        assertThatThrownBy(() -> access.requireFilter("tianji-coupons"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("请先登录");
    }

    @Test
    void filterForPointsReturnsDocumentFilterWithoutLogin() {
        UserContext.removeUser();
        assertThat(access.filterFor("tianji-points"))
                .isEqualTo("documentId in ['tianji-points-guide']");
    }

    @Test
    void filterForUnknownBaseReturnsNullWithoutLogin() {
        UserContext.removeUser();
        assertThat(access.filterFor("tianji-unknown")).isNull();
    }

    @Test
    void filterForIgnoresEnabledFlag() {
        access.setEnabled(false);
        UserContext.removeUser();
        assertThat(access.filterFor("tianji-points"))
                .isEqualTo("documentId in ['tianji-points-guide']");
    }

    @Test
    void requireFilterMatchesFilterForWhenAuthorized() {
        assertThat(access.requireFilter("tianji-points")).isEqualTo(access.filterFor("tianji-points"));
    }

    @Test
    void publishedDocumentIdsAreDisjointAcrossBases() {
        var seen = new HashSet<String>();
        for (var docs : access.getPublicBases().values()) {
            for (String documentId : docs) {
                assertThat(seen.add(documentId))
                        .as("documentId %s must not appear in multiple knowledge bases", documentId)
                        .isTrue();
            }
        }
        assertThat(seen).containsExactlyInAnyOrder("tianji-points-guide", "tianji-coupon-guide");
    }
}
