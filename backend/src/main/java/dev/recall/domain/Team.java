package dev.recall.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "teams")
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "invite_code", nullable = false, unique = true)
    private String inviteCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Team() {}

    public Team(String name, String inviteCode) {
        this.name = name;
        this.inviteCode = inviteCode;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getInviteCode() { return inviteCode; }
    public Instant getCreatedAt() { return createdAt; }
}
