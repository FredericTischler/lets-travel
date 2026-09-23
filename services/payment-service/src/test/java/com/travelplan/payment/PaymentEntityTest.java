package com.travelplan.payment;

import com.travelplan.payment.entity.Payment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain unit test for a couple of {@link Payment} getters
 * ({@code getCompletedAt}/{@code getDeletedAt}) that no integration test
 * happens to call directly (they assert soft-delete/completion behavior via
 * HTTP status codes and repository queries instead of the entity's own
 * getters) — cheap, direct coverage of otherwise-untouched accessors, no
 * Spring context needed.
 */
class PaymentEntityTest {

    @Test
    void getCompletedAt_isNull_untilStatusBecomesCompleted() {
        Payment payment = new Payment(UUID.randomUUID(), new BigDecimal("10.00"), "USD");

        assertThat(payment.getCompletedAt()).isNull();

        payment.setStatus(Payment.STATUS_COMPLETED);

        assertThat(payment.getCompletedAt()).isNotNull();
    }

    @Test
    void getDeletedAt_reflectsWhatWasSet() {
        Payment payment = new Payment(UUID.randomUUID(), new BigDecimal("10.00"), "USD");
        assertThat(payment.getDeletedAt()).isNull();

        OffsetDateTime deletedAt = OffsetDateTime.now();
        payment.setDeletedAt(deletedAt);

        assertThat(payment.getDeletedAt()).isEqualTo(deletedAt);
    }
}
