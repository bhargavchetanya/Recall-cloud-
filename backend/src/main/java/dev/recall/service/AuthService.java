package dev.recall.service;

import dev.recall.domain.Role;
import dev.recall.domain.Team;
import dev.recall.domain.User;
import dev.recall.repository.TeamRepository;
import dev.recall.repository.UserRepository;
import dev.recall.service.TokenService.IssuedToken;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    /** {@code inviteCode} is only set for the team owner right after registering. */
    public record Session(String token, Instant expiresAt, UUID teamId, String inviteCode) {}

    private static final int MAX_INVITE_ATTEMPTS = 5;

    private final TeamRepository teams;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final InviteCodeGenerator inviteCodes;

    public AuthService(TeamRepository teams, UserRepository users, PasswordEncoder passwords, TokenService tokens,
                       InviteCodeGenerator inviteCodes) {
        this.teams = teams;
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.inviteCodes = inviteCodes;
    }

    /** Creates a team and its OWNER. */
    @Transactional
    public Session register(String teamName, String email, String password, String displayName) {
        String normalizedEmail = normalize(email);
        requireUnusedEmail(normalizedEmail);
        Team team = teams.save(new Team(teamName.trim(), uniqueInviteCode()));
        User owner = users.save(new User(team.getId(), normalizedEmail, displayName.trim(), passwords.encode(password),
                Role.OWNER));
        IssuedToken token = tokens.issue(owner);
        return new Session(token.token(), token.expiresAt(), team.getId(), team.getInviteCode());
    }

    /** Adds a MEMBER to the team that owns the invite code. */
    @Transactional
    public Session join(String inviteCode, String email, String password, String displayName) {
        Team team = teams.findByInviteCode(inviteCode.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(AuthExceptions.InvalidInviteCode::new);
        String normalizedEmail = normalize(email);
        requireUnusedEmail(normalizedEmail);
        User member = users.save(new User(team.getId(), normalizedEmail, displayName.trim(), passwords.encode(password),
                Role.MEMBER));
        IssuedToken token = tokens.issue(member);
        return new Session(token.token(), token.expiresAt(), team.getId(), null);
    }

    @Transactional(readOnly = true)
    public Session login(String email, String password) {
        User user = users.findByEmailIgnoreCase(normalize(email))
                .filter(u -> passwords.matches(password, u.getPasswordHash()))
                .orElseThrow(AuthExceptions.InvalidCredentials::new);
        IssuedToken token = tokens.issue(user);
        return new Session(token.token(), token.expiresAt(), user.getTeamId(), null);
    }

    private void requireUnusedEmail(String email) {
        if (users.existsByEmailIgnoreCase(email)) {
            throw new AuthExceptions.EmailAlreadyRegistered();
        }
    }

    private String uniqueInviteCode() {
        for (int attempt = 0; attempt < MAX_INVITE_ATTEMPTS; attempt++) {
            String code = inviteCodes.next();
            if (!teams.existsByInviteCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique invite code");
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
