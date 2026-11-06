package com.tianji.search.config;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.RestHighLevelClientBuilder;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** Uses the ES 7.17 client in ES 8 API compatibility mode. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ElasticsearchProperties.class)
public class ElasticsearchClientConfiguration {

    @Bean(destroyMethod = "close")
    public RestHighLevelClient restHighLevelClient(ElasticsearchProperties properties) {
        HttpHost[] hosts = properties.getUris().stream().map(HttpHost::create).toArray(HttpHost[]::new);
        RestClientBuilder builder = RestClient.builder(hosts)
                .setRequestConfigCallback(request -> request
                        .setConnectTimeout(Math.toIntExact(properties.getConnectionTimeout().toMillis()))
                        .setSocketTimeout(Math.toIntExact(properties.getSocketTimeout().toMillis())));
        if (StringUtils.hasText(properties.getPathPrefix())) {
            builder.setPathPrefix(properties.getPathPrefix());
        }
        builder.setHttpClientConfigCallback(http -> {
            http.setDefaultIOReactorConfig(org.apache.http.impl.nio.reactor.IOReactorConfig.custom()
                    .setSoKeepAlive(properties.isSocketKeepAlive()).build());
            if (StringUtils.hasText(properties.getUsername())) {
                BasicCredentialsProvider credentials = new BasicCredentialsProvider();
                credentials.setCredentials(AuthScope.ANY,
                        new UsernamePasswordCredentials(properties.getUsername(), properties.getPassword()));
                http.setDefaultCredentialsProvider(credentials);
            }
            return http;
        });
        return new RestHighLevelClientBuilder(builder.build())
                .setApiCompatibilityMode(true)
                .build();
    }

    // The high-level client owns this resource and closes it once.
    @Bean(destroyMethod = "")
    public RestClient elasticsearchRestClient(RestHighLevelClient client) {
        return client.getLowLevelClient();
    }
}
