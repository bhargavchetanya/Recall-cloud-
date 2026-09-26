package dev.recall.service.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CommandGlossaryTest {

    private final CommandGlossary glossary = new CommandGlossary();

    @Test
    void loadsTheBundledTable() {
        assertThat(glossary.size()).isGreaterThan(100);
    }

    @Test
    void addsThePhrasePeopleActuallyAsk() {
        assertThat(glossary.intentText("git reset --soft HEAD~1"))
                .isEqualTo("git reset --soft HEAD~1 : undo my last commit but keep the changes staged");
        assertThat(glossary.glossesFor("npx kill-port 3000")).containsExactly(
                "free a port that is already in use by killing the process",
                "port is already in use, address already in use error (EADDRINUSE)");
        assertThat(glossary.glossesFor("docker compose up -d --build"))
                .contains("start all services in the background", "rebuild images and start the services");
    }

    @Test
    void matchesCaseInsensitivelyAndDistinguishesSimilarCommands() {
        assertThat(glossary.glossesFor("GIT STASH POP")).containsExactly("bring back my stashed changes");
        assertThat(glossary.glossesFor("git stash")).containsExactly(
                "save my uncommitted changes temporarily and clean the working tree",
                "put my work in progress aside without committing it");
        assertThat(glossary.glossesFor("git stash push -m \"wip\"")).contains(
                "save my uncommitted changes temporarily and clean the working tree");
        assertThat(glossary.glossesFor("git stash list")).containsExactly("list saved stashes");
        assertThat(glossary.glossesFor("git reset --hard origin/main"))
                .containsExactly("throw away all local changes and reset to a commit");
    }

    @Test
    void leavesUnknownCommandsUnchanged() {
        assertThat(glossary.intentText("./gradlew-custom-thing --flag")).isEqualTo("./gradlew-custom-thing --flag");
    }

    @Test
    void rejectsMalformedLinesAndMissingResource() {
        String bad = "# comment\n\nno tab here\n";
        assertThatThrownBy(() -> new CommandGlossary(new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("line 3");
        assertThatThrownBy(() -> new CommandGlossary(null)).hasMessageContaining("Missing");
    }

    @Test
    void duplicatePhrasesAreListedOnce() {
        String tsv = "\\bls\\b\tlist files\n\\bls\\s+-la\\b\tlist files\n";
        CommandGlossary small = new CommandGlossary(new ByteArrayInputStream(tsv.getBytes(StandardCharsets.UTF_8)));
        assertThat(small.glossesFor("ls -la")).containsExactly("list files");
    }
}
