package dev.recall.service;

/** Failures of the account flows, mapped to HTTP problems by the web layer. */
public final class AuthExceptions {

    private AuthExceptions() {}

    public static class EmailAlreadyRegistered extends RuntimeException {
        public EmailAlreadyRegistered() {
            super("An account with this email already exists.");
        }
    }

    public static class InvalidInviteCode extends RuntimeException {
        public InvalidInviteCode() {
            super("No team has this invite code.");
        }
    }

    public static class InvalidCredentials extends RuntimeException {
        public InvalidCredentials() {
            super("Email or password is incorrect.");
        }
    }
}
