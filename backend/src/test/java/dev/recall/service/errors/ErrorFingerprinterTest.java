package dev.recall.service.errors;

import static org.assertj.core.api.Assertions.assertThat;

import dev.recall.service.errors.ErrorSamples.Pair;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ErrorFingerprinterTest {

    static final List<Pair> SAME = ErrorSamples.SAME;
    static final List<Pair> DIFFERENT = ErrorSamples.DIFFERENT;

    private final ErrorFingerprinter fingerprinter = new ErrorFingerprinter();

    static List<Pair> same() {
        return SAME;
    }

    static List<Pair> different() {
        return DIFFERENT;
    }

    @Test
    void tableHasEnoughRealSamples() {
        assertThat(ErrorSamples.all()).hasSizeGreaterThanOrEqualTo(15);
        assertThat(SAME).hasSizeGreaterThanOrEqualTo(5);
        assertThat(DIFFERENT).hasSizeGreaterThanOrEqualTo(3);
    }

    @ParameterizedTest(name = "same: {0}")
    @MethodSource("same")
    void sameProblemGetsOneFingerprint(Pair p) {
        var a = fingerprinter.fingerprint(p.a(), null);
        var b = fingerprinter.fingerprint(p.b(), null);
        assertThat(a.normalized()).isEqualTo(b.normalized());
        assertThat(a.sha256()).isEqualTo(b.sha256());
    }

    @ParameterizedTest(name = "different: {0}")
    @MethodSource("different")
    void differentProblemsGetDifferentFingerprints(Pair p) {
        assertThat(fingerprinter.fingerprint(p.a(), null).sha256())
                .isNotEqualTo(fingerprinter.fingerprint(p.b(), null).sha256());
    }

    @Test
    void theSpecExampleCollapses() {
        assertThat(fingerprinter.normalize("listen EADDRINUSE: address already in use :::3000", null))
                .isEqualTo("listen eaddrinuse: address already in use :::<num>")
                .isEqualTo(fingerprinter.normalize("listen EADDRINUSE: address already in use :::8080", null));
    }

    @Test
    void replacesEachVolatilePart() {
        assertThat(fingerprinter.normalize("ERROR at 2026-09-24T10:15:02Z calling https://api.acme.io/v1/x?id=7", null))
                .isEqualTo("error at <time> calling <url>");
        assertThat(fingerprinter.normalize("cannot open /var/log/app.log or C:\\logs\\app.log", null))
                .isEqualTo("cannot open <path> or <path>");
        assertThat(fingerprinter.normalize("failed at 0x7ffd3a2b commit 3f2a91c in 123e4567-e89b-12d3-a456-426614174000",
                null)).isEqualTo("failed at <hash> commit <hash> in <hash>");
        assertThat(fingerprinter.normalize("error: can't find \"thing\" or `other` or 'third' in 42 places", null))
                .isEqualTo("error: can't find <str> or <str> or <str> in <num> places");
    }

    @Test
    void relativePathsAndPlainWordsAreKept() {
        assertThat(fingerprinter.normalize("error in src/app.ts: facade broken", null))
                .isEqualTo("error in src/app.ts: facade broken");
    }

    @Test
    void replacesTheUsernameButNotShortOnes() {
        assertThat(fingerprinter.normalize("ERROR: user Krish is not in the sudoers file", "krish"))
                .isEqualTo("error: user <user> is not in the sudoers file");
        assertThat(fingerprinter.normalize("ERROR: user bo failed", "bo")).isEqualTo("error: user bo failed");
    }

    @Test
    void stripsEscapeCodesAndCarriageReturns() {
        assertThat(fingerprinter.normalize("\u001B[31mError:\u001B[0m boom\r\n", null)).isEqualTo("error: boom");
    }

    @Test
    void keepsErrorLinesAndTheLastThreeAndDropsTheNoiseBetween() {
        String output = "compiling 1\ncompiling 2\nError: broken thing\nstep 4\nstep 5\nstep 6\nstep 7\nstep 8\n";
        assertThat(ErrorFingerprinter.relevantLines(output))
                .containsExactly("Error: broken thing", "step 6", "step 7", "step 8");
    }

    @Test
    void keepsTheLastTwentyWhenThereAreMore() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            sb.append("error ").append(i).append('\n');
        }
        List<String> lines = ErrorFingerprinter.relevantLines(sb.toString());
        assertThat(lines).hasSize(20).startsWith("error 11").endsWith("error 30");
    }

    @Test
    void emptyOrNullOutputIsStable() {
        assertThat(fingerprinter.normalize(null, null)).isEmpty();
        assertThat(fingerprinter.fingerprint("  \n ", null).sha256())
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
