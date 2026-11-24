package com.livingdocs.analysis.ast;

import static com.livingdocs.analysis.ast.JavaParseResult.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import com.github.javaparser.JavaToken;
import com.github.javaparser.Range;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.Type;

final class JavaDeclarationExtractor {

	static ParsedSource extract(String path, CompilationUnit unit, List<String> lines) {
		var packageName = unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString());
		var imports = unit.getImports().stream().map(declaration -> new ImportDeclaration(
				declaration.getNameAsString(), declaration.isStatic(), declaration.isAsterisk())).toList();
		var entities = new ArrayList<SourceEntity>();

		for (var declaration : unit.getTypes()) {
			extractType(declaration, packageName, List.of(), entities, lines);
		}
		entities.sort(java.util.Comparator.comparingInt((SourceEntity e) -> e.declarationRange().startLine())
				.thenComparingInt(e -> e.declarationRange().startColumn())
				.thenComparingInt(e -> e.declarationRange().endLine())
				.thenComparingInt(e -> e.declarationRange().endColumn())
				.thenComparing(e -> e.key().kind()).thenComparing(e -> e.key().name()));

		var keys = new HashSet<SymbolKey>();
		for (var entity : entities) {
			if (!keys.add(entity.key())) {
				throw new ExtractionFailure(DiagnosticCode.DUPLICATE_SYMBOL_IDENTITY,
						"Duplicate symbol key: " + entity.key());
			}
		}
		var module = unit.getModule().map(m -> new ModuleContext(m.getNameAsString(), m.isOpen(),
				annotationUsages(m, lines), m.getDirectives().stream().map(node -> reference(node, lines)).toList(),
				requiredRange(m, lines)));
		var packageUsages = unit.getPackageDeclaration()
				.map(declaration -> annotationUsages(declaration, lines)).orElse(List.of());
		return new ParsedSource(path, packageName, imports,
				new FileContext(packageUsages, module), entities);
	}

	private static void extractType(TypeDeclaration<?> type, Optional<String> pkg, List<String> owners,
			List<SourceEntity> output, List<String> lines) {
		Kind kind;
		var typeParameters = List.<String>of();
		var extended = List.<String>of();
		var implemented = List.<String>of();
		var permitted = List.<String>of();
		if (type instanceof ClassOrInterfaceDeclaration declaration) {
			kind = declaration.isInterface() ? Kind.INTERFACE : Kind.CLASS;
			typeParameters = declaration.getTypeParameters().stream().map(Object::toString).toList();
			extended = declaration.getExtendedTypes().stream().map(Object::toString).toList();
			implemented = declaration.getImplementedTypes().stream().map(Object::toString).toList();
			permitted = declaration.getPermittedTypes().stream().map(Object::toString).toList();
		} else if (type instanceof EnumDeclaration declaration) {
			kind = Kind.ENUM;
			implemented = declaration.getImplementedTypes().stream().map(Object::toString).toList();
		} else if (type instanceof RecordDeclaration declaration) {
			kind = Kind.RECORD;
			typeParameters = declaration.getTypeParameters().stream().map(Object::toString).toList();
			implemented = declaration.getImplementedTypes().stream().map(Object::toString).toList();
		} else if (type instanceof AnnotationDeclaration) {
			kind = Kind.ANNOTATION;
		} else {
			throw deferred("type " + type.getNameAsString());
		}
		var typeKey = new SymbolKey(kind, pkg, owners, type.getNameAsString(), List.of());
		var childOwners = new ArrayList<>(owners);
		childOwners.add(type.getNameAsString());
		var children = new ArrayList<SourceEntity>();
		if (type instanceof RecordDeclaration record) {
			for (var component : record.getParameters()) {
				var key = new SymbolKey(Kind.RECORD_COMPONENT, pkg, childOwners, component.getNameAsString(), List.of());
				var typeSyntax = typeSyntax(component.getType(), component.isVarArgs(), component.getName(), false);
				children.add(new SourceEntity(key, modifiers(component), parameterAnnotations(component, lines),
						requiredRange(component, lines), new RecordComponentDetails(typeSyntax)));
			}
		}
		if (type instanceof EnumDeclaration declaration) {
			for (var constant : declaration.getEntries()) {
				var key = new SymbolKey(Kind.ENUM_CONSTANT, pkg, childOwners, constant.getNameAsString(), List.of());
				var bodyRange = enumBodyRange(constant, lines);
				var argumentReferences = constant.getArguments().stream().map(argument -> reference(argument, lines)).toList();
				children.add(new SourceEntity(key, List.of(), annotationUsages(constant, lines), requiredRange(constant, lines),
						new EnumConstantDetails(argumentReferences, bodyRange)));
			}
		}
		for (var member : type.getMembers()) {
			if (member instanceof TypeDeclaration<?> nested) {
				extractType(nested, pkg, childOwners, children, lines);
			} else if (member instanceof FieldDeclaration field) {
				var fieldRange = requiredRange(field, lines);
				var fieldModifiers = modifiers(field);
				var fieldAnnotations = annotationUsages(field, lines);
				for (var variable : field.getVariables()) {
					var key = new SymbolKey(Kind.FIELD, pkg, childOwners, variable.getNameAsString(), List.of());
					var details = new FieldDetails(typeSyntax(variable.getType(), false, variable.getName(), false), fieldRange,
							variable.getInitializer().map(initializer -> requiredRange(initializer, lines)));
					children.add(new SourceEntity(key, fieldModifiers, fieldAnnotations,
							requiredRange(variable, lines), details));
				}
			} else if (member instanceof MethodDeclaration method) {
				children.add(extractMethod(method, typeKey, childOwners, lines));
			} else if (member instanceof ConstructorDeclaration constructor) {
				var key = new SymbolKey(Kind.CONSTRUCTOR, pkg, childOwners, constructor.getNameAsString(),
						constructor.getParameters().stream().map(JavaDeclarationExtractor::parameterKey).toList());
				var details = new ConstructorDetails(constructor.getParameters().stream().map(parameter -> parameter(parameter, lines)).toList(),
						constructor.getTypeParameters().stream().map(Object::toString).toList(),
						constructor.getThrownExceptions().stream().map(Object::toString).toList(), false,
						Optional.of(requiredRange(constructor.getBody(), lines)),
						constructor.getReceiverParameter().map(receiver -> reference(receiver, lines)));
				children.add(new SourceEntity(key, modifiers(constructor), annotationUsages(constructor, lines),
						requiredRange(constructor, lines), details));
			} else if (member instanceof CompactConstructorDeclaration constructor && type instanceof RecordDeclaration record) {
				var key = new SymbolKey(Kind.CONSTRUCTOR, pkg, childOwners, constructor.getNameAsString(),
						record.getParameters().stream().map(JavaDeclarationExtractor::parameterKey).toList());
				var details = new ConstructorDetails(record.getParameters().stream().map(parameter -> parameter(parameter, lines)).toList(),
						constructor.getTypeParameters().stream().map(Object::toString).toList(),
						constructor.getThrownExceptions().stream().map(Object::toString).toList(), true,
						Optional.of(requiredRange(constructor.getBody(), lines)), Optional.empty());
				children.add(new SourceEntity(key, modifiers(constructor), annotationUsages(constructor, lines),
						requiredRange(constructor, lines), details));
			} else if (member instanceof AnnotationMemberDeclaration annotationMember) {
				var key = new SymbolKey(Kind.ANNOTATION_MEMBER, pkg, childOwners, annotationMember.getNameAsString(), List.of());
				var memberType = typeSyntax(annotationMember.getType(), false, annotationMember.getName(), true);
				var details = new AnnotationMemberDetails(memberType,
						annotationMember.getDefaultValue().map(value -> reference(value, lines)));
				children.add(new SourceEntity(key, modifiers(annotationMember), annotationUsages(annotationMember, lines),
						requiredRange(annotationMember, lines), details));
			} else if (!(member instanceof InitializerDeclaration)) {
				throw deferred(member.getMetaModel().getTypeName() + " in " + type.getNameAsString());
			}
		}
		var memberKeys = children.stream().filter(child -> child.key().ownerTypes().equals(childOwners))
				.map(SourceEntity::key).toList();
		output.add(new SourceEntity(typeKey, modifiers(type), annotationUsages(type, lines), requiredRange(type, lines),
				new TypeDetails(memberKeys, typeParameters, extended, implemented, permitted)));
		output.addAll(children);
	}

	private static List<String> modifiers(NodeWithModifiers<?> node) {
		return node.getModifiers().stream().map(modifier -> modifier.getKeyword().asString()).toList();
	}

	private static List<AnnotationUsage> annotationUsages(NodeWithAnnotations<?> node, List<String> lines) {
		return node.getAnnotations().stream().map(annotation -> annotationUsage(annotation, lines)).toList();
	}

	private static AnnotationUsage annotationUsage(com.github.javaparser.ast.expr.AnnotationExpr annotation,
			List<String> lines) {
		List<SourceReference> arguments;
		if (annotation.isSingleMemberAnnotationExpr()) {
			arguments = List.of(reference(annotation.asSingleMemberAnnotationExpr().getMemberValue(), lines));
		} else if (annotation.isNormalAnnotationExpr()) {
			arguments = annotation.asNormalAnnotationExpr().getPairs().stream()
					.map(pair -> reference(pair, lines)).toList();
		} else {
			arguments = List.of();
		}
		return new AnnotationUsage(reference(annotation, lines), arguments);
	}

	private static SourceReference reference(Node node, List<String> lines) {
		return new SourceReference(node.getTokenRange().map(Object::toString)
				.orElseThrow(() -> deferred("source tokens for " + node.getMetaModel().getTypeName())),
				requiredRange(node, lines));
	}

	private static Optional<SourceRange> enumBodyRange(EnumConstantDeclaration constant, List<String> lines) {
		var tokens = constant.getTokenRange().orElseThrow(() -> deferred("enum body tokens"));
		var tokenList = new ArrayList<com.github.javaparser.JavaToken>();
		tokens.forEach(token -> {
			if (!token.getCategory().isWhitespaceOrComment()) tokenList.add(token);
		});
		if (tokenList.isEmpty() || !tokenList.getLast().getText().equals("}")) {
			return Optional.empty();
		}
		int depth = 0;
		com.github.javaparser.Range end = null;
		for (int i = tokenList.size() - 1; i >= 0; i--) {
			var token = tokenList.get(i);
			if (token.getText().equals("}")) {
				if (depth++ == 0) end = token.getRange().orElse(null);
			} else if (token.getText().equals("{") && --depth == 0 && end != null) {
				var begin = token.getRange().orElseThrow(() -> deferred("enum body start"));
				return Optional.of(sourceRange(new Range(begin.begin, end.end), lines)
						.orElseThrow(() -> new ExtractionFailure(DiagnosticCode.SOURCE_RANGE_UNAVAILABLE, "Invalid enum body range")));
			}
		}
		throw new ExtractionFailure(DiagnosticCode.SOURCE_RANGE_UNAVAILABLE, "Missing enum body range");
	}

	private static TypeSyntax typeSyntax(Type type, boolean varargs, Node name, boolean callable) {
		// NAME-origin dimensions span the declarator in JavaParser's token range.
		Type prefix = type;
		while (prefix instanceof ArrayType array && array.getOrigin() == ArrayType.Origin.NAME) {
			prefix = array.getComponentType();
		}
		var spelling = prefix.getTokenRange().orElseThrow(() -> deferred("type tokens")).toString();
		if (prefix != type) {
			var tokens = type.getTokenRange().orElseThrow(() -> deferred("postfix array type tokens"));
			var declaratorEnd = declaratorEnd(tokens, name, callable);
			var suffixStart = declaratorEnd.getNextToken().orElseThrow(() -> deferred("postfix array dimensions"));
			var suffix = new TokenRange(suffixStart, tokens.getEnd()).toString().stripLeading();
			spelling += suffix.startsWith("@") ? " " + suffix : suffix;
		}
		return new TypeSyntax(spelling, type.getArrayLevel(), varargs);
	}

	private static JavaToken declaratorEnd(TokenRange tokens, Node name, boolean callable) {
		boolean foundName = false;
		int depth = 0;
		for (var token : tokens) {
			if (!foundName) {
				if (isNameToken(token, name)) {
					if (!callable) {
						return token;
					}
					foundName = true;
				}
			} else if (token.getText().equals("(")) {
				depth++;
			} else if (token.getText().equals(")") && --depth == 0) {
				return token;
			}
		}
		throw deferred("declarator boundary for postfix dimensions");
	}

	private static List<AnnotationUsage> parameterAnnotations(Parameter parameter, List<String> lines) {
		var usages = new ArrayList<>(annotationUsages(parameter, lines));
		parameter.getVarArgsAnnotations().forEach(annotation -> usages.add(annotationUsage(annotation, lines)));
		return usages;
	}

	private static ParameterDetails parameter(Parameter parameter, List<String> lines) {
		var type = typeSyntax(parameter.getType(), parameter.isVarArgs(), parameter.getName(), false);
		return new ParameterDetails(parameter.getNameAsString(), type, modifiers(parameter),
				parameterAnnotations(parameter, lines));
	}

	private static SyntacticTypeKey parameterKey(Parameter parameter) {
		var tokens = parameter.getType().getTokenRange().orElseThrow(() -> deferred("parameter type tokens"));
		var parts = new ArrayList<String>();
		for (var token : tokens) {
			if (!token.getCategory().isWhitespaceOrComment() && !isNameToken(token, parameter.getName())) {
				parts.add(token.getText());
			}
		}
		for (var annotation : parameter.getVarArgsAnnotations()) {
			var annotationTokens = annotation.getTokenRange().orElseThrow(() -> deferred("varargs annotation tokens"));
			for (var token : annotationTokens) {
				if (!token.getCategory().isWhitespaceOrComment()) parts.add(token.getText());
			}
		}
		return new SyntacticTypeKey(parts, parameter.isVarArgs());
	}

	private static boolean isNameToken(JavaToken token, Node name) {
		return token.getRange().isPresent() && name.getRange().isPresent()
				&& token.getRange().orElseThrow().begin.equals(name.getRange().orElseThrow().begin);
	}

	private static SourceEntity extractMethod(MethodDeclaration method, SymbolKey owner, List<String> childOwners, List<String> lines) {
		var returnType = method.getType();
		var parameters = method.getParameters().stream().map(parameter -> parameter(parameter, lines)).toList();
		var parameterKeys = method.getParameters().stream().map(JavaDeclarationExtractor::parameterKey).toList();
		var key = new SymbolKey(Kind.METHOD, owner.packageName(), childOwners,
				method.getNameAsString(), parameterKeys);
		var bodyRange = method.getBody().map(body -> requiredRange(body, lines));
		var returnSpelling = typeSyntax(returnType, false, method.getName(), true).rawSpelling();
		var details = new MethodDetails(returnSpelling, bodyRange, parameters,
				method.getTypeParameters().stream().map(Object::toString).toList(),
				method.getThrownExceptions().stream().map(Object::toString).toList(),
				method.getReceiverParameter().map(receiver -> reference(receiver, lines)));
		return new SourceEntity(key, modifiers(method), annotationUsages(method, lines),
				requiredRange(method, lines), details);
	}

	private static SourceRange requiredRange(Node node, List<String> lines) {
		return node.getRange().flatMap(range -> sourceRange(range, lines))
				.orElseThrow(() -> new ExtractionFailure(DiagnosticCode.SOURCE_RANGE_UNAVAILABLE,
						"Missing or invalid source range for " + node.getMetaModel().getTypeName()));
	}

	static Optional<SourceRange> sourceRange(Range range, List<String> lines) {
		var start = range.begin;
		var end = range.end;
		if (start.line < 1 || end.line > lines.size() || start.compareTo(end) > 0
				|| start.column < 1 || end.column < 1 || start.column > lines.get(start.line - 1).length()
				|| end.column > lines.get(end.line - 1).length()) {
			return Optional.empty();
		}
		return Optional.of(new SourceRange(start.line, start.column, end.line, end.column));
	}

	private static ExtractionFailure deferred(String construct) {
		return new ExtractionFailure(DiagnosticCode.EXTRACTION_FAILURE,
				"Source extraction cannot represent " + construct);
	}

	static final class ExtractionFailure extends RuntimeException {
		final DiagnosticCode code;

		private ExtractionFailure(DiagnosticCode code, String message) {
			super(message);
			this.code = code;
		}
	}
}
