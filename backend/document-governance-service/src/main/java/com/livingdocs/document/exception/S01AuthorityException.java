package com.livingdocs.document.exception;

/** Neutral transport/authority failures, mapped to each public API by its adapter. */
public final class S01AuthorityException extends RuntimeException {
    public enum Kind { UNAUTHENTICATED, FORBIDDEN, UNAVAILABLE }
    private final Kind kind;
    private S01AuthorityException(Kind kind) { super("S01 authority request failed."); this.kind = kind; }
    public Kind getKind() { return kind; }
    public static S01AuthorityException unauthenticated() { return new S01AuthorityException(Kind.UNAUTHENTICATED); }
    public static S01AuthorityException forbidden() { return new S01AuthorityException(Kind.FORBIDDEN); }
    public static S01AuthorityException unavailable() { return new S01AuthorityException(Kind.UNAVAILABLE); }
}
