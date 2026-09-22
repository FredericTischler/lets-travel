package com.travelplan.identity.repository;

import com.travelplan.identity.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link RefreshToken}.
 *
 * No business logic lives here — only data access. Unlike
 * {@link UserRepository}/{@link ReportRepository} there is no
 * {@code findActiveById}: this table has no {@code deletedAt} (see
 * {@link RefreshToken} javadoc), so every row is a legitimate lookup target
 * and {@link com.travelplan.identity.service.RefreshTokenService} applies the
 * revoked/expired checks itself.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Look up a row by its stored SHA-256 hash — never by the plaintext value. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
