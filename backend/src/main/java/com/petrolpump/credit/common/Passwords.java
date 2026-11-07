package com.petrolpump.credit.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;

public final class Passwords {

    // No 0/O/1/l/I so a temporary password can be read out over the phone.
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {}

    public static String temporary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) sb.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        for (int i = 0; i < 3; i++) sb.append(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        return sb.toString();
    }

    public static void requireStrong(String password) {
        if (password == null || password.length() < 8
                || !password.chars().anyMatch(Character::isLetter)
                || !password.chars().anyMatch(Character::isDigit)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be at least 8 characters and contain letters and digits");
        }
    }
}
