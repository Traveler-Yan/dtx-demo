package com.example.dtx.tcc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * TCC demo application.
 *
 * <p>Everything runs against an in-memory H2 database, so the module is a
 * self-contained playground for the three classic TCC problems:
 * empty rollback, suspension and non-idempotent confirm/cancel.</p>
 */
@SpringBootApplication
public class TccApplication {

    public static void main(String[] args) {
        SpringApplication.run(TccApplication.class, args);
    }
}
