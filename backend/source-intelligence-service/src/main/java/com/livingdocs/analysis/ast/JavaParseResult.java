package com.livingdocs.analysis.ast;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record JavaParseResult(
		Status status,
		Optional<ParsedSource> parsedSource,
		List<ParseDiagnostic> diagnostics,
		Optional<Coverage> coverage) {

	public JavaParseResult {
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(parsedSource, "parsedSource");
		Objects.requireNonNull(coverage, "coverage");
		diagnostics = List.copyOf(diagnostics);
		boolean needsSource = status == Status.SUCCESS || status == Status.PARTIAL;
		if (parsedSource.isPresent() != needsSource || coverage.isPresent() != (status == Status.PARTIAL)) {
			throw new IllegalArgumentException("Parsed source and coverage must match the result status");
		}
		if (status != Status.SUCCESS && diagnostics.isEmpty()) {
			throw new IllegalArgumentException("A non-success result requires diagnostics");
		}
		if (status == Status.SUCCESS && diagnostics.stream()
				.anyMatch(diagnostic -> diagnostic.severity() == Severity.ERROR)) {
			throw new IllegalArgumentException("A successful result cannot contain error diagnostics");
		}
	}

	public enum Status {
		SUCCESS, PARTIAL, FAILED, OUT_OF_SCOPE
	}

	public enum DiagnosticCode {
		INVALID_INPUT, SYNTAX_ERROR, UNSUPPORTED_SYNTAX, UNSUPPORTED_LANGUAGE,
		SOURCE_RANGE_UNAVAILABLE, DUPLICATE_SYMBOL_IDENTITY, EXTRACTION_FAILURE
	}

	public enum Severity {
		INFO, ERROR
	}

	public enum Kind {
		CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, FIELD, METHOD, CONSTRUCTOR,
		ENUM_CONSTANT, RECORD_COMPONENT, ANNOTATION_MEMBER
	}

	public record ParsedSource(
			String filePath,
			Optional<String> packageName,
			List<ImportDeclaration> imports,
			FileContext fileContext,
			List<SourceEntity> entities) {

		public ParsedSource {
			Objects.requireNonNull(filePath, "filePath");
			Objects.requireNonNull(packageName, "packageName");
			imports = List.copyOf(imports);
			Objects.requireNonNull(fileContext, "fileContext");
			entities = List.copyOf(entities);
		}
	}

	public record SourceReference(String source, SourceRange range) {
		public SourceReference {
			Objects.requireNonNull(source, "source");
			Objects.requireNonNull(range, "range");
		}
	}

	public record AnnotationUsage(SourceReference reference, List<SourceReference> arguments) {
		public AnnotationUsage {
			Objects.requireNonNull(reference, "reference");
			arguments = List.copyOf(arguments);
		}
	}

	public record FileContext(List<AnnotationUsage> packageAnnotationUsages, Optional<ModuleContext> module) {
		public FileContext {
			Objects.requireNonNull(module, "module");
			packageAnnotationUsages = List.copyOf(packageAnnotationUsages);
		}

		public List<SourceReference> packageAnnotations() {
			return packageAnnotationUsages.stream().map(AnnotationUsage::reference).toList();
		}
	}

	public record ModuleContext(String name, boolean open, List<AnnotationUsage> annotationUsages,
			List<SourceReference> directives, SourceRange range) {
		public ModuleContext {
			Objects.requireNonNull(name, "name");
			directives = List.copyOf(directives);
			Objects.requireNonNull(range, "range");
			annotationUsages = List.copyOf(annotationUsages);
		}

		public List<SourceReference> annotations() {
			return annotationUsages.stream().map(AnnotationUsage::reference).toList();
		}
	}

	public record ImportDeclaration(String name, boolean isStatic, boolean wildcard) {

		public ImportDeclaration {
			Objects.requireNonNull(name, "name");
		}
	}

	public record SourceEntity(
			SymbolKey key,
			List<String> modifiers,
			List<AnnotationUsage> annotationUsages,
			SourceRange declarationRange,
			DeclarationDetails details) {

		public SourceEntity {
			Objects.requireNonNull(key, "key");
			modifiers = List.copyOf(modifiers);
			annotationUsages = List.copyOf(annotationUsages);
			Objects.requireNonNull(declarationRange, "declarationRange");
			Objects.requireNonNull(details, "details");
			boolean compatible = switch (key.kind()) {
				case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION -> details instanceof TypeDetails;
				case METHOD -> details instanceof MethodDetails;
				case FIELD -> details instanceof FieldDetails;
				case CONSTRUCTOR -> details instanceof ConstructorDetails;
				case ENUM_CONSTANT -> details instanceof EnumConstantDetails;
				case RECORD_COMPONENT -> details instanceof RecordComponentDetails;
				case ANNOTATION_MEMBER -> details instanceof AnnotationMemberDetails;
			};
			if (!compatible) {
				throw new IllegalArgumentException("Declaration details must match the symbol kind");
			}
		}

		public List<String> annotations() {
			return annotationUsages.stream().map(usage -> usage.reference().source()).toList();
		}
	}

	public record SymbolKey(
			Kind kind,
			Optional<String> packageName,
			List<String> ownerTypes,
			String name,
			List<SyntacticTypeKey> parameterTypes) {

		public SymbolKey {
			Objects.requireNonNull(kind, "kind");
			Objects.requireNonNull(packageName, "packageName");
			ownerTypes = List.copyOf(ownerTypes);
			Objects.requireNonNull(name, "name");
			parameterTypes = List.copyOf(parameterTypes);
		}
	}

	public record SyntacticTypeKey(List<String> tokens, boolean varargs) {

		public SyntacticTypeKey {
			tokens = List.copyOf(tokens);
		}
	}

	public sealed interface DeclarationDetails permits TypeDetails, MethodDetails, FieldDetails,
			ConstructorDetails, EnumConstantDetails, RecordComponentDetails, AnnotationMemberDetails {
	}

	public record TypeSyntax(String rawSpelling, int arrayDimensions, boolean varargs) {
		public TypeSyntax {
			Objects.requireNonNull(rawSpelling, "rawSpelling");
			if (arrayDimensions < 0) {
				throw new IllegalArgumentException("Array dimensions cannot be negative");
			}
		}
	}

	public record ParameterDetails(String name, TypeSyntax type, List<String> modifiers,
			List<AnnotationUsage> annotationUsages) {
		public ParameterDetails {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(type, "type");
			modifiers = List.copyOf(modifiers);
			annotationUsages = List.copyOf(annotationUsages);
		}

		public List<String> annotations() {
			return annotationUsages.stream().map(usage -> usage.reference().source()).toList();
		}
	}

	public record FieldDetails(TypeSyntax type, SourceRange sharedDeclarationRange,
			Optional<SourceRange> initializerRange) implements DeclarationDetails {
		public FieldDetails {
			Objects.requireNonNull(type, "type");
			Objects.requireNonNull(sharedDeclarationRange, "sharedDeclarationRange");
			Objects.requireNonNull(initializerRange, "initializerRange");
		}
	}

	public record ConstructorDetails(List<ParameterDetails> parameters, List<String> typeParameters,
			List<String> thrownTypes, boolean compact, Optional<SourceRange> bodyRange,
			Optional<SourceReference> receiver)
			implements DeclarationDetails {
		public ConstructorDetails {
			parameters = List.copyOf(parameters);
			typeParameters = List.copyOf(typeParameters);
			thrownTypes = List.copyOf(thrownTypes);
			Objects.requireNonNull(bodyRange, "bodyRange");
			Objects.requireNonNull(receiver, "receiver");
		}
	}

	public record EnumConstantDetails(List<SourceReference> argumentReferences, Optional<SourceRange> bodyRange)
			implements DeclarationDetails {
		public EnumConstantDetails {
			Objects.requireNonNull(bodyRange, "bodyRange");
			argumentReferences = List.copyOf(argumentReferences);
		}

		public List<String> arguments() {
			return argumentReferences.stream().map(SourceReference::source).toList();
		}
	}

	public record RecordComponentDetails(TypeSyntax type) implements DeclarationDetails {
		public RecordComponentDetails {
			Objects.requireNonNull(type, "type");
		}
	}

	public record AnnotationMemberDetails(TypeSyntax type, Optional<SourceReference> defaultValue)
			implements DeclarationDetails {
		public AnnotationMemberDetails {
			Objects.requireNonNull(type, "type");
			Objects.requireNonNull(defaultValue, "defaultValue");
		}
	}

	public record TypeDetails(List<SymbolKey> members, List<String> typeParameters,
			List<String> extendedTypes, List<String> implementedTypes, List<String> permittedTypes)
			implements DeclarationDetails {

		public TypeDetails {
			members = List.copyOf(members);
			typeParameters = List.copyOf(typeParameters);
			extendedTypes = List.copyOf(extendedTypes);
			implementedTypes = List.copyOf(implementedTypes);
			permittedTypes = List.copyOf(permittedTypes);
		}
	}

	public record MethodDetails(String returnTypeSpelling, Optional<SourceRange> bodyRange,
			List<ParameterDetails> parameters, List<String> typeParameters, List<String> thrownTypes,
			Optional<SourceReference> receiver)
			implements DeclarationDetails {

		public MethodDetails {
			Objects.requireNonNull(returnTypeSpelling, "returnTypeSpelling");
			Objects.requireNonNull(bodyRange, "bodyRange");
			parameters = List.copyOf(parameters);
			typeParameters = List.copyOf(typeParameters);
			thrownTypes = List.copyOf(thrownTypes);
			Objects.requireNonNull(receiver, "receiver");
		}
	}

	public record SourceRange(int startLine, int startColumn, int endLine, int endColumn) {

		public SourceRange {
			if (startLine < 1 || startColumn < 1 || endLine < startLine
					|| endColumn < 1 || (startLine == endLine && endColumn < startColumn)) {
				throw new IllegalArgumentException("Expected an ordered, 1-based source range");
			}
		}
	}

	public record ParseDiagnostic(
			DiagnosticCode code, Severity severity, String message, Optional<SourceRange> range) {

		public ParseDiagnostic {
			Objects.requireNonNull(code, "code");
			Objects.requireNonNull(severity, "severity");
			Objects.requireNonNull(message, "message");
			Objects.requireNonNull(range, "range");
		}
	}

	public record Coverage(List<SourceRange> verifiedRanges, List<CoverageGap> gaps) {

		public Coverage {
			verifiedRanges = List.copyOf(verifiedRanges);
			gaps = List.copyOf(gaps);
			if (verifiedRanges.isEmpty() || gaps.isEmpty()) {
				throw new IllegalArgumentException("Partial coverage requires verified ranges and explicit gaps");
			}
		}
	}

	public record CoverageGap(Optional<SourceRange> range, String reason) {

		public CoverageGap {
			Objects.requireNonNull(range, "range");
			if (Objects.requireNonNull(reason, "reason").isBlank()) {
				throw new IllegalArgumentException("A coverage gap requires a reason");
			}
		}
	}
}
