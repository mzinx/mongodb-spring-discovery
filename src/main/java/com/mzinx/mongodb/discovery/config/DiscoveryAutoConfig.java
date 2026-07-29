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
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ChangeStreamPreAndPostImagesOptions;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.changestream.FullDocumentBeforeChange;
import com.mzinx.mongodb.changestream.model.ChangeStream.Mode;
import com.mzinx.mongodb.changestream.model.ChangeStreamConfig;
import com.mzinx.mongodb.changestream.service.ChangeStreamConfigService;

import jakarta.annotation.PostConstruct;

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

    private final DiscoveryProperties discoveryProperties;

    private final MongoTemplate mongoTemplate;

    private final Set<String> instances;

    DiscoveryAutoConfig(DiscoveryProperties discoveryProperties, MongoTemplate mongoTemplate, Set<String> instances) {
        this.discoveryProperties = discoveryProperties;
        this.mongoTemplate = mongoTemplate;
        this.instances = instances;
    }

    private void createIndex(MongoCollection<Document> coll) {
        coll.createIndex(Indexes.descending(INDEX_KEY),
                new IndexOptions()
                        .expireAfter(discoveryProperties.getHeartbeat().getMaxTimeout(), TimeUnit.MILLISECONDS)
                        .name(INDEX_NAME));
    }

    @Autowired
    private ChangeStreamConfigService changeStreamConfigService;

    /**
     * Ensures the instance collection exists with change stream pre- and
     * post-images enabled. The discovery change stream is registered with
     * {@link FullDocumentBeforeChange#REQUIRED}, so without pre-images the
     * change stream cursor fails as soon as a heartbeat document is updated
     * or expires (e.g. on a fresh database where the collection would
     * otherwise be created implicitly by the first heartbeat upsert).
     */
    private void enablePreImages() {
        String collectionName = discoveryProperties.getCollection();
        MongoDatabase db = mongoTemplate.getDb();
        boolean exists = db.listCollectionNames().into(new ArrayList<>()).contains(collectionName);
        if (!exists) {
            try {
                db.createCollection(collectionName, new CreateCollectionOptions()
                        .changeStreamPreAndPostImagesOptions(new ChangeStreamPreAndPostImagesOptions(true)));
                logger.info("Created collection '{}' with changeStreamPreAndPostImages enabled", collectionName);
                return;
            } catch (MongoCommandException e) {
                // 48 = NamespaceExists: created concurrently by another instance,
                // fall through to collMod below.
                if (e.getErrorCode() != 48)
                    throw e;
            }
        }
        try {
            db.runCommand(new Document("collMod", collectionName)
                    .append("changeStreamPreAndPostImages", new Document("enabled", true)));
            logger.info("Enabled changeStreamPreAndPostImages on collection '{}'", collectionName);
        } catch (MongoCommandException e) {
            logger.error("Unable to enable changeStreamPreAndPostImages on collection '{}' (requires the collMod "
                    + "privilege, e.g. the dbAdmin role); the discovery change stream requires pre-images and "
                    + "will fail on update/delete events", collectionName, e);
        }
    }

    @PostConstruct
    private void init() {
        enablePreImages();
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
        changeStreamConfigService.save(ChangeStreamConfig.builder()
                .id("discovery") // unique change stream id
                .collectionName(discoveryProperties.getCollection()) // collection to watch (null = whole database)
                .mode(Mode.BOARDCAST) // BOARDCAST, AUTO_RECOVER or AUTO_SCALE
                .pipeline(List.of(new Document("$match",
                        new Document("operationType", new Document("$in", List.of("insert", "update", "delete"))))))
                .listener("instanceWatch") // ChangeStreamListener bean name
                .fullDocumentBeforeChange(FullDocumentBeforeChange.REQUIRED)
                .enabled(true)
                .build());

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