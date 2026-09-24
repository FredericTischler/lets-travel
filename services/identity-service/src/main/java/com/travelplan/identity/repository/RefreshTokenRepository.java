package com.travelplan.identity.repository;

import com.travelplan.identity.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link RefreshToken}.
 *
 * All query methods filter on {@code deleted_at IS NULL} to honour the
 * soft-delete (here, "not yet revoked/redeemed") contract. Expiry is not
 * filtered here — {@code RefreshTokenService} checks {@code expiresAt}
 * itself, since an expired-vs-revoked-vs-unknown token must all collapse to
 * the same generic failure for the caller, and doing that comparison in the
 * service keeps the reason fully in one place. No business logic otherwise.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Query("SELECT t FROM RefreshToken t WHERE t.tokenHash = :tokenHash AND t.deletedAt IS NULL")
    Optional<RefreshToken> findActiveByTokenHash(@Param("tokenHash") String tokenHash);
}
