package com.livingdocs.model;

import java.util.regex.Pattern;

/** Loại nguồn cần kiểm tra: nhánh hoặc yêu cầu hợp nhất. */
public enum TargetKind {
    BRANCH, PR;

    private static final String BRANCH_PREFIX = "refs/heads/";
    private static final Pattern PR_NUMBER = Pattern.compile("[1-9][0-9]{0,9}");
    private static final String MAX_PR_NUMBER = Integer.toString(Integer.MAX_VALUE);

    public boolean isValidRef(String ref) {
        if (ref == null || ref.length() > 512) {
            return false;
        }
        if (this == PR) {
            return PR_NUMBER.matcher(ref).matches()
                    && (ref.length() < MAX_PR_NUMBER.length() || ref.compareTo(MAX_PR_NUMBER) <= 0);
        }
        if (!ref.startsWith(BRANCH_PREFIX) || ref.length() == BRANCH_PREFIX.length()
                || ref.contains("..") || ref.contains("@{") || ref.endsWith(".") || ref.endsWith("/")) {
            return false;
        }
        for (char character : ref.toCharArray()) {
            if (character <= 32 || character == 127 || "~^:?*[\\".indexOf(character) >= 0) {
                return false;
            }
        }
        for (String component : ref.split("/", -1)) {
            if (component.isEmpty() || component.startsWith(".") || component.endsWith(".lock")) {
                return false;
            }
        }
        return true;
    }
}
