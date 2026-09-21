package com.procuremind.common.tracing;

/**
 * Names for the contract-id correlation carried across the pipeline.
 *
 * <p>The same contract UUID already travels as the Kafka message key on every topic in the
 * {@code contract.uploaded -> contract.indexed -> contract.analyzed/contract.failed} pipeline,
 * but the key is not visible to log output and is not guaranteed to survive every hop (e.g. a
 * dead-letter republish). Producers additionally stamp it as an explicit header under
 * {@link #HEADER}; consumers restore it into MDC under {@link #MDC_KEY} so every log line for a
 * contract's journey can be grepped across all four services by one id, without relying on the
 * message key or manual string interpolation in each log statement.
 */
public final class CorrelationIds {

    /** Kafka header name carrying the contract UUID as UTF-8 bytes. */
    public static final String HEADER = "X-Contract-Id";

    /** MDC key each consumer restores the contract id into for the duration of processing. */
    public static final String MDC_KEY = "contractId";

    private CorrelationIds() {
    }
}
