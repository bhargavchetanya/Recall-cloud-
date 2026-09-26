package dev.recall.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(nullable = false)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {}

    public User(UUID teamId, String email, String displayName, String passwordHash, Role role) {
        this.teamId = teamId;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTeamId() { return teamId; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
}
