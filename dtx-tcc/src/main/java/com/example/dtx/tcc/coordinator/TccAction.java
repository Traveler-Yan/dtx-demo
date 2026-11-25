package com.example.dtx.tcc.coordinator;

/** A single TCC phase action. Throwing means "this phase failed". */
@FunctionalInterface
public interface TccAction {
    void run() throws Exception;
}
