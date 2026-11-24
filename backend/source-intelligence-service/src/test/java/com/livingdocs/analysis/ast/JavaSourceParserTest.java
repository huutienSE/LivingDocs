package com.livingdocs.analysis.ast;

import static com.livingdocs.analysis.ast.JavaParseResult.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.sun.source.util.JavacTask;
import org.junit.jupiter.api.Test;

class JavaSourceParserTest {

	private final JavaSourceParser parser = new JavaSourceParser();

	@Test
	void rv01AndRv12ParseFoundationInventoryWithSourceRanges() {
		var input = new SourceInput("src/Example.java", """
				package sample;
				import java.util.List;
				import static java.util.Collections.*;
				public class Example {
				    int count() { return 1; }
				}
				""");

		var result = parser.parse(input);
		assertEquals(Status.SUCCESS, result.status());
		assertTrue(result.diagnostics().isEmpty());
		assertEquals(result, parser.parse(input));
		var source = result.parsedSource().orElseThrow();
		assertEquals(input.filePath(), source.filePath());
		assertEquals(Optional.of("sample"), source.packageName());
		assertEquals(List.of(new ImportDeclaration("java.util.List", false, false),
				new ImportDeclaration("java.util.Collections", true, true)), source.imports());
		assertEquals(2, source.entities().size());
		var type = source.entities().get(0);
		var method = source.entities().get(1);
		assertEquals(new SymbolKey(Kind.CLASS, Optional.of("sample"), List.of(), "Example", List.of()), type.key());
		assertEquals(new SymbolKey(Kind.METHOD, Optional.of("sample"), List.of("Example"), "count", List.of()),
				method.key());
		assertEquals(List.of("public"), type.modifiers());
		assertEquals(new TypeDetails(List.of(method.key()), List.of(), List.of(), List.of(), List.of()), type.details());
		assertEquals(new SourceRange(4, 1, 6, 1), type.declarationRange());
		assertEquals(new SourceRange(5, 5, 5, 29), method.declarationRange());
		var details = assertInstanceOf(MethodDetails.class, method.details());
		assertEquals("int", details.returnTypeSpelling());
		assertEquals(Optional.of(new SourceRange(5, 17, 5, 29)), details.bodyRange());
		var sourceLine = input.sourceContent().lines().toList().get(4);
		var bodyRange = details.bodyRange().orElseThrow();
		assertEquals("{ return 1; }", sourceLine.substring(bodyRange.startColumn() - 1, bodyRange.endColumn()));
	}

	@Test
	void rv02AcceptsOldJavaAndFinalizedJava21PatternSwitch() {
		assertEquals(Status.SUCCESS, parser.parse(new SourceInput("Legacy.java", "class Legacy {}")).status());
		var result = parser.parse(new SourceInput("Example.java", """
				class Example {
				    int length() {
				        Object value = "abc";
				        return switch (value) {
				            case String s when !s.isEmpty() -> s.length();
				            default -> 0;
				        };
				    }
				}
				"""));
		assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
		var method = result.parsedSource().orElseThrow().entities().get(1);
		assertEquals("length", method.key().name());
		assertTrue(assertInstanceOf(MethodDetails.class, method.details()).bodyRange().isPresent());
	}

	@Test
	void rv03RejectsJava21PreviewAndPost21Syntax() throws Exception {
		String preview = """
				import static java.lang.StringTemplate.STR;
				class Example {
				    void render() {
				        String name = "A";
				        String result = STR."Hello \\{name}";
				    }
				}
				""";
		String post21 = "import module java.base; class Example {}";
		assertTrue(preview.contains("\\{name}"));
		assertValidJava21Preview(preview);
		assertTrue(libraryParser(LanguageLevel.JAVA_25).parse(post21).isSuccessful());
		var previewResult = parser.parse(new SourceInput("Example.java", preview));
		assertFailure(previewResult, DiagnosticCode.SYNTAX_ERROR);
		assertFailure(parser.parse(new SourceInput("Example.java", post21)), DiagnosticCode.UNSUPPORTED_SYNTAX);
	}

	@Test
	void rv13RejectsMalformedSourceEvenWithARecoveredCompilationUnit() {
		String malformed = "class Example { void broken() { int value = ; } }";
		var recovered = libraryParser(LanguageLevel.JAVA_21).parse(malformed);
		assertTrue(recovered.getResult().isPresent());
		assertFalse(recovered.isSuccessful());
		assertFailure(parser.parse(new SourceInput("Example.java", malformed)), DiagnosticCode.SYNTAX_ERROR);
	}

	@Test
	void rv04AndRv06ExtractRecordAndNestedTypes() {
		for (String source : List.of("record Example(int value) {}",
				"class Example { class Nested {} }")) {
			assertTrue(libraryParser(LanguageLevel.JAVA_21).parse(source).isSuccessful());
			var result = parser.parse(new SourceInput("Example.java", source));
			assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
			assertEquals(2, result.parsedSource().orElseThrow().entities().size());
		}
	}

	@Test
	void rv04AndRv05ExtractKindsAndMetadata() {
		String source = """
				package demo;
				@interface Mark { String value() default "ok"; }
				@Mark("yes") interface Api { void run(); }
				record Pair(int left, String right) {
				    Pair { if (left < 0) throw new IllegalArgumentException(); }
				}
				enum Choice {
				    ONE(1) { void run() {} }, TWO(2);
				    Choice(int number) {}
				}
				""";
		var result = parser.parse(new SourceInput("All.java", source));
		assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
		var entities = result.parsedSource().orElseThrow().entities();
		for (var kind : List.of(Kind.ANNOTATION, Kind.ANNOTATION_MEMBER, Kind.INTERFACE, Kind.METHOD,
				Kind.RECORD, Kind.RECORD_COMPONENT, Kind.CONSTRUCTOR, Kind.ENUM, Kind.ENUM_CONSTANT)) {
			assertTrue(entities.stream().anyMatch(e -> e.key().kind() == kind), kind.toString());
		}
		var annotationMember = entities.stream().filter(e -> e.key().kind() == Kind.ANNOTATION_MEMBER).findFirst().orElseThrow();
		var defaultValue = ((AnnotationMemberDetails) annotationMember.details()).defaultValue().orElseThrow();
		assertEquals("\"ok\"", defaultValue.source());
		assertReferenceMatchesSource(source, defaultValue);
		var api = entities.stream().filter(e -> e.key().name().equals("Api")).findFirst().orElseThrow();
		assertEquals(List.of("@Mark(\"yes\")"), api.annotations());
		var compact = entities.stream().filter(e -> e.key().kind() == Kind.CONSTRUCTOR)
				.filter(e -> ((ConstructorDetails) e.details()).compact()).findFirst().orElseThrow();
		assertEquals(List.of("left", "right"), ((ConstructorDetails) compact.details()).parameters()
				.stream().map(ParameterDetails::name).toList());
		assertEquals(List.of("int", "String"), ((ConstructorDetails) compact.details()).parameters()
				.stream().map(parameter -> parameter.type().rawSpelling()).toList());
		var one = entities.stream().filter(e -> e.key().name().equals("ONE")).findFirst().orElseThrow();
		var constantDetails = assertInstanceOf(EnumConstantDetails.class, one.details());
		assertEquals("{ void run() {} }", sourceSlice(source, constantDetails.bodyRange().orElseThrow()));
		assertEquals(List.of("1"), constantDetails.arguments());
		var enumType = entities.stream().filter(entity -> entity.key().kind() == Kind.ENUM).findFirst().orElseThrow();
		assertEquals(List.of("ONE", "TWO", "Choice"), assertInstanceOf(TypeDetails.class, enumType.details())
				.members().stream().map(SymbolKey::name).toList());
		assertEquals(result, parser.parse(new SourceInput("All.java", source)));
	}

	@Test
	void rv04RetainsExactDefaultValueAndDistinguishesAnAbsentDefault() {
		String source = "@interface Mark { int value() default 1 +  2; int required(); }";
		var result = parser.parse(new SourceInput("Mark.java", source));
		assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
		var members = result.parsedSource().orElseThrow().entities().stream()
				.filter(entity -> entity.key().kind() == Kind.ANNOTATION_MEMBER).toList();
		assertEquals(List.of("value", "required"), members.stream().map(entity -> entity.key().name()).toList());
		var details = assertInstanceOf(AnnotationMemberDetails.class, members.getFirst().details());
		var defaultValue = details.defaultValue().orElseThrow();
		assertEquals(new SourceReference("1 +  2", new SourceRange(1, 39, 1, 44)), defaultValue);
		assertReferenceMatchesSource(source, defaultValue);
		assertTrue(assertInstanceOf(AnnotationMemberDetails.class, members.getLast().details()).defaultValue().isEmpty());
	}

	@Test
	void rv18RetainsPackageAndModuleContext() {
		var packagePath = "src/test/package-info.java";
		var pkg = parser.parse(new SourceInput(packagePath, "@Deprecated package demo;"));
		assertEquals(Status.SUCCESS, pkg.status());
		var packageResult = pkg.parsedSource().orElseThrow();
		assertEquals(packagePath, packageResult.filePath());
		assertEquals(Optional.of("demo"), packageResult.packageName());
		assertEquals("@Deprecated", packageResult.fileContext().packageAnnotations().getFirst().source());
		assertTrue(packageResult.fileContext().module().isEmpty());
		assertTrue(packageResult.entities().isEmpty());
		var modulePath = "target/module-info.java";
		var moduleSource = "open module demo { requires java.base; exports demo; }";
		var module = parser.parse(new SourceInput(modulePath, moduleSource));
		assertEquals(Status.SUCCESS, module.status(), () -> module.diagnostics().toString());
		var moduleResult = module.parsedSource().orElseThrow();
		assertEquals(modulePath, moduleResult.filePath());
		assertTrue(moduleResult.packageName().isEmpty());
		var context = moduleResult.fileContext().module().orElseThrow();
		assertEquals("demo", context.name());
		assertTrue(context.open());
		assertEquals(List.of("requires java.base;", "exports demo;"),
				context.directives().stream().map(SourceReference::source).toList());
		context.directives().forEach(reference -> assertReferenceMatchesSource(moduleSource, reference));
		assertTrue(moduleResult.entities().isEmpty());
		for (var path : List.of("generated/Sample.java", "src/test/Sample.java", "target/Sample.java")) {
			var result = parser.parse(new SourceInput(path, "class Sample {}"));
			assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
			var parsed = result.parsedSource().orElseThrow();
			assertEquals(path, parsed.filePath());
			assertEquals(List.of(new SymbolKey(Kind.CLASS, Optional.empty(), List.of(), "Sample", List.of())),
					parsed.entities().stream().map(SourceEntity::key).toList());
		}
	}

	@Test
	void annotatedPackageAndModuleRetainArgumentReferences() {
		String packageSource = "@Tag(value = \"package\") package demo;";
		var pkg = parser.parse(new SourceInput("package-info.java", packageSource));
		assertEquals(Status.SUCCESS, pkg.status(), () -> pkg.diagnostics().toString());
		var packageUsage = pkg.parsedSource().orElseThrow().fileContext().packageAnnotationUsages().getFirst();
		assertEquals(List.of(packageUsage.reference()), pkg.parsedSource().orElseThrow().fileContext().packageAnnotations());
		assertEquals("value = \"package\"", packageUsage.arguments().getFirst().source());
		assertReferenceMatchesSource(packageSource, packageUsage.arguments().getFirst());
		String moduleSource = "@Tag(\"module\") open module demo { exports demo; }";
		var module = parser.parse(new SourceInput("module-info.java", moduleSource));
		assertEquals(Status.SUCCESS, module.status(), () -> module.diagnostics().toString());
		var moduleContext = module.parsedSource().orElseThrow().fileContext().module().orElseThrow();
		var moduleUsage = moduleContext.annotationUsages().getFirst();
		assertEquals(List.of(moduleUsage.reference()), moduleContext.annotations());
		assertEquals("\"module\"", moduleUsage.arguments().getFirst().source());
		assertReferenceMatchesSource(moduleSource, moduleUsage.arguments().getFirst());
	}

	@Test
	void rv06OverloadsAndNestedOwnershipAreDistinct() {
		var result = parser.parse(new SourceInput("generated/Many.java",
				"class Outer { void run(int x) {} void run(String x) {} class Inner { void run(int x) {} } } class Other {}"));
		assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
		var entities = result.parsedSource().orElseThrow().entities();
		var methods = entities.stream().filter(e -> e.key().kind() == Kind.METHOD).toList();
		assertEquals(3, methods.size());
		assertEquals(3, methods.stream().map(SourceEntity::key).distinct().count());
		assertEquals(List.of("Outer", "run", "run", "Inner", "run", "Other"),
				entities.stream().map(e -> e.key().name()).toList());
		assertEquals(List.of(List.of("Outer"), List.of("Outer"), List.of("Outer", "Inner")),
				methods.stream().map(e -> e.key().ownerTypes()).toList());
		assertTrue(result.parsedSource().orElseThrow().packageName().isEmpty());
	}

	@Test
	void rv08LocalDeclarationsRemainInEnclosingReferences() {
		var source = "class Example { Object member = new Object() { int hidden; }; "
				+ "void run() { class Local {} Runnable r = () -> {}; Object x = new Object() {}; } }";
		var result = parser.parse(new SourceInput("src/test/Example.java", source));
		assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
		var entities = result.parsedSource().orElseThrow().entities();
		assertEquals(List.of("Example", "member", "run"), entities.stream().map(entity -> entity.key().name()).toList());
		var initializer = assertInstanceOf(FieldDetails.class, entities.get(1).details()).initializerRange().orElseThrow();
		assertEquals("new Object() { int hidden; }", sourceSlice(source, initializer));
		var body = ((MethodDetails) entities.get(2).details()).bodyRange().orElseThrow();
		var bodyText = source.substring(body.startColumn() - 1, body.endColumn());
		assertTrue(bodyText.contains("class Local"));
		assertTrue(bodyText.contains("new Object()"));
		assertTrue(bodyText.contains("() -> {}"));
		assertTrue(bodyText.contains("Object x ="));
	}

	@Test
	void rv09AndRv10IdentityIsSyntacticAndIgnoresOtherMethodMetadata() {
		var inputs = List.of("public int save(List<User> a) { return 1; }",
				"private void save(List< /* comment */ User > b) {}",
				"void save(java.util.List<User> a) {}",
				"void save(String[] a) {}",
				"void save(String... a) {}");
		var entities = inputs.stream().map(method -> parser.parse(new SourceInput("A.java", "class A { " + method + " }")))
				.map(result -> {
					assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
					return result.parsedSource().orElseThrow().entities().get(1);
				}).toList();
		assertEquals(entities.get(0).key(), entities.get(1).key());
		assertNotEquals(entities.get(0).details(), entities.get(1).details());
		assertNotEquals(entities.get(0).modifiers(), entities.get(1).modifiers());
		var firstBody = ((MethodDetails) entities.get(0).details()).bodyRange().orElseThrow();
		var secondBody = ((MethodDetails) entities.get(1).details()).bodyRange().orElseThrow();
		var firstSource = "class A { " + inputs.get(0) + " }";
		var secondSource = "class A { " + inputs.get(1) + " }";
		assertNotEquals(firstSource.substring(firstBody.startColumn() - 1, firstBody.endColumn()),
				secondSource.substring(secondBody.startColumn() - 1, secondBody.endColumn()));
		assertNotEquals(entities.get(0).key(), entities.get(2).key());
		assertNotEquals(entities.get(3).key(), entities.get(4).key());
		var array = assertInstanceOf(MethodDetails.class, entities.get(3).details()).parameters().getFirst().type();
		var varargs = assertInstanceOf(MethodDetails.class, entities.get(4).details()).parameters().getFirst().type();
		assertEquals(new TypeSyntax("String[]", 1, false), array);
		assertEquals(new TypeSyntax("String", 0, true), varargs);
		assertEquals(new SyntacticTypeKey(List.of("String", "[", "]"), false),
				entities.get(3).key().parameterTypes().getFirst());
		assertEquals(new SyntacticTypeKey(List.of("String"), true), entities.get(4).key().parameterTypes().getFirst());
	}

	@Test
	void rv11DefensiveCopiesAndDetailsKindInvariant() {
		var mutable = new ArrayList<>(List.of("public"));
		var key = new SymbolKey(Kind.FIELD, Optional.empty(), List.of("A"), "x", List.of());
		var range = new SourceRange(1, 1, 1, 1);
		var field = new SourceEntity(key, mutable, List.of(), range,
				new FieldDetails(new TypeSyntax("int", 0, false), range, Optional.empty()));
		mutable.clear();
		assertEquals(List.of("public"), field.modifiers());
		assertThrows(UnsupportedOperationException.class, () -> field.modifiers().clear());
		var emptyType = new TypeDetails(List.of(), List.of(), List.of(), List.of(), List.of());
		assertThrows(IllegalArgumentException.class, () -> new SourceEntity(key, List.of(), List.of(), range, emptyType));
		var ownerNames = new ArrayList<>(List.of("A"));
		var tokenList = new ArrayList<>(List.of("List", "<", "User", ">"));
		var typeKey = new SyntacticTypeKey(tokenList, false);
		var types = new ArrayList<>(List.of(typeKey));
		var methodKey = new SymbolKey(Kind.METHOD, Optional.empty(), ownerNames, "m", types);
		var members = new ArrayList<>(List.of(methodKey));
		var details = new TypeDetails(members, List.of(), List.of(), List.of(), List.of());
		tokenList.clear();
		types.clear();
		ownerNames.clear();
		members.clear();
		assertEquals(List.of("List", "<", "User", ">"), typeKey.tokens());
		assertEquals(List.of("A"), methodKey.ownerTypes());
		assertEquals(List.of(typeKey), methodKey.parameterTypes());
		assertEquals(List.of(methodKey), details.members());
		assertThrows(UnsupportedOperationException.class, () -> typeKey.tokens().clear());
		assertThrows(UnsupportedOperationException.class, () -> details.members().clear());
	}

	@Test
	void rv11StructuredMetadataCopiesInputsAndDerivesReadOnlyViews() {
		var range = new SourceRange(1, 1, 1, 6);
		var annotation = new SourceReference("@A(12)", range);
		var argument = new SourceReference("12", new SourceRange(1, 4, 1, 5));
		var argumentInputs = new ArrayList<>(List.of(argument));
		var usage = new AnnotationUsage(annotation, argumentInputs);
		var constant = new EnumConstantDetails(argumentInputs, Optional.empty());
		var usageInputs = new ArrayList<>(List.of(usage));
		var type = new TypeSyntax("int", 0, false);
		var entity = new SourceEntity(new SymbolKey(Kind.ENUM_CONSTANT, Optional.empty(), List.of("E"), "A", List.of()),
				List.of(), usageInputs, range, constant);
		var parameter = new ParameterDetails("value", type, List.of(), usageInputs);
		var module = new ModuleContext("demo", true, usageInputs, List.of(), range);
		var packageContext = new FileContext(usageInputs, Optional.empty());
		argumentInputs.clear();
		usageInputs.clear();

		for (var annotations : List.of(entity.annotationUsages(), parameter.annotationUsages(),
				module.annotationUsages(), packageContext.packageAnnotationUsages())) {
			assertEquals(List.of(usage), annotations);
			assertThrows(UnsupportedOperationException.class, annotations::clear);
		}
		assertEquals(List.of(argument), usage.arguments());
		assertEquals(List.of(argument), constant.argumentReferences());
		assertEquals(List.of("12"), constant.arguments());
		assertEquals(List.of("@A(12)"), entity.annotations());
		assertEquals(entity.annotations(), parameter.annotations());
		assertEquals(List.of(annotation), module.annotations());
		assertEquals(module.annotations(), packageContext.packageAnnotations());
		assertThrows(UnsupportedOperationException.class, usage.arguments()::clear);
		assertThrows(UnsupportedOperationException.class, constant.argumentReferences()::clear);
		assertThrows(UnsupportedOperationException.class, constant.arguments()::clear);
		assertThrows(UnsupportedOperationException.class, entity.annotations()::clear);
	}

	@Test
	void rv01AndRv07FullInventoryForAnnotatedFieldsAndGenericMethod() {
		String source = """
				package app;
				import java.util.List;
				class UserService {
				    @Deprecated private int a = 1, b = 2;
				    <T> List<T> save(List<T> value) throws Exception { return value; }
				}
				""";
		var result = parser.parse(new SourceInput("UserService.java", source));
		assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
		var entities = result.parsedSource().orElseThrow().entities();
		assertEquals(List.of("UserService", "a", "b", "save"), entities.stream().map(e -> e.key().name()).toList());
		var a = entities.get(1);
		var b = entities.get(2);
		assertEquals(List.of("@Deprecated"), a.annotations());
		assertEquals(List.of("private"), b.modifiers());
		assertEquals(a.annotationUsages(), b.annotationUsages());
		assertEquals(a.modifiers(), b.modifiers());
		var aDetails = assertInstanceOf(FieldDetails.class, a.details());
		var bDetails = assertInstanceOf(FieldDetails.class, b.details());
		assertEquals(aDetails.sharedDeclarationRange(), bDetails.sharedDeclarationRange());
		assertEquals("@Deprecated private int a = 1, b = 2;", sourceSlice(source, aDetails.sharedDeclarationRange()));
		assertEquals("1", sourceSlice(source, aDetails.initializerRange().orElseThrow()));
		assertEquals("2", sourceSlice(source, bDetails.initializerRange().orElseThrow()));
		assertNotEquals(a.declarationRange(), b.declarationRange());
		var method = (MethodDetails) entities.get(3).details();
		assertEquals(List.of("T"), method.typeParameters());
		assertEquals(List.of("Exception"), method.thrownTypes());
		assertEquals("value", method.parameters().getFirst().name());
		assertEquals("List<T>", method.returnTypeSpelling());
		assertEquals(Optional.of("app"), result.parsedSource().orElseThrow().packageName());
		assertEquals(new ImportDeclaration("java.util.List", false, false),
				result.parsedSource().orElseThrow().imports().getFirst());
		assertEquals(new SourceRange(3, 1, 6, 1), entities.getFirst().declarationRange());
		assertEquals(List.of("UserService"), a.key().ownerTypes());
		assertTrue(result.diagnostics().isEmpty());
		var fieldRange = a.declarationRange();
		assertEquals(new SourceRange(4, 29, 4, 33), fieldRange);
		assertEquals("a = 1", sourceSlice(source, fieldRange));
		assertEquals("b = 2", sourceSlice(source, b.declarationRange()));
		var methodRange = entities.getLast().declarationRange();
		assertEquals(new SourceRange(5, 5, 5, 70), methodRange);
		assertEquals("<T> List<T> save(List<T> value) throws Exception { return value; }",
				sourceSlice(source, methodRange));
		assertEquals(source.substring(source.indexOf("class UserService"), source.lastIndexOf('}') + 1),
				sourceSlice(source, entities.getFirst().declarationRange()));
	}

	@Test
	void rv14FailsClosedWhenRequiredRangeIsMissing() {
		var source = "class A { int value; }";
		var unit = libraryParser(LanguageLevel.JAVA_21).parse(source).getResult().orElseThrow();
		unit.getType(0).getFieldByName("value").orElseThrow().getVariable(0).setRange(null);
		assertFailure(parser.extractParsed(new SourceInput("A.java", source), unit),
				DiagnosticCode.SOURCE_RANGE_UNAVAILABLE);
	}

	@Test
	void rv05RecordDoesNotInventImplicitMembers() {
		var result = parser.parse(new SourceInput("Pair.java", "record Pair(int x, String y) { Pair {} }"));
		assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
		var entities = result.parsedSource().orElseThrow().entities();
		assertEquals(List.of(Kind.RECORD, Kind.RECORD_COMPONENT, Kind.RECORD_COMPONENT, Kind.CONSTRUCTOR),
				entities.stream().map(entity -> entity.key().kind()).toList());
		assertEquals(2, entities.getLast().key().parameterTypes().size());
	}

	@Test
	void rv04Rv05Rv07AnnotationAndEnumArgumentsHaveExactSourceReferences() {
		String source = """
				package demo;
				@Meta( value = "A  B", count= 2 )
				class A {
				    @Meta("field  argument") int x = 1, y = 2;
				    void f(@Meta("parameter") String name) {}
				}
				enum E { FIRST( 1 +  2, "x y" ), SECOND(3) {} }
				""";
		var result = parser.parse(new SourceInput("A.java", source));
		assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
		var entities = result.parsedSource().orElseThrow().entities();
		var type = entities.stream().filter(e -> e.key().name().equals("A")).findFirst().orElseThrow();
		var usage = type.annotationUsages().getFirst();
		assertEquals("@Meta( value = \"A  B\", count= 2 )", usage.reference().source());
		assertEquals(List.of("value = \"A  B\"", "count= 2"),
				usage.arguments().stream().map(SourceReference::source).toList());
		assertReferenceMatchesSource(source, usage.reference());
		usage.arguments().forEach(reference -> assertReferenceMatchesSource(source, reference));
		for (var name : List.of("x", "y")) {
			var entity = entities.stream().filter(e -> e.key().name().equals(name)).findFirst().orElseThrow();
			var fieldAnnotation = entity.annotationUsages().getFirst();
			assertEquals("\"field  argument\"", fieldAnnotation.arguments().getFirst().source());
			assertReferenceMatchesSource(source, fieldAnnotation.arguments().getFirst());
		}
		var method = entities.stream().filter(e -> e.key().name().equals("f")).findFirst().orElseThrow();
		var parameter = ((MethodDetails) method.details()).parameters().getFirst();
		assertEquals("\"parameter\"", parameter.annotationUsages().getFirst().arguments().getFirst().source());
		var constant = entities.stream().filter(e -> e.key().name().equals("FIRST")).findFirst().orElseThrow();
		var details = (EnumConstantDetails) constant.details();
		assertEquals(List.of("1 +  2", "\"x y\""), details.arguments());
		assertEquals(details.arguments(), details.argumentReferences().stream().map(SourceReference::source).toList());
		details.argumentReferences().forEach(reference -> assertReferenceMatchesSource(source, reference));
		assertThrows(UnsupportedOperationException.class, () -> usage.arguments().clear());
		assertThrows(UnsupportedOperationException.class, () -> details.argumentReferences().clear());
	}

	private static void assertReferenceMatchesSource(String source, SourceReference reference) {
		assertEquals(reference.source(), sourceSlice(source, reference.range()));
	}

	private static String sourceSlice(String source, SourceRange range) {
		var lines = source.split("\n", -1);
		var start = range.startColumn() - 1;
		var end = range.endColumn();
		for (int i = 0; i < range.endLine() - 1; i++) {
			var offset = lines[i].length() + 1;
			end += offset;
			if (i < range.startLine() - 1) {
				start += offset;
			}
		}
		return source.substring(start, end);
	}

	@Test
	void rv10PreservesLiteralWhitespaceWithinTypeAnnotations() {
		String first = "class A { void f(List<@Tag(\"a b\") User> x) {} }";
		String second = "class A { void f(List< @Tag( \"a b\" ) /* c */ User > x) {} }";
		String different = "class A { void f(List<@Tag(\"ab\") User> x) {} }";
		var keys = List.of(first, second, different).stream().map(source -> {
			var result = parser.parse(new SourceInput("A.java", source));
			assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
			return result.parsedSource().orElseThrow().entities().get(1).key();
		}).toList();
		assertEquals(keys.get(0), keys.get(1));
		assertNotEquals(keys.get(0), keys.get(2));
	}

	@Test
	void rv12FieldAndInitializerRangesMatchSource() {
		String source = "class A { int value = 42; }";
		var result = parser.parse(new SourceInput("A.java", source));
		assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
		var entity = result.parsedSource().orElseThrow().entities().get(1);
		var details = (FieldDetails) entity.details();
		var variable = entity.declarationRange();
		var initializer = details.initializerRange().orElseThrow();
		assertEquals("value = 42", source.substring(variable.startColumn() - 1, variable.endColumn()));
		assertEquals("42", source.substring(initializer.startColumn() - 1, initializer.endColumn()));
	}

	@Test
	void rv05EmptyEnumBodyRemainsObservable() {
		var result = parser.parse(new SourceInput("Choice.java", "enum Choice { ONE {}, TWO }"));
		assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
		var one = result.parsedSource().orElseThrow().entities().get(1);
		assertTrue(((EnumConstantDetails) one.details()).bodyRange().isPresent());
	}

	@Test
	void explicitReceiverIsMetadataOutsideIdentity() {
		var result = parser.parse(new SourceInput("A.java", "class A { void f(A this) {} }"));
		var without = parser.parse(new SourceInput("A.java", "class A { void f() {} }"));
		assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
		var method = result.parsedSource().orElseThrow().entities().getLast();
		assertEquals(without.parsedSource().orElseThrow().entities().getLast().key(), method.key());
		assertEquals("A this", ((MethodDetails) method.details()).receiver().orElseThrow().source());
		var constructorResult = parser.parse(new SourceInput("Outer.java",
				"class Outer { class Inner { Inner(Outer Outer.this, int x) {} } }"));
		assertEquals(Status.SUCCESS, constructorResult.status(), () -> constructorResult.diagnostics().toString());
		var constructor = constructorResult.parsedSource().orElseThrow().entities().getLast();
		assertEquals("Outer Outer.this", ((ConstructorDetails) constructor.details()).receiver().orElseThrow().source());
		assertEquals(1, constructor.key().parameterTypes().size());
	}

	@Test
	void f03DeclaredAndReturnTypeSpellingExcludesOtherDeclarations() {
		var result = parser.parse(new SourceInput("A.java",
				"class A { int a, b[]; String values()[] { return null; } "
						+ "String[] mixed(@Ann(1) int input) @Ann(2) [] [] { return null; } } "
						+ "@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) "
						+ "@interface Ann { int value(); }"));
		assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
		var declarations = result.parsedSource().orElseThrow().entities();
		var a = assertInstanceOf(FieldDetails.class, declarations.get(1).details());
		var b = assertInstanceOf(FieldDetails.class, declarations.get(2).details());
		var values = assertInstanceOf(MethodDetails.class, declarations.get(3).details());
		var mixed = assertInstanceOf(MethodDetails.class, declarations.get(4).details());
		assertAll(
				() -> assertEquals(new TypeSyntax("int", 0, false), a.type()),
				() -> assertEquals(new TypeSyntax("int[]", 1, false), b.type()),
				() -> assertEquals("String[]", values.returnTypeSpelling()),
				() -> assertEquals("String[] @Ann(2) [] []", mixed.returnTypeSpelling()));
	}

	@Test
	void declaredArrayDimensionsArePreservedWhenAfterNames() {
		var result = parser.parse(new SourceInput("A.java", "class A { int a[], b; void f(String x[]) {} }"));
		assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
		var entities = result.parsedSource().orElseThrow().entities();
		var field = (FieldDetails) entities.stream().filter(e -> e.key().name().equals("a")).findFirst().orElseThrow().details();
		var method = entities.stream().filter(e -> e.key().name().equals("f")).findFirst().orElseThrow();
		assertEquals(1, field.type().arrayDimensions());
		assertEquals(1, ((MethodDetails) method.details()).parameters().getFirst().type().arrayDimensions());
		assertTrue(method.key().parameterTypes().getFirst().tokens().contains("["));
		assertFalse(method.key().parameterTypes().getFirst().tokens().contains("x"), method.key().toString());
		assertFalse(field.type().rawSpelling().contains("a"));
	}

	@Test
	void parameterNameChangesDoNotAffectPostfixArrayIdentity() {
		var one = parser.parse(new SourceInput("A.java", "class A { void f(String first[]) {} }"));
		var two = parser.parse(new SourceInput("A.java", "class A { void f(String second[]) {} }"));
		assertEquals(Status.SUCCESS, one.status());
		assertEquals(Status.SUCCESS, two.status());
		assertEquals(one.parsedSource().orElseThrow().entities().getLast().key(),
				two.parsedSource().orElseThrow().entities().getLast().key());
	}

	@Test
	void methodKeysIgnoreFormattingBetweenTypeTokens() {
		var spellings = List.of("List<User>", "List< User >", "List</* note */ User>");
		var methods = spellings.stream().map(spelling -> {
			var result = parser.parse(new SourceInput("Example.java",
					"import java.util.List; class Example { void save(" + spelling + " input) {} }"));
			assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
			var parsed = result.parsedSource().orElseThrow();
			assertEquals(List.of(new ImportDeclaration("java.util.List", false, false)), parsed.imports());
			return parsed.entities().get(1);
		}).toList();
		for (int i = 0; i < spellings.size(); i++) {
			var method = methods.get(i);
			assertEquals(methods.getFirst().key(), method.key());
			assertEquals(new SyntacticTypeKey(List.of("List", "<", "User", ">"), false),
					method.key().parameterTypes().getFirst());
			assertEquals(spellings.get(i), assertInstanceOf(MethodDetails.class, method.details())
					.parameters().getFirst().type().rawSpelling());
		}
	}

	@Test
	void fieldsRetainSharedDeclarationAndIndividualInitializerRanges() {
		var result = parser.parse(new SourceInput("Example.java", "class Example { @Deprecated int a = 1, b = 2; }"));
		assertEquals(Status.SUCCESS, result.status(), () -> result.diagnostics().toString());
		var entities = result.parsedSource().orElseThrow().entities();
		assertEquals(3, entities.size());
		var a = entities.get(1);
		var b = entities.get(2);
		assertEquals(Kind.FIELD, a.key().kind());
		assertEquals("a", a.key().name());
		assertEquals("b", b.key().name());
		assertEquals(List.of("@Deprecated"), a.annotations());
		var aDetails = assertInstanceOf(FieldDetails.class, a.details());
		var bDetails = assertInstanceOf(FieldDetails.class, b.details());
		assertEquals(aDetails.sharedDeclarationRange(), bDetails.sharedDeclarationRange());
		assertNotEquals(a.declarationRange(), b.declarationRange());
		assertTrue(aDetails.initializerRange().isPresent());
		assertTrue(bDetails.initializerRange().isPresent());
	}

	@Test
	void rv14RejectsDuplicateKeys() {
		var result = parser.parse(new SourceInput("Example.java", "class Example { void f() {} void f() {} }"));
		assertFailure(result, DiagnosticCode.DUPLICATE_SYMBOL_IDENTITY);
	}

	@Test
	void rv14EnforcesResultAndCoverageInvariants() {
		var source = new ParsedSource("Example.java", Optional.empty(), List.of(),
				new FileContext(List.of(), Optional.empty()), List.of());
		var diagnostic = new ParseDiagnostic(DiagnosticCode.EXTRACTION_FAILURE, Severity.ERROR,
				"Declaration metadata unavailable", Optional.empty());
		var range = new SourceRange(1, 1, 1, 10);
		var verifiedRanges = new ArrayList<>(List.of(range));
		var gap = new CoverageGap(Optional.empty(), "Remaining declarations are unverified");
		var coverage = new Coverage(verifiedRanges, List.of(gap));
		var partial = new JavaParseResult(Status.PARTIAL, Optional.of(source), List.of(diagnostic),
				Optional.of(coverage));
		verifiedRanges.clear();
		assertEquals(List.of(range), partial.coverage().orElseThrow().verifiedRanges());
		assertThrows(UnsupportedOperationException.class, () -> partial.diagnostics().clear());
		assertThrows(IllegalArgumentException.class, () -> new Coverage(List.of(range), List.of()));
		assertThrows(IllegalArgumentException.class, () -> new Coverage(List.of(), List.of(gap)));
		assertThrows(IllegalArgumentException.class, () -> new JavaParseResult(
				Status.PARTIAL, Optional.of(source), List.of(diagnostic), Optional.empty()));
		assertThrows(IllegalArgumentException.class, () -> new JavaParseResult(
				Status.PARTIAL, Optional.empty(), List.of(diagnostic), Optional.of(coverage)));
		assertThrows(IllegalArgumentException.class, () -> new JavaParseResult(
				Status.SUCCESS, Optional.empty(), List.of(), Optional.empty()));
		assertThrows(IllegalArgumentException.class, () -> new JavaParseResult(
				Status.SUCCESS, Optional.of(source), List.of(diagnostic), Optional.empty()));
		for (Status status : List.of(Status.FAILED, Status.OUT_OF_SCOPE)) {
			assertThrows(IllegalArgumentException.class, () -> new JavaParseResult(
					status, Optional.of(source), List.of(diagnostic), Optional.empty()));
		}
	}

	@Test
	void rv15ChecksInputAndPathBeforeContent() {
		assertFailure(parser.parse(null), DiagnosticCode.INVALID_INPUT);
		for (var input : List.of(new SourceInput(null, null), new SourceInput("", "broken"),
				new SourceInput(" \t", null))) {
			var result = parser.parse(input);
			assertFailure(result, DiagnosticCode.INVALID_INPUT);
			assertEquals("A source file path is required", result.diagnostics().getFirst().message());
		}
		var missingContent = parser.parse(new SourceInput("Example.java", null));
		assertFailure(missingContent, DiagnosticCode.INVALID_INPUT);
		assertEquals("Java source content is required", missingContent.diagnostics().getFirst().message());
	}

	@Test
	void rv16ParsesEmptyAndCommentOnlyFiles() {
		for (String source : List.of("", "// comment", "/* comment */")) {
			var result = parser.parse(new SourceInput(" folder/Example.java", source));
			assertEquals(Status.SUCCESS, result.status());
			assertEquals(" folder/Example.java", result.parsedSource().orElseThrow().filePath());
			assertTrue(result.parsedSource().orElseThrow().entities().isEmpty());
		}
	}

	@Test
	void rv17RejectsNonJavaPathBeforeCheckingContent() {
		for (String path : List.of("file.py", "file.md", "file.class", "file.JAVA", "file.java ")) {
			for (String content : new String[]{null, "class {"}) {
				var result = parser.parse(new SourceInput(path, content));
				assertEquals(Status.OUT_OF_SCOPE, result.status());
				assertTrue(result.parsedSource().isEmpty());
				assertEquals(DiagnosticCode.UNSUPPORTED_LANGUAGE, result.diagnostics().getFirst().code());
				assertEquals(Severity.INFO, result.diagnostics().getFirst().severity());
			}
		}
	}

	private static JavaParser libraryParser(LanguageLevel level) {
		return new JavaParser(new ParserConfiguration().setLanguageLevel(level));
	}

	private static void assertValidJava21Preview(String source) throws Exception {
		var compiler = ToolProvider.getSystemJavaCompiler();
		var diagnostics = new DiagnosticCollector<JavaFileObject>();
		var input = new SimpleJavaFileObject(URI.create("string:///Example.java"), JavaFileObject.Kind.SOURCE) {
			@Override
			public CharSequence getCharContent(boolean ignoreEncodingErrors) {
				return source;
			}
		};
		try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
			var task = (JavacTask) compiler.getTask(null, files, diagnostics,
					List.of("--enable-preview", "--source", "21", "-proc:none"), null, List.of(input));
			task.analyze();
			assertTrue(diagnostics.getDiagnostics().stream()
					.noneMatch(diagnostic -> diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR),
					() -> diagnostics.getDiagnostics().toString());
		}
	}

	private static void assertFailure(JavaParseResult result, DiagnosticCode code) {
		assertEquals(Status.FAILED, result.status(), () -> result.diagnostics().toString());
		assertTrue(result.parsedSource().isEmpty());
		assertTrue(result.coverage().isEmpty());
		assertTrue(result.diagnostics().stream()
				.anyMatch(diagnostic -> diagnostic.code() == code && diagnostic.severity() == Severity.ERROR),
				() -> result.diagnostics().toString());
	}
}
