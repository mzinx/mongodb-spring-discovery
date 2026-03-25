package com.mzinx.mongodb.discovery.config;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.changestream.FullDocumentBeforeChange;
import com.mzinx.mongodb.changestream.model.ChangeStream;
import com.mzinx.mongodb.changestream.model.ChangeStreamRegistry;
import com.mzinx.mongodb.changestream.model.ChangeStream.Mode;
import com.mzinx.mongodb.changestream.service.ChangeStreamService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@AutoConfiguration
@EnableConfigurationProperties(DiscoveryProperties.class)
@ConditionalOnProperty(prefix = "discovery", name = "enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan("com.mzinx.mongodb.discovery")
@Import(ScanRegistrar.class)
@EnableScheduling
public class DiscoveryAutoConfig {
    Logger logger = LoggerFactory.getLogger(this.getClass());

    private static final String INDEX_KEY = "at";
    private static final String INDEX_NAME = "ttl";

    @Autowired
    private DiscoveryProperties discoveryProperties;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private Set<String> instances;

    @Autowired
    private ChangeStreamService<Document> changeStreamService;

    private void createIndex(MongoCollection<Document> coll) {
        coll.createIndex(Indexes.descending(INDEX_KEY),
                new IndexOptions()
                        .expireAfter(discoveryProperties.getHeartbeat().getMaxTimeout(), TimeUnit.MILLISECONDS)
                        .name(INDEX_NAME));
    }

    ChangeStream<Document> cs;

    @PostConstruct
    private void init() {
        MongoCollection<Document> coll = mongoTemplate.getCollection(discoveryProperties.getCollection());
        try {
            createIndex(coll);
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == 85 || e.getErrorCode() == 86) {
                coll.dropIndex(INDEX_NAME);
                createIndex(coll);
            }
        }
        this.instances.addAll(coll.find().projection(Projections.include("_id")).map(d -> d.getString("_id"))
                .into(new ArrayList<>()));
        cs = ChangeStream.of("discovery", Mode.BOARDCAST,
                List.of(Aggregates.match(
                        Filters.in("operationType", List.of("insert", "update", "delete")))))
                .fullDocumentBeforeChange(FullDocumentBeforeChange.REQUIRED);
        changeStreamService.run(ChangeStreamRegistry.<Document>builder().collectionName(discoveryProperties.getCollection()).body(e -> {
            String instance = e.getDocumentKey().getString("_id").getValue();
            switch (e.getOperationType()) {
                case INSERT:
                    this.instances.add(instance);
                    break;
                case UPDATE:
                    break;
                case DELETE:
                    this.instances.remove(instance);
                    break;
                default:
            }
            changeStreamService.publish(e);
        }).changeStream(cs).build());
    }

    @PreDestroy
    private void clear() {
        if (cs != null)
            cs.setRunning(false);
    }

    @Scheduled(fixedRateString = "#{@discoveryProperties.heartbeat.interval}")
    private void heartbeat() {
        mongoTemplate.getCollection(discoveryProperties.getCollection()).updateOne(
                Filters.eq("_id", discoveryProperties.getHostname()),
                Updates.combine(Updates.set("_id", discoveryProperties.getHostname()),
                        Updates.set(INDEX_KEY, new Date())),
                new UpdateOptions().upsert(true));
    }
}