package com.petstore.catalog.repository.mongo;

import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shared base for the catalog MongoDB adapter parity tests. Boots a real {@code mongo:7} container
 * so the store's actual queries (locale reads, ordering, pagination, keyword search) run
 * true-to-prod rather than against a fake — the one scoped exception to the repo's hermetic,
 * no-container rule, mirroring the OPC {@code MongoTestBase}.
 *
 * <p><b>Singleton container.</b> Started once in a static initialiser and shared by every test in
 * the package (not one-per-class), reaped at JVM exit. Catalog is read-only so a plain standalone
 * {@code mongod} suffices — no replica set needed (unlike OPC, which needs {@code rs0} for
 * multi-document transactions).
 *
 * <p><b>Docker-less runs skip, never fail.</b> {@code assumeTrue(dockerAvailable)} aborts each test
 * when Docker/Colima is unreachable, so a plain {@code mvn clean install} on a box with no Docker
 * stays green (the JPA {@code CatalogCharacterizationTest} still covers the default profile). On
 * Colima, run with:
 * <pre>
 *   DOCKER_HOST="unix://$HOME/.colima/default/docker.sock" \
 *   TESTCONTAINERS_RYUK_DISABLED=true \
 *   TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
 *   mvn -pl app test -Dtest='MongoCatalog*Test' -DargLine="-Dapi.version=1.44"
 * </pre>
 */
abstract class MongoTestBase {

    private static final boolean DOCKER_AVAILABLE = probeDocker();

    static final MongoDBContainer MONGO = DOCKER_AVAILABLE
            ? new MongoDBContainer(DockerImageName.parse("mongo:7.0"))
            : null;

    static {
        if (DOCKER_AVAILABLE) {
            MONGO.start();   // singleton: started once, reused across all test classes, reaped at JVM exit
        }
    }

    /** The template the adapter/seeder under test use — pointed at the container's DB. */
    MongoTemplate mongo;

    private static boolean probeDocker() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Skip (not fail) every Mongo test when Docker is unreachable — keeps the default build hermetic. */
    @BeforeEach
    void setUp() {
        assumeTrue(DOCKER_AVAILABLE, "Docker not available — skipping catalog MongoDB tests");
        mongo = new MongoTemplate(MongoClients.create(MONGO.getConnectionString()), "petstore_test");
    }

    /** Each test starts from empty collections so ordering/counts are deterministic. */
    @AfterEach
    void cleanCollections() {
        if (DOCKER_AVAILABLE && mongo != null) {
            mongo.getCollectionNames().forEach(mongo::dropCollection);
        }
    }
}
