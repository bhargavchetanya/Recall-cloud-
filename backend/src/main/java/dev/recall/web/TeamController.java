package dev.recall.web;

import dev.recall.domain.Team;
import dev.recall.repository.TeamRepository;
import dev.recall.repository.UserRepository;
import dev.recall.security.AuthUser;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own team: its name, invite code (to bring in teammates) and members. Not in the spec's table. */
@RestController
@RequestMapping("/api/v1")
public class TeamController {

    public record Member(String displayName, String role) {}

    public record TeamResponse(UUID teamId, String name, String inviteCode, List<Member> members) {}

    private final TeamRepository teams;
    private final UserRepository users;

    public TeamController(TeamRepository teams, UserRepository users) {
        this.teams = teams;
        this.users = users;
    }

    @GetMapping("/team")
    public TeamResponse team(AuthUser user) {
        Team team = teams.findById(user.teamId()).orElseThrow();
        List<Member> members = users.findByTeamId(team.getId()).stream()
                .sorted(Comparator.comparing(dev.recall.domain.User::getCreatedAt))
                .map(u -> new Member(u.getDisplayName(), u.getRole().name()))
                .toList();
        return new TeamResponse(team.getId(), team.getName(), team.getInviteCode(), members);
    }
}
