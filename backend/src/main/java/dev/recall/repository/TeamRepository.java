package dev.recall.repository;

import dev.recall.domain.Team;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, UUID> {

    Optional<Team> findByInviteCode(String inviteCode);

    boolean existsByInviteCode(String inviteCode);
}
