package com.travelplan.identity;

import com.travelplan.identity.config.BootstrapAdminInitializer;
import com.travelplan.identity.dto.CreateUserRequest;
import com.travelplan.identity.entity.User;
import com.travelplan.identity.repository.UserRepository;
import com.travelplan.identity.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fail-fast contract of the bootstrap-admin variables, and the service-level
 * rules of {@link UserService#bootstrapAdminIfAbsent}, without a Spring context
 * or a container. Mocks stand in for collaborators here only; the behaviour
 * against a real PostgreSQL (creation, login, idempotence) is in
 * {@link BootstrapAdminIntegrationTest}.
 */
class BootstrapAdminInitializerTest {

    private static final String GOOD_PASSWORD = "a-long-enough-password";

    private final UserService userService = mock(UserService.class);

    @Test
    void bothVariablesUnsetMeansFeatureOffAndNothingIsCreated() {
        new BootstrapAdminInitializer(userService, "", "").run(null);
        new BootstrapAdminInitializer(userService, "  ", "  ").run(null);

        verify(userService, never()).bootstrapAdminIfAbsent(anyString(), anyString());
    }

    @Test
    void onlyOneOfTheTwoVariablesRefusesToStart() {
        assertThatThrownBy(() -> new BootstrapAdminInitializer(userService, "admin@example.com", "").run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be set together");
        assertThatThrownBy(() -> new BootstrapAdminInitializer(userService, "", GOOD_PASSWORD).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be set together");
    }

    @Test
    void aWeakPasswordOrABadEmailRefusesToStartWithoutEchoingTheSecret() {
        assertThatThrownBy(() -> new BootstrapAdminInitializer(userService, "admin@example.com", "short-pw").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 12")
                .hasMessageNotContaining("short-pw");
        assertThatThrownBy(() -> new BootstrapAdminInitializer(userService, "not-an-email", GOOD_PASSWORD).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(GOOD_PASSWORD);

        verify(userService, never()).bootstrapAdminIfAbsent(anyString(), anyString());
    }

    @Test
    void aRaceLostToAnotherReplicaIsFineOnceAnAdminIsVisible() {
        when(userService.bootstrapAdminIfAbsent("admin@example.com", GOOD_PASSWORD))
                .thenThrow(new DataIntegrityViolationException("uq_users_email_active"));
        when(userService.hasActiveAdmin()).thenReturn(true);

        new BootstrapAdminInitializer(userService, "admin@example.com", GOOD_PASSWORD).run(null);
    }

    @Test
    void aConstraintViolationWithNoAdminAfterwardsIsNotSwallowed() {
        when(userService.bootstrapAdminIfAbsent("admin@example.com", GOOD_PASSWORD))
                .thenThrow(new DataIntegrityViolationException("something else"));
        when(userService.hasActiveAdmin()).thenReturn(false);

        assertThatThrownBy(() -> new BootstrapAdminInitializer(userService, "admin@example.com", GOOD_PASSWORD).run(null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- UserService#bootstrapAdminIfAbsent / create, against a mocked repository ---

    @Test
    void serviceCreatesAnAdminWithABcryptHashOnlyWhenNoActiveAdminExists() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.existsByRoleAndDeletedAtIsNull("ADMIN")).thenReturn(false);
        when(repository.findByEmailAndDeletedAtIsNull("admin@example.com")).thenReturn(Optional.empty());
        when(repository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        UserService service = new UserService(repository, new BCryptPasswordEncoder());

        assertThat(service.bootstrapAdminIfAbsent("admin@example.com", GOOD_PASSWORD)).isPresent();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo("ADMIN");
        assertThat(saved.getValue().getPasswordHash()).startsWith("$2").doesNotContain(GOOD_PASSWORD);
    }

    @Test
    void serviceDoesNothingWhenAnActiveAdminAlreadyExists() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.existsByRoleAndDeletedAtIsNull("ADMIN")).thenReturn(true);
        UserService service = new UserService(repository, new BCryptPasswordEncoder());

        assertThat(service.bootstrapAdminIfAbsent("admin@example.com", GOOD_PASSWORD)).isEmpty();

        verify(repository, never()).save(any(User.class));
    }

    @Test
    void serviceRefusesWhenTheBootstrapEmailBelongsToANonAdminAndNoAdminExists() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.existsByRoleAndDeletedAtIsNull("ADMIN")).thenReturn(false);
        when(repository.findByEmailAndDeletedAtIsNull("taken@example.com"))
                .thenReturn(Optional.of(new User("taken@example.com", "hash", "TRAVELER")));
        UserService service = new UserService(repository, new BCryptPasswordEncoder());

        assertThatThrownBy(() -> service.bootstrapAdminIfAbsent("taken@example.com", GOOD_PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOOTSTRAP_ADMIN_EMAIL");
        verify(repository, never()).save(any(User.class));
    }

    @Test
    void serviceCreateRejectsAdminForNonAdminCallersBeforeTouchingTheRepository() {
        UserRepository repository = mock(UserRepository.class);
        UserService service = new UserService(repository, new BCryptPasswordEncoder());
        CreateUserRequest request = new CreateUserRequest();
        request.setEmail("x@example.com");
        request.setPassword("secret123");
        request.setRole("ADMIN");

        assertThatThrownBy(() -> service.create(request, false))
                .isInstanceOf(com.travelplan.identity.exception.InsufficientRoleException.class);
        verify(repository, never()).save(any(User.class));
    }
}
