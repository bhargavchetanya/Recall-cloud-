package dev.recall.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InviteCodeGeneratorTest {

    private final InviteCodeGenerator generator = new InviteCodeGenerator();

    @Test
    void codesUseTheUnambiguousAlphabetAndFixedLength() {
        for (int i = 0; i < 200; i++) {
            String code = generator.next();
            assertThat(code).hasSize(InviteCodeGenerator.LENGTH);
            assertThat(code.chars()).allMatch(c -> InviteCodeGenerator.ALPHABET.indexOf(c) >= 0);
            assertThat(code).doesNotContain("0", "O", "1", "I", "L");
        }
    }

    @Test
    void codesDoNotRepeatInPractice() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(generator.next());
        }
        assertThat(seen).hasSize(1000);
    }
}
