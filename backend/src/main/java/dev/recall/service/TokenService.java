package dev.recall.service;

import dev.recall.config.RecallProperties;
import dev.recall.domain.User;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    public static final String ISSUER = "recall";
    public static final String TEAM_CLAIM = "team";
    public static final String NAME_CLAIM = "name";
    public static final String ROLE_CLAIM = "role";

    public record IssuedToken(String token, Instant expiresAt) {}

    private final JwtEncoder encoder;
    private final RecallProperties properties;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, RecallProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issue(User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.jwt().ttl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(TEAM_CLAIM, user.getTeamId().toString())
                .claim(NAME_CLAIM, user.getDisplayName())
                .claim(ROLE_CLAIM, user.getRole().name())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(token, expiresAt);
    }
}
