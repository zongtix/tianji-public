package com.tianji.aigc.memory;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "TJ_AIGC_TEST_MONGODB_URI", matches = ".+")
class MongoChatMemoryIntegrationTest extends ChatMemoryRepositoryContract {
    private static MongoClient client;
    private static MongoTemplate template;

    @BeforeAll
    static void connect() {
        String uri = System.getenv("TJ_AIGC_TEST_MONGODB_URI");
        client = MongoClients.create(uri);
        template = new MongoTemplate(client, new ConnectionString(uri).getDatabase());
    }

    @AfterAll
    static void disconnect() {
        if (client != null) client.close();
    }

    @Override
    protected ChatMemoryRepository openRepository() {
        return new MongoChatMemoryRepository(template);
    }
}
