package com.tianji.aigc.knowledge.material;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CourseMaterialAccessTest {
    private final CourseMaterialAccess access = CourseMaterialAccess.fromClasspath("course-materials/manifest.json");

    @Test
    void accessBuildsFilterOnlyForPublishedPublicDemoOfRequestedCourse() {
        var decision = access.authorize(1L, "910000000000010005");
        assertThat(decision.status()).isEqualTo(CourseMaterialAccess.Status.OK);
        assertThat(decision.filterExpression()).contains("courseId");
        assertThat(decision.filterExpression()).contains("910000000000010005");
        assertThat(decision.filterExpression()).contains("materialId");
        assertThat(decision.filterExpression()).contains("redis-hotspot-faq-01");
        assertThat(decision.filterExpression()).doesNotContain("mysql-index-faq-01");
    }

    @Test
    void unpaidOrRestrictedMaterialIsDeniedWithoutEmptyFallback() {
        var decision = access.authorize(1L, "999");
        assertThat(decision.status()).isEqualTo(CourseMaterialAccess.Status.ACCESS_DENIED);
        assertThat(decision.filterExpression()).isNull();
    }

    @Test
    void missingUserIdIsDeniedWithoutEmptyFallback() {
        var decision = access.authorize(null, "910000000000010005");
        assertThat(decision.status()).isEqualTo(CourseMaterialAccess.Status.ACCESS_DENIED);
        assertThat(decision.filterExpression()).isNull();
    }
}
