package com.livingdocs.identity.model;

public final class EmailNormalization {

    private EmailNormalization() {
    }

    // PostgreSQL BTRIM(email) without a character argument removes ASCII spaces.
    public static String trimOuterSpaces(String email) {
        if (email == null) {
            return null;
        }
        int start = 0;
        int end = email.length();
        while (start < end && email.charAt(start) == ' ') {
            start++;
        }
        while (end > start && email.charAt(end - 1) == ' ') {
            end--;
        }
        return email.substring(start, end);
    }
}
