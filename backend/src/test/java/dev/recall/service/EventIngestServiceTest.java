package dev.recall.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.recall.domain.CapturedEvent;
import dev.recall.domain.EventBatch;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EventIngestServiceTest {

    private final EventPublisher publisher = mock(EventPublisher.class);
    private final EventIngestService service = new EventIngestService(new SecretMasker(), publisher);
    private final UUID team = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @Test
    void masksCommandAndErrorAndStampsCaller() {
        CapturedEvent dirty = event("mysql -u root -pHunter2 app", false, 1, "ERROR 1045: denied for postgres://u:pw@db/x");
        CapturedEvent clean = event("git status", false, 0, null);

        int accepted = service.accept(team, user, List.of(dirty, clean));

        EventBatch batch = published();
        assertThat(accepted).isEqualTo(2);
        assertThat(batch.teamId()).isEqualTo(team);
        assertThat(batch.userId()).isEqualTo(user);
        assertThat(batch.events().get(0).commandText()).isEqualTo("mysql -u root -p*** app");
        assertThat(batch.events().get(0).errorText()).isEqualTo("ERROR 1045: denied for postgres://u:***@db/x");
        assertThat(batch.events().get(0).redacted()).isTrue();
        assertThat(batch.events().get(1).redacted()).isFalse();
    }

    @Test
    void secretOnlyInErrorOutputStillMarksRedacted() {
        service.accept(team, user, List.of(event("npm publish", false, 1, "npm ERR! token=abc123 invalid")));

        CapturedEvent sent = published().events().get(0);
        assertThat(sent.commandText()).isEqualTo("npm publish");
        assertThat(sent.errorText()).isEqualTo("npm ERR! token=*** invalid");
        assertThat(sent.redacted()).isTrue();
    }

    @Test
    void keepsClientRedactedFlagEvenWhenNothingLeftToMask() {
        service.accept(team, user, List.of(event("psql --password=***", true, 0, null)));

        assertThat(published().events().get(0).redacted()).isTrue();
    }

    @Test
    void propagatesQueueFailure() {
        doThrow(new EventPublisher.QueueUnavailableException("down", null)).when(publisher).publish(any());

        assertThatThrownBy(() -> service.accept(team, user, List.of(event("ls", false, 0, null))))
                .isInstanceOf(EventPublisher.QueueUnavailableException.class);
    }

    private EventBatch published() {
        ArgumentCaptor<EventBatch> captor = ArgumentCaptor.forClass(EventBatch.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue();
    }

    private static CapturedEvent event(String command, boolean redacted, Integer exitCode, String errorText) {
        return new CapturedEvent("s1", 1, command, redacted, "/work", "app", "bash", "linux", exitCode,
                Instant.parse("2026-09-01T10:00:00Z"), 120L, errorText, null);
    }
}
