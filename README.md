# MongoDB Spring Discovery

A Spring Boot starter library that provides service discovery capabilities using MongoDB as the registry. It enables automatic detection and tracking of service instances in distributed environments through heartbeat mechanisms and change stream monitoring.

## Features

- **Automatic Instance Discovery**: Automatically detects and tracks running service instances
- **Heartbeat-based Health Monitoring**: Uses periodic heartbeats to maintain instance health status
- **TTL-based Cleanup**: Automatically removes stale instance records using MongoDB TTL indexes
- **Real-time Notifications**: Leverages MongoDB change streams for instant instance state change notifications
- **Distributed Coordination**: Enables coordination between distributed service instances
- **Spring Integration**: Seamless integration with Spring Boot applications
- **Configurable Timeouts**: Customizable heartbeat intervals and timeout thresholds

## Installation

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.mzinx</groupId>
    <artifactId>mongodb-spring-discovery</artifactId>
    <version>0.0.3</version>
</dependency>
```

Also add the change stream dependency for real-time notifications:

```xml
<dependency>
    <groupId>com.mzinx</groupId>
    <artifactId>mongodb-spring-change-stream</artifactId>
    <version>0.0.3</version>
</dependency>
```

## Configuration

Configure the discovery service using the following properties in your `application.properties` or `application.yml`:

```properties
# Enable/disable discovery functionality (default: true)
discovery.enabled=true

# Hostname for this instance (default: system HOSTNAME or localhost)
discovery.hostname=my-service-instance

# MongoDB collection for storing instance information (default: _instances)
discovery.collection=_instances

# Heartbeat interval in milliseconds (default: 5000)
discovery.heartbeat.interval=5000

# Maximum number of missed heartbeats before considering instance dead (default: 10)
discovery.heartbeat.max=10
```

## Usage

### Basic Setup

The discovery service starts automatically with your Spring Boot application. No additional code is required for basic instance registration and discovery.

```java
@SpringBootApplication
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

### Accessing Instance Information

```java
@Autowired
private Set<String> instances; // Injected by DiscoveryAutoConfig

public void checkInstances() {
    System.out.println("Active instances: " + instances);
    System.out.println("Total instances: " + instances.size());
}
```

### Listening to Instance Changes

```java
@Autowired
private ChangeStreamService<Document> changeStreamService;

@PostConstruct
public void setupInstanceListener() {
    // The discovery service automatically publishes instance change events
    // You can subscribe to these events if needed
    changeStreamService.subscribe(event -> {
        if (event.getNamespace().getCollectionName().equals("_instances")) {
            System.out.println("Instance change detected: " + event.getOperationType());
        }
    });
}
```

### Custom Instance Metadata

You can extend the basic discovery by storing additional metadata with each instance:

```java
@Autowired
private MongoTemplate mongoTemplate;

@Autowired
private DiscoveryProperties discoveryProperties;

public void registerWithMetadata() {
    Document instanceDoc = new Document("_id", discoveryProperties.getHostname())
        .append("at", new Date())
        .append("version", "1.0.0")
        .append("services", Arrays.asList("api", "worker"))
        .append("region", "us-east-1");

    mongoTemplate.getCollection(discoveryProperties.getCollection())
        .replaceOne(
            Filters.eq("_id", discoveryProperties.getHostname()),
            instanceDoc,
            new ReplaceOptions().upsert(true)
        );
}
```

## How It Works

### Heartbeat Mechanism

1. Each service instance sends periodic heartbeats to the discovery collection
2. Heartbeats include the instance ID (hostname) and timestamp
3. TTL indexes automatically remove stale heartbeats after the configured timeout

### Instance Tracking

1. The discovery service maintains an in-memory set of active instances
2. A change stream monitors the discovery collection for real-time updates
3. Instance additions, updates, and deletions are reflected immediately

### Failure Detection

- If an instance stops sending heartbeats, its record expires due to TTL
- The change stream detects the deletion and removes it from the active instances set
- Other services are notified of the instance failure in real-time

## Integration with Other Modules

This discovery module works seamlessly with the change stream module to provide:

- **Auto-Recovery**: Failed instances can be detected and processing redistributed
- **Auto-Scaling**: New instances can be discovered and included in load balancing
- **Distributed Coordination**: Multiple instances can coordinate through shared state

## Monitoring and Observability

The service provides logging for:

- Instance registrations and deregistrations
- Heartbeat operations
- Change stream events
- TTL index management

Enable debug logging to monitor discovery operations:

```properties
logging.level.com.mzinx.mongodb.discovery=DEBUG
```

## Best Practices

### Heartbeat Configuration

- Set `discovery.heartbeat.interval` based on your application's tolerance for stale data
- Configure `discovery.heartbeat.max` to allow for temporary network issues
- Consider network latency when setting timeout values

### Collection Management

- Use a dedicated MongoDB collection for discovery (default: `_instances`)
- Ensure the collection has appropriate read/write permissions
- Monitor collection size and growth over time

### Instance Identification

- Use unique, descriptive hostnames for instance identification
- Include environment information (dev/staging/prod) in hostnames if needed
- Avoid using IP addresses that may change

## License

This project is licensed under the Apache License, Version 2.0 - see the [LICENSE](LICENSE) file for details.
