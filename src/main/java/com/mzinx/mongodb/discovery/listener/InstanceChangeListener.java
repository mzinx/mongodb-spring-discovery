package com.mzinx.mongodb.discovery.listener;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mzinx.mongodb.changestream.InstanceRegistry;
import com.mzinx.mongodb.changestream.listener.ChangeStreamListener;
import com.mzinx.mongodb.changestream.service.ChangeStreamService;

/**
 * Keeps the {@link InstanceRegistry} current from heartbeat change stream
 * events on the instance collection, and republishes the raw events so other
 * components (e.g. the change stream coordination) can react to instance
 * lifecycle changes.
 */
@Component
public class InstanceChangeListener implements ChangeStreamListener<Document> {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final InstanceRegistry instanceRegistry;
    private final ChangeStreamService<Document> changeStreamService;

    InstanceChangeListener(InstanceRegistry instanceRegistry, ChangeStreamService<Document> changeStreamService) {
        this.instanceRegistry = instanceRegistry;
        this.changeStreamService = changeStreamService;
    }

    @Override
    public void onEvent(String streamId, java.util.Map<String, Object> attributes,
            ChangeStreamDocument<Document> event) {
        String instance = event.getDocumentKey().getString("_id").getValue();
        switch (event.getOperationType()) {
            case INSERT:
                this.instanceRegistry.add(instance);
                break;
            case DELETE:
                this.instanceRegistry.remove(instance);
                break;
            default:
        }
        changeStreamService.publish(event);

    }
}
