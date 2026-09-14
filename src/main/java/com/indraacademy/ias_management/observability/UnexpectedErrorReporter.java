package com.indraacademy.ias_management.observability;

public interface UnexpectedErrorReporter {
    void report(String operation, Throwable failure);
}
