package dev.recall.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.recall.service.HealthService.Report;
import dev.recall.service.HealthService.Status;
import dev.recall.service.embedding.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class HealthServiceTest {

    private JdbcTemplate jdbc;
    private ConnectionFactory rabbit;
    private Connection connection;
    private EmbeddingService embeddings;
    private HealthService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        rabbit = mock(ConnectionFactory.class);
        connection = mock(Connection.class);
        embeddings = mock(EmbeddingService.class);
        float[] unit = new float[EmbeddingService.DIMENSIONS];
        unit[0] = 1f;
        when(embeddings.embed(HealthService.PROBE_TEXT)).thenReturn(unit);
        service = new HealthService(jdbc, rabbit, embeddings);
    }

    @Test
    void upWhenDatabaseAndQueueAreReachable() {
        when(jdbc.queryForObject("select 1", Integer.class)).thenReturn(1);
        when(rabbit.createConnection()).thenReturn(connection);
        when(connection.isOpen()).thenReturn(true);

        Report report = service.check();

        assertThat(report).isEqualTo(new Report(Status.UP, Status.UP, Status.UP, Status.UP));
    }

    @Test
    void downWhenDatabaseThrows() {
        when(jdbc.queryForObject("select 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));
        when(rabbit.createConnection()).thenReturn(connection);
        when(connection.isOpen()).thenReturn(true);

        Report report = service.check();

        assertThat(report.status()).isEqualTo(Status.DOWN);
        assertThat(report.db()).isEqualTo(Status.DOWN);
        assertThat(report.queue()).isEqualTo(Status.UP);
    }

    @Test
    void downWhenQueueIsUnreachable() {
        when(jdbc.queryForObject("select 1", Integer.class)).thenReturn(1);
        when(rabbit.createConnection()).thenThrow(new AmqpConnectException(new java.net.ConnectException("refused")));

        Report report = service.check();

        assertThat(report.status()).isEqualTo(Status.DOWN);
        assertThat(report.db()).isEqualTo(Status.UP);
        assertThat(report.queue()).isEqualTo(Status.DOWN);
    }

    @Test
    void downWhenQueueConnectionIsClosed() {
        when(jdbc.queryForObject("select 1", Integer.class)).thenReturn(1);
        when(rabbit.createConnection()).thenReturn(connection);
        when(connection.isOpen()).thenReturn(false);

        assertThat(service.check().queue()).isEqualTo(Status.DOWN);
    }

    @Test
    void downWhenDatabaseReturnsUnexpectedValue() {
        when(jdbc.queryForObject("select 1", Integer.class)).thenReturn(null);
        when(rabbit.createConnection()).thenReturn(connection);
        when(connection.isOpen()).thenReturn(true);

        assertThat(service.check().db()).isEqualTo(Status.DOWN);
    }

    @Test
    void embedderDownWhenItThrowsReturnsWrongSizeZeroOrNaN() {
        when(jdbc.queryForObject("select 1", Integer.class)).thenReturn(1);
        when(rabbit.createConnection()).thenReturn(connection);
        when(connection.isOpen()).thenReturn(true);
        float[] nan = new float[EmbeddingService.DIMENSIONS];
        nan[3] = Float.NaN;

        when(embeddings.embed(HealthService.PROBE_TEXT)).thenThrow(new IllegalStateException("model gone"));
        assertThat(service.check()).isEqualTo(new Report(Status.DOWN, Status.UP, Status.UP, Status.DOWN));

        org.mockito.Mockito.reset(embeddings);
        when(embeddings.embed(HealthService.PROBE_TEXT)).thenReturn(new float[3], new float[EmbeddingService.DIMENSIONS], nan);
        assertThat(service.checkEmbedder()).as("wrong size").isEqualTo(Status.DOWN);
        assertThat(service.checkEmbedder()).as("all zeros").isEqualTo(Status.DOWN);
        assertThat(service.checkEmbedder()).as("NaN").isEqualTo(Status.DOWN);
    }
}
