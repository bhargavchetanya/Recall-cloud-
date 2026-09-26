package dev.recall.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

import dev.recall.IntegrationTestBase;
import org.junit.jupiter.api.Test;

class TeamIT extends IntegrationTestBase {

    @Test
    void membersSeeTheirOwnTeamWithInviteCodeAndMembers() {
        Member owner = registerTeam("Olive");
        Member member = joinTeam(owner, "Mo");
        registerTeam("Stranger");

        member.auth().get("/team")
                .then().statusCode(200)
                .body("teamId", equalTo(owner.teamId().toString()))
                .body("name", equalTo("Olive's team"))
                .body("inviteCode", equalTo(owner.inviteCode()))
                .body("members.displayName", contains("Olive", "Mo"))
                .body("members.role", contains("OWNER", "MEMBER"));
    }

    @Test
    void teamNeedsAToken() {
        given().get("/team").then().statusCode(401).contentType("application/problem+json");
    }
}
