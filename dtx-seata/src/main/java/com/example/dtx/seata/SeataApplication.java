package com.example.dtx.seata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Seata AT demo. Requires the infrastructure from {@code infra/docker-compose.yml}
 * (MySQL + seata-server); it is not part of the runnable test suite because AT
 * needs a live transaction coordinator.
 */
@SpringBootApplication
public class SeataApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeataApplication.class, args);
    }
}
