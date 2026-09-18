package com.travelplan.travel.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the low-level {@link RestClient} travel-service's search components
 * (indexing hook, search service, index initializer) are wired against.
 *
 * <p>Per docs/lets-travel-architecture-decisions.md §6, this service talks to
 * Elasticsearch through the LOW-LEVEL {@code RestClient} (raw JSON requests),
 * not the typed {@code co.elastic.clients:elasticsearch-java} builder API nor
 * Spring Data Elasticsearch — see that section for the tradeoff. There is no
 * username/password to configure: the Elasticsearch instance behind the
 * {@code search} Compose profile runs in dev mode with
 * {@code xpack.security.enabled=false} (mirrors Vault's own "dev mode",
 * docs/architecture-decisions.md §5), never exposed outside {@code data-net}.</p>
 *
 * <p><b>Deliberate deviation from {@link Neo4jConnectionConfig}'s hard
 * fail-fast.</b> Neo4j is this service's sole primary store — always
 * required, so a missing {@code NEO4J_HOST}/{@code NEO4J_PORT} is a genuine
 * misconfiguration and application.yml's {@code :?} placeholder refuses to
 * boot. Elasticsearch is optional, additive infra behind its own Compose
 * profile ({@code search}): travel-service must remain fully bootable (CRUD,
 * transports, the pre-existing test suite) when {@code search} is not up and
 * {@code ES_HOST}/{@code ES_PORT} are simply absent from the environment.
 * application.yml therefore gives {@code elasticsearch.host}/{@code .port} a
 * benign default ({@code localhost:9200}) instead of a {@code :?} guard, and
 * this class does NOT probe connectivity at startup — building a
 * {@link RestClient} is lazy, it never opens a socket by itself. An
 * unreachable Elasticsearch only ever surfaces when a search/autocomplete
 * request is actually made (as a 503, not a boot failure) — see
 * {@code DestinationSearchService}.</p>
 */
@Configuration
public class ElasticsearchConnectionConfig {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchConnectionConfig.class);

    @Value("${elasticsearch.host}")
    private String elasticsearchHost;

    @Value("${elasticsearch.port}")
    private String elasticsearchPort;

    @Bean
    public RestClient elasticsearchRestClient() {
        int port = Integer.parseInt(elasticsearchPort);
        log.info("Elasticsearch client configured for http://{}:{} (lazy — not probed at startup)",
                elasticsearchHost, port);
        return RestClient.builder(new HttpHost(elasticsearchHost, port, "http")).build();
    }
}
