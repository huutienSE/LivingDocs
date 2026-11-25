package com.livingdocs.analysis.diff;

import java.util.Optional;

import com.livingdocs.analysis.ast.SourceInput;

/** Caller-supplied, pinned snapshots for one file comparison. */
public record StructuralDiffInput(String repository, Intent intent, Optional<Side> before, Side after,
		Optional<FileAssociation> association) {

	public enum Intent { REVISION_COMPARISON, INITIAL_ASSESSMENT }

	public record SnapshotRef(String repository, String sha) {
	}

	public sealed interface Side permits Present, Absent, Unavailable {
		SnapshotRef snapshot();
		String path();
	}

	public record Present(SnapshotRef snapshot, SourceInput source) implements Side {
		@Override
		public String path() { return source == null ? null : source.filePath(); }
	}

	public record Absent(SnapshotRef snapshot, String path, String reason) implements Side {
	}

	public record Unavailable(SnapshotRef snapshot, String path, String diagnostic) implements Side {
	}

	public record FileAssociation(String beforePath, String afterPath) {
	}
}
