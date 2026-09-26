package dev.recall.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import dev.recall.OnnxIntegrationTestBase;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** T1 — a question in plain words finds the one command a teammate used for it. Real model, real HTTP. */
class T1SearchHitIT extends OnnxIntegrationTestBase {

    static final List<String> HISTORY = List.of(
            "git status",
            "git pull --rebase",
            "git checkout -b feature/login-form",
            "git add -A",
            "git commit -m \"add login form\"",
            "git push -u origin feature/login-form",
            "git reset --soft HEAD~1",
            "git log --oneline -10",
            "git stash",
            "git stash pop",
            "npm ci",
            "npm run dev",
            "npm test",
            "npx kill-port 3000",
            "docker compose up -d",
            "docker compose logs -f api",
            "docker system prune -af",
            "kubectl get pods -n staging",
            "kubectl rollout restart deployment/api -n staging",
            "python -m venv .venv",
            "pip install -r requirements.txt",
            "du -sh node_modules",
            "chmod +x scripts/deploy.sh",
            "ssh-keygen -t ed25519 -C \"dev@example.com\"");

    @Test
    void undoLastCommitKeepingChangesFindsResetSoft() {
        Member ada = registerTeam("Ada");
        seedHistory(ada, HISTORY);

        ada.auth().body(Map.of("query", "undo my last commit but keep the changes"))
                .post("/search")
                .then()
                .statusCode(200)
                .body("command", equalTo("git reset --soft HEAD~1"))
                .body("source", equalTo("TEAM"))
                .body("confidence", greaterThanOrEqualTo(0.55f))
                .body("provenance.solvedBy", equalTo("Ada"))
                .body("provenance.timesUsed", equalTo(1));
    }

    @Test
    void provenanceCountsEveryUseAcrossTheTeamAndNamesTheLatestUser() {
        Member ada = registerTeam("Ada");
        Member daksh = joinTeam(ada, "Daksh");
        seedHistory(ada, List.of("docker system prune -af", "git status"));
        seedHistory(daksh, List.of("docker system prune -af", "docker system prune -af"));

        Response r = daksh.auth().body(Map.of("query", "free up disk space used by docker"))
                .post("/search").then().statusCode(200).extract().response();

        assertThat(r.<String>path("command")).isEqualTo("docker system prune -af");
        assertThat(r.<String>path("source")).isEqualTo("TEAM");
        assertThat(r.<Integer>path("provenance.timesUsed")).isEqualTo(3);
        assertThat(r.<String>path("provenance.solvedBy")).isEqualTo("Daksh");
        assertThat(r.<String>path("provenance.lastUsedAt")).isNotBlank();
    }
}
