package dev.recall.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.recall.OnnxIntegrationTestBase;
import dev.recall.service.embedding.CommandGlossary;
import dev.recall.service.embedding.EmbeddingService;
import dev.recall.service.embedding.Vectors;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * T10 — with 10 000 commands in the team's history, the 95th percentile of 50 /search calls is under 300 ms,
 * measured here around real HTTP calls with the real model. The history is ~1 000 distinct realistic commands
 * used ten times each over the last 90 days, inserted with JDBC (embedding 10 000 events through the queue would
 * only test the consumer). As the consumer does, only the first use of each command carries its vector.
 * Five warm-up calls are not counted, and the table is ANALYZEd first so the planner is in its steady state
 * (an earlier version passed only because it measured before autovacuum had analyzed the new rows).
 */
class T10LatencyIT extends OnnxIntegrationTestBase {

    static final int ROWS = 10_000;
    static final int CALLS = 50;
    static final long P95_LIMIT_MS = 300;

    static final List<String> QUERIES = List.of(
            "undo my last commit but keep the changes",
            "free a port that is already in use",
            "list running docker containers",
            "restart the kubernetes deployment",
            "show the logs of a pod",
            "install python dependencies",
            "delete a local branch",
            "check how much disk space a folder uses",
            "create a new branch",
            "run the tests");

    @Autowired
    EmbeddingService embeddings;

    @Autowired
    CommandGlossary glossary;

    @Test
    void p95UnderThreeHundredMillisecondsWithTenThousandCommands() {
        Member ada = registerTeam("Ada");
        Member bo = joinTeam(ada, "Bo");
        seed(List.of(ada.userId(), bo.userId()), ada.teamId());
        assertThat(commandCount(ada.userId()) + commandCount(bo.userId())).isEqualTo(ROWS);
        jdbc.execute("analyze command_events");

        for (int i = 0; i < 5; i++) {
            search(ada, QUERIES.get(i));
        }
        long[] millis = new long[CALLS];
        for (int i = 0; i < CALLS; i++) {
            long start = System.nanoTime();
            search(ada, QUERIES.get(i % QUERIES.size()));
            millis[i] = (System.nanoTime() - start) / 1_000_000;
        }
        Arrays.sort(millis);
        long p50 = millis[CALLS / 2 - 1];
        long p95 = millis[(int) Math.ceil(CALLS * 0.95) - 1];
        System.out.printf("T10 /search latency over %d calls: p50=%d ms, p95=%d ms, max=%d ms%n", CALLS, p50, p95,
                millis[CALLS - 1]);

        assertThat(p95).as("p95 latency in ms").isLessThan(P95_LIMIT_MS);
    }

    private void search(Member m, String query) {
        m.auth().body(Map.of("query", query, "repoName", "shop")).post("/search").then().statusCode(200);
    }

    private void seed(List<UUID> users, UUID teamId) {
        List<String> distinct = distinctCommands(ROWS / 10);
        Map<String, String> vectors = new java.util.HashMap<>();
        for (String c : distinct) {
            vectors.put(c, Vectors.toPgLiteral(embeddings.embed(glossary.intentText(c))));
        }
        Random random = new Random(10);
        Instant now = Instant.now();
        List<Object[]> rows = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            String command = distinct.get(i % distinct.size());
            UUID user = users.get(i % users.size());
            rows.add(new Object[] {teamId, user, "t10-" + (i % 40), i, command, "/work/shop", "shop", "bash", "linux",
                    Timestamp.from(now.minusSeconds(random.nextInt(90 * 86_400))),
                    i < distinct.size() ? vectors.get(command) : null});
        }
        jdbc.batchUpdate("""
                insert into command_events (team_id, user_id, session_id, seq, command_text, cwd, repo_name, shell, os,
                                            exit_code, started_at, duration_ms, intent_vector)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 100, ?::vector)
                """, rows);
    }

    /** Realistic, varied commands: a fixed set of templates filled from small vocabularies, deterministic. */
    static List<String> distinctCommands(int count) {
        String[] services = {"api", "web", "worker", "auth", "billing", "search", "gateway", "cron"};
        String[] branches = {"feature/login", "fix/cart-total", "chore/deps", "feature/search", "hotfix/payments"};
        String[] packages = {"lodash", "react", "axios", "zod", "express", "vitest", "dayjs", "prisma"};
        String[] namespaces = {"dev", "staging", "prod", "qa"};
        String[] files = {"src/app.ts", "README.md", "package.json", "src/db.ts", "docker-compose.yml"};
        String[][] templates = {
                {"git checkout %B"}, {"git push origin %B"}, {"git branch -d %B"}, {"git rebase origin/%B"},
                {"git log --oneline -%N"}, {"git diff %F"}, {"git restore --staged %F"}, {"git add %F"},
                {"git commit -m \"update %S\""}, {"git reset --soft HEAD~%N"}, {"git stash push -m \"%S wip\""},
                {"npm install %P"}, {"npm install -D %P"}, {"npm run build -w %S"}, {"npm test -- %S"},
                {"npx kill-port 3%N00"}, {"docker compose logs -f %S"}, {"docker compose restart %S"},
                {"docker exec -it shop-%S-1 sh"}, {"docker build -t shop/%S:%N ."}, {"docker logs --tail %N00 shop-%S-1"},
                {"kubectl get pods -n %K"}, {"kubectl logs deploy/%S -n %K"}, {"kubectl rollout restart deployment/%S -n %K"},
                {"kubectl describe pod %S-%N -n %K"}, {"kubectl port-forward svc/%S 80%N:80 -n %K"},
                {"kubectl scale deployment/%S --replicas=%N -n %K"}, {"curl -s http://localhost:80%N/%S/health"},
                {"pytest tests/test_%S.py"}, {"pip install -r %S/requirements.txt"}, {"du -sh %S"},
                {"tail -f logs/%S.log"}, {"grep -rn \"%S\" src"}, {"chmod +x scripts/%S.sh"}};
        Random random = new Random(42);
        Set<String> out = new LinkedHashSet<>();
        while (out.size() < count) {
            String t = templates[random.nextInt(templates.length)][0];
            out.add(t.replace("%B", branches[random.nextInt(branches.length)])
                    .replace("%P", packages[random.nextInt(packages.length)])
                    .replace("%K", namespaces[random.nextInt(namespaces.length)])
                    .replace("%F", files[random.nextInt(files.length)])
                    .replace("%S", services[random.nextInt(services.length)])
                    .replace("%N", String.valueOf(1 + random.nextInt(9))));
        }
        return new ArrayList<>(out);
    }
}
