package com.mohit.screenbook;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    /*
     * postgis/postgis rather than the official postgres image, because V15
     * creates the postgis extension and the official image does not ship it.
     * Without this every integration test fails inside Flyway, before a single
     * assertion runs.
     *
     * asCompatibleSubstituteFor is required rather than cosmetic.
     * PostgreSQLContainer refuses an image whose name is not "postgres" unless
     * it is told the substitute behaves the same way, which is the check that
     * normally stops somebody pointing a Postgres container at a MySQL image.
     *
     * 18-3.6-alpine keeps Postgres 18, so the tests still run the major
     * version that Neon and docker-compose run.
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(
                DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                        .asCompatibleSubstituteFor("postgres"));
    }

}