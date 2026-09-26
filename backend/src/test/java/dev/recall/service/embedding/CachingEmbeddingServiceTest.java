package dev.recall.service.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class CachingEmbeddingServiceTest {

    @Test
    void computesEachTextOnceAndReturnsCopies() {
        EmbeddingService delegate = mock(EmbeddingService.class);
        when(delegate.embed("a")).thenReturn(new float[] {1f, 0f});
        CachingEmbeddingService cache = new CachingEmbeddingService(delegate, 10);

        float[] first = cache.embed("a");
        first[0] = 42f;

        assertThat(cache.embed("a")).containsExactly(1f, 0f);
        verify(delegate, times(1)).embed("a");
    }

    @Test
    void evictsTheLeastRecentlyUsedEntry() {
        EmbeddingService delegate = mock(EmbeddingService.class);
        when(delegate.embed(org.mockito.ArgumentMatchers.anyString())).thenReturn(new float[] {1f});
        CachingEmbeddingService cache = new CachingEmbeddingService(delegate, 2);

        cache.embed("a");
        cache.embed("b");
        cache.embed("a");
        cache.embed("c");
        cache.embed("a");
        cache.embed("b");

        verify(delegate, times(1)).embed("a");
        verify(delegate, times(2)).embed("b");
    }

    @Test
    void delegatesNameAndClose() throws Exception {
        OnnxLike delegate = mock(OnnxLike.class);
        when(delegate.name()).thenReturn("model");
        CachingEmbeddingService cache = new CachingEmbeddingService(delegate, 2);

        assertThat(cache.name()).isEqualTo("model");
        cache.close();
        verify(delegate).close();

        new CachingEmbeddingService(new HashingEmbeddingService(), 2).close();
    }

    interface OnnxLike extends EmbeddingService, AutoCloseable {}
}
