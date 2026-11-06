package com.tianji.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.transfer.TransferManager;
import com.qcloud.vod.VodUploadClient;
import com.tencentcloudapi.vod.v20180717.VodClient;
import com.tianji.media.domain.po.Media;
import com.tianji.media.enums.FileStatus;
import com.tianji.media.storage.IFileStorage;
import com.tianji.media.storage.IMediaStorage;
import com.tianji.media.task.PullEventTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "TJ_UPGRADE_CONTROLLED_SERVICES", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "spring.profiles.active=upgrade-test",
        "spring.cloud.nacos.server-addr=127.0.0.1:8848",
        "spring.cloud.nacos.config.namespace=tianji-platform-upgrade-test-boot3",
        "spring.cloud.nacos.discovery.namespace=tianji-platform-upgrade-test-boot3",
        "spring.cloud.nacos.discovery.register-enabled=false", "seata.enabled=false",
        "tj.jdbc.database=tj_upgrade_media", "tj.xxl-job.executor.port=0", "tj.xxl-job.admin.address=",
        "tj.tencent.secret-id=upgrade-test-disabled", "tj.tencent.secret-key=upgrade-test-disabled"})
@DirtiesContext
class MediaControlledIntegrationTest {
    @MockitoBean VodClient vod;
    @MockitoBean VodUploadClient upload;
    @MockitoBean COSClient cos;
    @MockitoBean TransferManager transfer;
    @MockitoBean IFileStorage files;
    @MockitoBean IMediaStorage storage;
    @MockitoBean PullEventTask polling;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach void assertIsolation() {
        assertEquals("tj_upgrade_media", jdbc.queryForObject("SELECT DATABASE()", String.class));
    }

    @Test void actualHttpAndDatabasePreserveUploadIdempotency() throws Exception {
        String fileId = "upg-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Media media = new Media().setFileId(fileId).setFilename("Upgrade Fixture.mp4")
                .setStatus(FileStatus.UPLOADED).setSize(1024L).setDuration(10F)
                .setMediaUrl("/test/fixture.mp4").setCoverUrl("/test/fixture.png")
                .setCreater(900000002L).setUpdater(900000002L).setDeleted(0);
        when(storage.queryMediaInfos(fileId)).thenReturn(List.of(media));
        try {
            JsonNode first = json.readTree(http.exchange("/medias", HttpMethod.POST,
                    request(Map.of("fileId", fileId)), String.class).getBody());
            assertTrue(first.hasNonNull("id"));
            JsonNode second = json.readTree(http.exchange("/medias", HttpMethod.POST,
                    request(Map.of("fileId", fileId)), String.class).getBody());
            assertEquals(first.get("id"), second.get("id"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media WHERE file_id=?", Integer.class, fileId));
        } finally {
            jdbc.update("DELETE FROM media WHERE file_id=?", fileId);
        }
        verifyNoInteractions(vod, cos, upload);
    }

    @Test void mediaApiAndOpenApiStartWithoutCloudCalls() throws Exception {
        var response = http.exchange("/medias?pageNo=1&pageSize=10", HttpMethod.GET, request(null), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(json.readTree(response.getBody()).get("list").isArray());
        assertTrue(json.readTree(http.getForObject("/v3/api-docs/default", String.class)).has("openapi"));
        verifyNoInteractions(vod, cos, upload);
    }

    private HttpEntity<?> request(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("user-info", "900000002");
        return new HttpEntity<>(body, headers);
    }
}
