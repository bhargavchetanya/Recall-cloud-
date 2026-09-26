package dev.recall.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.recall.OnnxIntegrationTestBase;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Search quality against a realistic history with the real model. This is the calibration set for the 0.55
 * threshold: questions that have an answer must find the right one above it, unrelated questions must stay below.
 * Prints every score so a threshold change can be judged from the build log.
 */
class SearchQualityIT extends OnnxIntegrationTestBase {

    record Case(String question, String expected) {}

    static final List<String> HISTORY = new ArrayList<>(T1SearchHitIT.HISTORY);

    static {
        HISTORY.addAll(List.of(
                "git branch -d feature/old-ui",
                "git commit --amend -m \"fix typo in readme\"",
                "git fetch --prune",
                "git restore --staged src/app.ts",
                "git clean -fd",
                "git remote -v",
                "git cherry-pick 3f2a91c",
                "lsof -i :8080",
                "rm -rf node_modules",
                "npm cache clean --force",
                "docker ps -a",
                "docker exec -it shop-api-1 sh",
                "docker compose down -v",
                "kubectl logs deploy/api -n staging",
                "kubectl port-forward svc/api 8080:80 -n staging",
                "tail -f logs/app.log",
                "df -h",
                "tar -xzf release.tar.gz",
                "psql -h localhost -U app shop",
                "mvn clean install -DskipTests"));
    }

    static final List<Case> ANSWERABLE = List.of(
            new Case("undo my last commit but keep the changes", "git reset --soft HEAD~1"),
            new Case("something is already using port 3000", "npx kill-port 3000"),
            new Case("which process is listening on port 8080", "lsof -i :8080"),
            new Case("delete my old local branch", "git branch -d feature/old-ui"),
            new Case("fix the message of the last commit", "git commit --amend -m \"fix typo in readme\""),
            new Case("unstage a file", "git restore --staged src/app.ts"),
            new Case("save my work in progress without committing", "git stash"),
            new Case("get my stashed changes back", "git stash pop"),
            new Case("show all containers including stopped ones", "docker ps -a"),
            new Case("get a shell inside the api container", "docker exec -it shop-api-1 sh"),
            new Case("free up disk space used by docker", "docker system prune -af"),
            new Case("restart the api deployment on kubernetes", "kubectl rollout restart deployment/api -n staging"),
            new Case("see the logs of the api pod", "kubectl logs deploy/api -n staging"),
            new Case("reinstall node modules from scratch", "rm -rf node_modules"),
            new Case("create a python virtual environment", "python -m venv .venv"),
            new Case("how big is the node_modules folder", "du -sh node_modules"),
            new Case("make the deploy script executable", "chmod +x scripts/deploy.sh"),
            new Case("generate a new ssh key", "ssh-keygen -t ed25519 -C \"dev@example.com\""),
            new Case("build the java project without running tests", "mvn clean install -DskipTests"),
            new Case("how much free disk space is left", "df -h"));

    static final List<String> UNANSWERABLE = List.of(
            "what's the weather in berlin tomorrow",
            "bake a chocolate cake",
            "translate good morning into spanish",
            "who won the football match yesterday",
            "purple elephants sing sonatas under the ocean",
            "book a table for two at an italian restaurant",
            "rotate the pdf pages by ninety degrees",
            "train a neural network on my photos");

    @Test
    void answersRealQuestionsAndStaysQuietOnUnrelatedOnes() {
        Member dev = registerTeam("Calibration");
        seedHistory(dev, HISTORY);

        int correct = 0;
        System.out.println("---- search calibration (threshold 0.55) ----");
        for (Case c : ANSWERABLE) {
            Response r = ask(dev, c.question());
            boolean hit = c.expected().equals(r.path("command")) && "TEAM".equals(r.path("source"));
            correct += hit ? 1 : 0;
            System.out.printf("%s %-52s -> %-6s %-5s %s%n", hit ? "OK  " : "MISS", c.question(), r.path("source"),
                    r.path("confidence"), r.path("command") == null ? "" : r.path("command"));
        }
        int falsePositives = 0;
        for (String q : UNANSWERABLE) {
            Response r = ask(dev, q);
            boolean quiet = "NONE".equals(r.path("source"));
            falsePositives += quiet ? 0 : 1;
            System.out.printf("%s %-52s -> %-6s %-5s %s%n", quiet ? "OK  " : "FP  ", q, r.path("source"),
                    r.path("confidence"), r.path("command") == null ? "" : r.path("command"));
        }
        System.out.printf("top-1 accuracy %d/%d, false positives %d/%d%n", correct, ANSWERABLE.size(), falsePositives,
                UNANSWERABLE.size());

        assertThat(correct).as("answerable questions answered correctly").isGreaterThanOrEqualTo(17);
        assertThat(falsePositives).as("unrelated questions that got an answer").isZero();
    }

    /**
     * Worst case for false positives: every command is recent, used ten times and from the same repo, so the
     * non-meaning part of the score is already at its maximum (0.40). Unrelated questions must still get NONE.
     */
    @Test
    void heavilyUsedSameRepoCommandsDoNotAnswerUnrelatedQuestions() {
        Member dev = registerTeam("Heavy");
        List<String> repeated = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            repeated.addAll(HISTORY);
        }
        seedHistory(dev, repeated);

        int falsePositives = 0;
        for (String q : UNANSWERABLE) {
            Response r = dev.auth().body(Map.of("query", q, "repoName", "app")).post("/search")
                    .then().statusCode(200).extract().response();
            falsePositives += "NONE".equals(r.path("source")) ? 0 : 1;
            System.out.printf("heavy %-52s -> %-6s %-5s %s%n", q, r.path("source"), r.path("confidence"),
                    r.path("command") == null ? "" : r.path("command"));
        }
        assertThat(falsePositives).as("unrelated questions answered despite max usage, recency and context").isZero();
    }

    private Response ask(Member m, String question) {
        return m.auth().body(Map.of("query", question)).post("/search").then().statusCode(200).extract().response();
    }
}
