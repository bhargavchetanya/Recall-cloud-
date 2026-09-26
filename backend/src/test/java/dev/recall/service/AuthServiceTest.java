package dev.recall.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.recall.domain.Role;
import dev.recall.domain.Team;
import dev.recall.domain.User;
import dev.recall.repository.TeamRepository;
import dev.recall.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceTest {

    private TeamRepository teams;
    private UserRepository users;
    private PasswordEncoder passwords;
    private TokenService tokens;
    private InviteCodeGenerator inviteCodes;
    private AuthService service;

    private final Instant expiry = Instant.parse("2030-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        teams = mock(TeamRepository.class);
        users = mock(UserRepository.class);
        passwords = mock(PasswordEncoder.class);
        tokens = mock(TokenService.class);
        inviteCodes = mock(InviteCodeGenerator.class);
        service = new AuthService(teams, users, passwords, tokens, inviteCodes);

        when(teams.save(any(Team.class))).thenAnswer(inv -> withId(inv.getArgument(0)));
        when(users.save(any(User.class))).thenAnswer(inv -> withId(inv.getArgument(0)));
        when(passwords.encode(any())).thenAnswer(inv -> "hashed:" + inv.getArgument(0));
        when(passwords.matches(any(), any())).thenAnswer(inv -> ("hashed:" + inv.getArgument(0)).equals(inv.getArgument(1)));
        when(tokens.issue(any(User.class))).thenReturn(new TokenService.IssuedToken("jwt", expiry));
    }

    @Test
    void registerCreatesTeamAndOwnerWithNormalizedEmail() {
        when(inviteCodes.next()).thenReturn("ABCDEFGHJK");

        AuthService.Session session = service.register("  Core Team ", " Ada@Example.COM ", "password1", " Ada ");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ada@example.com");
        assertThat(saved.getValue().getDisplayName()).isEqualTo("Ada");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.OWNER);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed:password1");
        assertThat(saved.getValue().getTeamId()).isEqualTo(session.teamId());
        assertThat(session.inviteCode()).isEqualTo("ABCDEFGHJK");
        assertThat(session.token()).isEqualTo("jwt");
        assertThat(session.expiresAt()).isEqualTo(expiry);
    }

    @Test
    void registerRetriesWhenInviteCodeCollides() {
        when(inviteCodes.next()).thenReturn("TAKENTAKEN", "FREEFREEFR");
        when(teams.existsByInviteCode("TAKENTAKEN")).thenReturn(true);

        assertThat(service.register("t", "a@b.co", "password1", "A").inviteCode()).isEqualTo("FREEFREEFR");
    }

    @Test
    void registerGivesUpAfterRepeatedInviteCollisions() {
        when(inviteCodes.next()).thenReturn("TAKENTAKEN");
        when(teams.existsByInviteCode("TAKENTAKEN")).thenReturn(true);

        assertThatThrownBy(() -> service.register("t", "a@b.co", "password1", "A"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void registerRejectsExistingEmail() {
        when(users.existsByEmailIgnoreCase("ada@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register("t", "ADA@example.com", "password1", "Ada"))
                .isInstanceOf(AuthExceptions.EmailAlreadyRegistered.class);
        verify(teams, never()).save(any());
    }

    @Test
    void joinAddsMemberToInvitingTeam() {
        Team team = withId(new Team("core", "ABCDEFGHJK"));
        when(teams.findByInviteCode("ABCDEFGHJK")).thenReturn(Optional.of(team));

        AuthService.Session session = service.join(" abcdefghjk ", "bob@example.com", "password1", "Bob");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.MEMBER);
        assertThat(saved.getValue().getTeamId()).isEqualTo(team.getId());
        assertThat(session.teamId()).isEqualTo(team.getId());
        assertThat(session.inviteCode()).isNull();
    }

    @Test
    void joinRejectsUnknownInviteCode() {
        when(teams.findByInviteCode(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.join("NOPE", "bob@example.com", "password1", "Bob"))
                .isInstanceOf(AuthExceptions.InvalidInviteCode.class);
    }

    @Test
    void joinRejectsExistingEmail() {
        when(teams.findByInviteCode("ABCDEFGHJK")).thenReturn(Optional.of(withId(new Team("core", "ABCDEFGHJK"))));
        when(users.existsByEmailIgnoreCase("bob@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.join("ABCDEFGHJK", "bob@example.com", "password1", "Bob"))
                .isInstanceOf(AuthExceptions.EmailAlreadyRegistered.class);
    }

    @Test
    void loginIssuesTokenForCorrectPassword() {
        User user = withId(new User(UUID.randomUUID(), "ada@example.com", "Ada", "hashed:password1", Role.OWNER));
        when(users.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(user));

        AuthService.Session session = service.login(" ADA@example.com", "password1");

        assertThat(session.token()).isEqualTo("jwt");
        assertThat(session.teamId()).isEqualTo(user.getTeamId());
    }

    @Test
    void loginRejectsWrongPassword() {
        User user = withId(new User(UUID.randomUUID(), "ada@example.com", "Ada", "hashed:password1", Role.OWNER));
        when(users.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login("ada@example.com", "wrong-password"))
                .isInstanceOf(AuthExceptions.InvalidCredentials.class);
    }

    @Test
    void loginRejectsUnknownEmailTheSameWay() {
        when(users.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("nobody@example.com", "password1"))
                .isInstanceOf(AuthExceptions.InvalidCredentials.class)
                .hasMessage(new AuthExceptions.InvalidCredentials().getMessage());
    }

    private static <T> T withId(T entity) {
        if (ReflectionTestUtils.getField(entity, "id") == null) {
            ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        }
        return entity;
    }
}
