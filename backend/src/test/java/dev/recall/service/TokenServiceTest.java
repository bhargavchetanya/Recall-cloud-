package dev.recall.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import dev.recall.config.RecallProperties;
import dev.recall.domain.Role;
import dev.recall.domain.User;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class TokenServiceTest {

    private final SecretKey key = new SecretKeySpec(
            "unit-test-secret-that-is-at-least-32-bytes".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final RecallProperties properties = new RecallProperties(
            new RecallProperties.Jwt("unused", Duration.ofDays(30)), null, null, null, null, null, null, null);
    private final TokenService service = new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(key)), properties,
            Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void tokenCarriesUserTeamAndExpiry() {
        UUID userId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        User user = new User(teamId, "ada@example.com", "Ada", "hash", Role.OWNER);
        ReflectionTestUtils.setField(user, "id", userId);

        TokenService.IssuedToken issued = service.issue(user);
        Jwt jwt = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build().decode(issued.token());

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString(TokenService.TEAM_CLAIM)).isEqualTo(teamId.toString());
        assertThat(jwt.getClaimAsString(TokenService.NAME_CLAIM)).isEqualTo("Ada");
        assertThat(jwt.getClaimAsString(TokenService.ROLE_CLAIM)).isEqualTo("OWNER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(TokenService.ISSUER);
        assertThat(issued.expiresAt()).isEqualTo(now.plus(Duration.ofDays(30)));
        assertThat(jwt.getExpiresAt()).isEqualTo(issued.expiresAt());
    }
}
