package com.travelplan.payment.dto;

/**
 * Response body for {@code DELETE /payments/by-user/{userId}}.
 *
 * Reports how many active payments were soft-deleted for the given user, so
 * the caller (see {@link com.travelplan.payment.controller.PaymentController})
 * can confirm the bulk operation's effect without needing the list of ids.
 */
public class DeleteByUserResponse {

    private final int deletedCount;

    public DeleteByUserResponse(int deletedCount) {
        this.deletedCount = deletedCount;
    }

    public int getDeletedCount() {
        return deletedCount;
    }
}