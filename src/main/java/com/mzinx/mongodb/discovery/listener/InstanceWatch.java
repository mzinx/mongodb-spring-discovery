package com.mzinx.mongodb.discovery.listener;

import java.util.Set;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mzinx.mongodb.changestream.listener.ChangeStreamListener;
import com.mzinx.mongodb.changestream.service.ChangeStreamService;

@Component
public class InstanceWatch<T> implements ChangeStreamListener<Document> {
    Logger logger = LoggerFactory.getLogger(getClass());

    private final Set<String> instances;
    private final ChangeStreamService<Document> changeStreamService;

    InstanceWatch(Set<String> instances, ChangeStreamService<Document> changeStreamService) {
        this.instances = instances;
        this.changeStreamService = changeStreamService;
    }

    public void execute(ChangeStreamDocument<Document> e) {
        String instance = e.getDocumentKey().getString("_id").getValue();
        switch (e.getOperationType()) {
            case INSERT:
                this.instances.add(instance);
                break;
            case DELETE:
                this.instances.remove(instance);
                break;
            default:
        }
        changeStreamService.publish(e);

    }
}
