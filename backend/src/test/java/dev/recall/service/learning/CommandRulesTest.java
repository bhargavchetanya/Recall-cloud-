package dev.recall.service.learning;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CommandRulesTest {

    private final CommandRules rules = new CommandRules(LearningServiceTest.LEARNING.unblockers(),
            LearningServiceTest.LEARNING.trivial());

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "git push origin main           | git",
            "sudo npm install -g pnpm       | npm",
            "sudo -E docker compose up      | docker",
            "NODE_ENV=test npm test         | npm",
            "./gradlew build                | gradlew",
            "C:\\tools\\node.exe server.js  | node",
            "  Docker PS                    | docker"})
    void findsTheTool(String command, String tool) {
        assertThat(CommandRules.tool(command)).isEqualTo(tool);
    }

    @Test
    void unblockersMatchWholeWordPrefixes() {
        assertThat(rules.isUnblocker("npx kill-port 3000")).isTrue();
        assertThat(rules.isUnblocker("sudo kill -9 4242")).isTrue();
        assertThat(rules.isUnblocker("kill")).isTrue();
        assertThat(rules.isUnblocker("killall node")).isFalse();
        assertThat(rules.isUnblocker("rm -rf node_modules")).isTrue();
        assertThat(rules.isUnblocker("rm -rf src")).isFalse();
        assertThat(rules.isUnblocker("$env:NODE_OPTIONS=\"--max-old-space-size=4096\"")).isTrue();
        assertThat(rules.isUnblocker("npx prettier .")).isFalse();
    }

    @Test
    void trivialCommands() {
        assertThat(rules.isTrivial("cd ~/app")).isTrue();
        assertThat(rules.isTrivial("git status")).isTrue();
        assertThat(rules.isTrivial("GIT STATUS")).isTrue();
        assertThat(rules.isTrivial("git stash")).isFalse();
        assertThat(rules.isTrivial("lsof -i :3000")).isFalse();
    }
}
