package com.livingdocs.analysis.ast;

import static com.livingdocs.analysis.ast.JavaParseResult.*;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

public final class JavaSourceParser {

	@FunctionalInterface
	interface ParsedUnitProjection<T> {
		T project(SourceInput input, CompilationUnit unit, ParsedSource parsedSource, List<String> lines);
	}

	record BridgedParseResult<T>(JavaParseResult parseResult, Optional<T> projection) {
		BridgedParseResult {
			Objects.requireNonNull(parseResult, "parseResult");
			Objects.requireNonNull(projection, "projection");
		}
	}

	public JavaParseResult parse(SourceInput input) {
		return parseAndProject(input, (sourceInput, unit, parsedSource, lines) -> Boolean.TRUE).parseResult();
	}

	<T> BridgedParseResult<T> parseAndProject(SourceInput input, ParsedUnitProjection<T> projector) {
		Objects.requireNonNull(projector, "projector");
		if (input == null || input.filePath() == null || input.filePath().isBlank()) {
			return new BridgedParseResult<>(fail(DiagnosticCode.INVALID_INPUT, "A source file path is required"),
					Optional.empty());
		}
		if (!input.filePath().endsWith(".java")) {
			var outOfScope = new JavaParseResult(Status.OUT_OF_SCOPE, Optional.empty(), List.of(new ParseDiagnostic(
					DiagnosticCode.UNSUPPORTED_LANGUAGE, Severity.INFO, "Expected a .java file", Optional.empty())),
					Optional.empty());
			return new BridgedParseResult<>(outOfScope, Optional.empty());
		}
		if (input.sourceContent() == null) {
			return new BridgedParseResult<>(fail(DiagnosticCode.INVALID_INPUT, "Java source content is required"),
					Optional.empty());
		}

		var parser = new JavaParser(new ParserConfiguration()
				.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
		var parsed = parser.parse(input.sourceContent());
		var lines = input.sourceContent().lines().toList();
		if (!parsed.isSuccessful()) {
			var diagnostics = parsed.getProblems().stream().map(problem -> {
				String message = problem.getMessage();
				var code = message.contains("supported starting from") && message.contains("language level")
						? DiagnosticCode.UNSUPPORTED_SYNTAX : DiagnosticCode.SYNTAX_ERROR;
				var range = problem.getLocation().flatMap(location -> location.toRange())
						.flatMap(location -> JavaDeclarationExtractor.sourceRange(location, lines));
				return new ParseDiagnostic(code, Severity.ERROR, message, range);
			}).toList();
			var failure = diagnostics.isEmpty()
					? fail(DiagnosticCode.SYNTAX_ERROR, "Java parser returned no compilation unit")
					: new JavaParseResult(Status.FAILED, Optional.empty(), diagnostics, Optional.empty());
			return new BridgedParseResult<>(failure, Optional.empty());
		}

		return extractAndProject(input, parsed.getResult().orElseThrow(), lines, projector);
	}

	JavaParseResult extractParsed(SourceInput input, CompilationUnit unit) {
		return extractAndProject(input, unit,
				(sourceInput, compilationUnit, parsedSource, sourceLines) -> Boolean.TRUE).parseResult();
	}

	<T> BridgedParseResult<T> extractAndProject(SourceInput input, CompilationUnit unit,
			ParsedUnitProjection<T> projector) {
		Objects.requireNonNull(projector, "projector");
		var lines = input.sourceContent().lines().toList();
		return extractAndProject(input, unit, lines, projector);
	}

	static final class ProjectionFailure extends RuntimeException {
		final DiagnosticCode code;

		ProjectionFailure(DiagnosticCode code, String message) {
			super(message);
			this.code = Objects.requireNonNull(code, "code");
		}
	}

	private <T> BridgedParseResult<T> extractAndProject(SourceInput input, CompilationUnit unit, List<String> lines,
			ParsedUnitProjection<T> projector) {
		try {
			var source = JavaDeclarationExtractor.extract(input.filePath(), unit, lines);
			var projection = Objects.requireNonNull(projector.project(input, unit, source, lines), "projection");
			var result = new JavaParseResult(Status.SUCCESS, Optional.of(source), List.of(), Optional.empty());
			return new BridgedParseResult<>(result, Optional.of(projection));
		} catch (JavaDeclarationExtractor.ExtractionFailure failure) {
			return new BridgedParseResult<>(fail(failure.code, failure.getMessage()), Optional.empty());
		} catch (ProjectionFailure failure) {
			return new BridgedParseResult<>(fail(failure.code, failure.getMessage()), Optional.empty());
		}
	}

	private static JavaParseResult fail(DiagnosticCode code, String message) {
		return new JavaParseResult(Status.FAILED, Optional.empty(),
				List.of(new ParseDiagnostic(code, Severity.ERROR, message, Optional.empty())), Optional.empty());
	}
}
