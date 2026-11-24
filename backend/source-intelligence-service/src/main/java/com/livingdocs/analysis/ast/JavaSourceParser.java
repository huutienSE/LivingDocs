package com.livingdocs.analysis.ast;

import static com.livingdocs.analysis.ast.JavaParseResult.*;

import java.util.List;
import java.util.Optional;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

public final class JavaSourceParser {

	public JavaParseResult parse(SourceInput input) {
		if (input == null || input.filePath() == null || input.filePath().isBlank()) {
			return fail(DiagnosticCode.INVALID_INPUT, "A source file path is required");
		}
		if (!input.filePath().endsWith(".java")) {
			return new JavaParseResult(Status.OUT_OF_SCOPE, Optional.empty(), List.of(new ParseDiagnostic(
					DiagnosticCode.UNSUPPORTED_LANGUAGE, Severity.INFO, "Expected a .java file", Optional.empty())),
					Optional.empty());
		}
		if (input.sourceContent() == null) {
			return fail(DiagnosticCode.INVALID_INPUT, "Java source content is required");
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
			return diagnostics.isEmpty() ? fail(DiagnosticCode.SYNTAX_ERROR, "Java parser returned no compilation unit")
					: new JavaParseResult(Status.FAILED, Optional.empty(), diagnostics, Optional.empty());
		}

		return extractParsed(input, parsed.getResult().orElseThrow());
	}

	JavaParseResult extractParsed(SourceInput input, CompilationUnit unit) {
		try {
			var source = JavaDeclarationExtractor.extract(input.filePath(), unit, input.sourceContent().lines().toList());
			return new JavaParseResult(Status.SUCCESS, Optional.of(source), List.of(), Optional.empty());
		} catch (JavaDeclarationExtractor.ExtractionFailure failure) {
			return fail(failure.code, failure.getMessage());
		}
	}

	private static JavaParseResult fail(DiagnosticCode code, String message) {
		return new JavaParseResult(Status.FAILED, Optional.empty(),
				List.of(new ParseDiagnostic(code, Severity.ERROR, message, Optional.empty())), Optional.empty());
	}
}
