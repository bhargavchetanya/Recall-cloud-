package dev.recall.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Same fixture file as the extension's masking tests, so both sides stay in lockstep. */
class SecretMaskerTest {

    static final Path FIXTURES = Path.of("..", "shared", "fixtures", "masking-cases.json");

    private final SecretMasker masker = new SecretMasker();

    static List<Arguments> sharedCases() throws IOException {
        JsonNode cases = new ObjectMapper().readTree(FIXTURES.toFile());
        List<Arguments> args = new ArrayList<>();
        cases.forEach(c -> args.add(Arguments.of(c.get("note").asText(), c.get("input").asText(),
                c.get("expected").asText())));
        return args;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedCases")
    void masksSharedFixture(String note, String input, String expected) {
        SecretMasker.Result result = masker.mask(input);

        assertThat(result.text()).isEqualTo(expected);
        assertThat(result.redacted()).isEqualTo(!input.equals(expected));
    }

    @Test
    void fixtureFileHasEnoughCasesOfBothKinds() throws IOException {
        List<Arguments> cases = sharedCases();
        long clean = cases.stream().filter(a -> a.get()[1].equals(a.get()[2])).count();

        assertThat(cases).hasSizeGreaterThanOrEqualTo(25);
        assertThat(clean).as("cases that must stay untouched").isGreaterThanOrEqualTo(8);
    }

    @Test
    void nullAndEmptyAreNotRedacted() {
        assertThat(masker.mask(null)).isEqualTo(new SecretMasker.Result(null, false));
        assertThat(masker.mask("")).isEqualTo(new SecretMasker.Result("", false));
    }

    @Test
    void maskingIsIdempotent() {
        String once = masker.mask("PGPASSWORD=x mysql -pHunter2 --token abc").text();

        assertThat(masker.mask(once)).isEqualTo(new SecretMasker.Result(once, false));
    }

    @Test
    void masksSecretsInsideMultilineErrorOutput() {
        String output = """
                Error: connect ECONNREFUSED
                    at postgres://app:s3cret@db:5432/app
                Authorization: Bearer abc.def.ghi
                """;

        String masked = masker.mask(output).text();

        assertThat(masked).doesNotContain("s3cret", "abc.def.ghi").contains("app:***@", "Bearer ***");
    }
}
