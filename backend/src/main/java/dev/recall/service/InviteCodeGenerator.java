package dev.recall.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** Ten characters from an alphabet without look-alikes (no 0/O, 1/I/L), about 49 bits of entropy. */
@Component
public class InviteCodeGenerator {

    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 10;

    private final SecureRandom random = new SecureRandom();

    public String next() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
