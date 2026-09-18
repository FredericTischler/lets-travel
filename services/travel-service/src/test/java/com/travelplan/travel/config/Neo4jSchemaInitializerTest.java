package com.travelplan.travel.config;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.exceptions.TransientException;
import org.neo4j.driver.summary.ResultSummary;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.neo4j.core.Neo4jClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the retry-on-transient-error behaviour of
 * {@link Neo4jSchemaInitializer}.
 *
 * Reproduces, without a real database, the failure observed when two
 * travel-service instances start concurrently against a blank Neo4j:
 * Neo4j's Forseti locking can abort one of the two concurrent
 * {@code CREATE CONSTRAINT} transactions with
 * {@code Neo.TransientError.Transaction.DeadlockDetected}, which
 * Spring Data Neo4j translates to a {@link TransientDataAccessResourceException}.
 * That failure must be retried instead of crashing the whole Spring context.
 */
class Neo4jSchemaInitializerTest {

    private final Neo4jSchemaInitializer initializer = new Neo4jSchemaInitializer();

    @Test
    void retriesOnceOnTransientErrorThenSucceeds() throws Exception {
        Neo4jClient neo4jClient = mock(Neo4jClient.class);
        Neo4jClient.UnboundRunnableSpec runnableSpec = mock(Neo4jClient.UnboundRunnableSpec.class);
        ResultSummary resultSummary = mock(ResultSummary.class);

        when(neo4jClient.query(anyString())).thenReturn(runnableSpec);
        // First call on each of the 4 statements fails with a transient error,
        // the retry that follows succeeds.
        when(runnableSpec.run())
                .thenThrow(transientError())
                .thenReturn(resultSummary)
                .thenThrow(transientError())
                .thenReturn(resultSummary)
                .thenThrow(transientError())
                .thenReturn(resultSummary)
                .thenThrow(transientError())
                .thenReturn(resultSummary);

        CommandLineRunner runner = initializer.ensureDestinationIdUniqueConstraint(neo4jClient);

        runner.run();

        // 4 constraints (Destination/Activity/Accommodation/TravelerRef), each
        // retried once after a transient failure: 8 executions total.
        verify(runnableSpec, times(8)).run();
    }

    @Test
    void givesUpAfterExhaustingRetriesAndRethrowsTheTransientError() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class);
        Neo4jClient.UnboundRunnableSpec runnableSpec = mock(Neo4jClient.UnboundRunnableSpec.class);

        when(neo4jClient.query(anyString())).thenReturn(runnableSpec);
        // Every attempt fails: retries must be bounded, not infinite.
        when(runnableSpec.run()).thenThrow(transientError());

        CommandLineRunner runner = initializer.ensureDestinationIdUniqueConstraint(neo4jClient);

        assertThatThrownBy(runner::run).isInstanceOf(TransientDataAccessResourceException.class);
    }

    @Test
    void doesNotRetryNonTransientErrors() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class);
        Neo4jClient.UnboundRunnableSpec runnableSpec = mock(Neo4jClient.UnboundRunnableSpec.class);

        when(neo4jClient.query(anyString())).thenReturn(runnableSpec);
        RuntimeException notTransient = new IllegalStateException("invalid Cypher, not a transient failure");
        when(runnableSpec.run()).thenThrow(notTransient);

        CommandLineRunner runner = initializer.ensureDestinationIdUniqueConstraint(neo4jClient);

        assertThatThrownBy(runner::run).isSameAs(notTransient);
        // Fails fast on the very first statement: no retry attempted for a non-transient error.
        verify(runnableSpec, times(1)).run();
    }

    private static TransientDataAccessResourceException transientError() {
        TransientException driverException = new TransientException(
                "Neo.TransientError.Transaction.DeadlockDetected",
                "simulated concurrent schema initialization deadlock");
        return new TransientDataAccessResourceException(driverException.getMessage(), driverException);
    }
}
