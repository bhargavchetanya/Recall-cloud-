package dev.recall.service.errors;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.recall.repository.FixRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FixFeedbackServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    private final FixRepository fixes = mock(FixRepository.class);
    private final FixFeedbackService service = new FixFeedbackService(fixes, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID team = UUID.randomUUID();
    private final UUID fix = UUID.randomUUID();

    @Test
    void recordsBothKindsOfFeedbackForOwnTeam() {
        when(fixes.teamOf(fix)).thenReturn(Optional.of(team));

        service.record(team, fix, true);
        service.record(team, fix, false);

        verify(fixes).recordFeedback(fix, true, NOW);
        verify(fixes).recordFeedback(fix, false, NOW);
    }

    @Test
    void unknownFixIsNotFound() {
        when(fixes.teamOf(fix)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.record(team, fix, false)).isInstanceOf(FixFeedbackService.FixNotFound.class);
    }

    @Test
    void anotherTeamsFixIsForbiddenAndUntouched() {
        when(fixes.teamOf(fix)).thenReturn(Optional.of(UUID.randomUUID()));
        assertThatThrownBy(() -> service.record(team, fix, false))
                .isInstanceOf(FixFeedbackService.NotYourTeamsFix.class);
        verify(fixes, never()).recordFeedback(any(), anyBoolean(), any());
    }
}
