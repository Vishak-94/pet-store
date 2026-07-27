package com.petstore.opc.repository.mongo;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shared base for the MongoDB adapter integration tests. Boots a real {@code mongo:7}
 * container — Testcontainers' {@link MongoDBContainer} starts it as a single-node replica
 * set, so multi-document transactions and the {@code @Version} optimistic-lock conditional
 * update behave exactly as against the {@code docker-compose} runtime.
 *
 * <p><b>Singleton-container pattern.</b> The container is a manually-started {@code static}
 * singleton started ONCE in a static initialiser and shared by <em>every</em> test class in this
 * package (not one-per-class as the JUnit {@code @Container} lifecycle would give). The JVM reaps
 * it at exit. This matters on Colima: booting five separate containers in one {@code mvn} run
 * intermittently tripped a port-forwarding flake ({@code Connection refused} on a mapped port,
 * then 30s-per-op driver selector timeouts that stalled the whole build). One shared container
 * removes that failure mode and cuts suite time — while still exercising the real store.
 *
 * <p>Needs Docker/Colima available at test time (unlike the {@code @DataJpaTest} suite, which is
 * fully in-VM). This is the deliberate, scoped exception to the "hermetic, no-container" rule —
 * limited to the Mongo adapter tests, so the store's real queries (sales aggregation, the version
 * conflict, the outbox drain/park) are exercised true-to-prod rather than against a fake.
 *
 * <p>The {@code assumeTrue(dockerAvailable)} guard keeps the default {@code mvn clean install}
 * green on a machine (or CI stage) with no reachable Docker — every test is <b>skipped</b> (the
 * assumption aborts it), not failed — so the rest of the suite stays hermetic. On Colima, run with:
 * <pre>
 *   DOCKER_HOST="unix://$HOME/.colima/default/docker.sock" \
 *   TESTCONTAINERS_RYUK_DISABLED=true \
 *   TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
 *   mvn -pl app test -Dtest='Mongo*Test' -DargLine="-Dapi.version=1.44"
 * </pre>
 * ({@code api.version} pins the docker-java client version so a very new engine doesn't reject the
 * default 1.32; the socket override + Ryuk-disabled are the standard Colima bind-mount workaround.)
 */
abstract class MongoTestBase {

    /** True once, so the Docker probe (which itself can be slow) runs a single time for the suite. */
    private static final boolean DOCKER_AVAILABLE = probeDocker();

    /**
     * One shared container for the whole package. Started only when Docker is reachable so the probe
     * (not a container boot) is what decides skip-vs-run — otherwise constructing/starting it on a
     * Docker-less box would throw before the {@code assumeTrue} guard could skip the test.
     */
    static final MongoDBContainer MONGO = DOCKER_AVAILABLE
            ? new MongoDBContainer(DockerImageName.parse("mongo:7.0"))
            : null;

    static {
        if (DOCKER_AVAILABLE) {
            MONGO.start();   // singleton: started once, reused across all test classes, reaped at JVM exit
        }
    }

    private static boolean probeDocker() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry registry) {
        // Only read when a container exists; on a Docker-less run the tests are skipped before use.
        registry.add("spring.data.mongodb.uri",
                () -> DOCKER_AVAILABLE ? MONGO.getReplicaSetUrl() : "mongodb://localhost:27017/test");
    }

    @Autowired
    MongoTemplate mongo;

    /** Skip (not fail) every Mongo IT when Docker is unreachable — keeps the default build hermetic. */
    @org.junit.jupiter.api.BeforeEach
    void requireDocker() {
        assumeTrue(DOCKER_AVAILABLE, "Docker not available — skipping MongoDB integration tests");
    }

    /** Each test starts from empty collections so ordering/counts are deterministic. */
    @AfterEach
    void cleanCollections() {
        if (DOCKER_AVAILABLE) {
            mongo.getCollectionNames().forEach(mongo::dropCollection);
        }
    }
}
