package dev.recall.security;

import java.util.UUID;

/**
 * The caller, as proven by their JWT. Controllers take this as a parameter; it is the only source of
 * {@code teamId} for any query.
 */
public record AuthUser(UUID userId, UUID teamId, String displayName) {}
