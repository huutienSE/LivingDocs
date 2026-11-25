package com.livingdocs.analysis.ast;

import static com.livingdocs.analysis.ast.JavaParseResult.*;
import static com.livingdocs.analysis.ast.JavaStructuralRepresentation.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import org.junit.jupiter.api.Test;

class JavaStructuralRepresentationTest {

	private final JavaSourceParser parser = new JavaSourceParser();

	@Test
	void singleParseBridgeReusesValidatedParseResultAndFailsClosedOnInvalidOrOutOfScopeInputs() {
		var validInput = new SourceInput("src/Service.java", """
				package demo;
				import java.util.List;
				public class Service {
				    public int compute(int x) { return x + 1; }
				}
				""");
		var outcome = JavaStructuralRepresentation.parse(parser, validInput);
		assertEquals(Status.SUCCESS, outcome.parseResult().status());
		assertEquals(parser.parse(validInput), outcome.parseResult());
		var rep = outcome.representation().orElseThrow();
		assertEquals(outcome.parseResult().parsedSource().orElseThrow(), rep.parsedSource());
		assertEquals(NORMALIZATION_VERSION, rep.normalizationVersion());
		assertEquals(PARSER_VERSION, rep.parserVersion());
		assertEquals(LANGUAGE_POLICY, rep.languagePolicy());

		var nonJava = JavaStructuralRepresentation.parse(parser, new SourceInput("README.md", null));
		assertEquals(Status.OUT_OF_SCOPE, nonJava.parseResult().status());
		assertTrue(nonJava.representation().isEmpty());

		var invalid = JavaStructuralRepresentation.parse(parser, new SourceInput("Broken.java", null));
		assertEquals(Status.FAILED, invalid.parseResult().status());
		assertEquals(DiagnosticCode.INVALID_INPUT, invalid.parseResult().diagnostics().getFirst().code());
		assertTrue(invalid.representation().isEmpty());

		var syntaxError = JavaStructuralRepresentation.parse(parser,
				new SourceInput("Broken.java", "class Broken { int x = ; }"));
		assertEquals(Status.FAILED, syntaxError.parseResult().status());
		assertEquals(DiagnosticCode.SYNTAX_ERROR, syntaxError.parseResult().diagnostics().getFirst().code());
		assertTrue(syntaxError.representation().isEmpty());
	}

	@Test
	void whitespaceAndCommentChangesKeepStructuralFingerprintsEqualWhileRecordingRawAndCommentFacts() {
		var first = JavaStructuralRepresentation.parse(parser, new SourceInput("A.java", """
				package demo;
				public class A {
				    int add(int a, int b) {
				        return a + b;
				    }
				}
				""")).representation().orElseThrow();

		var whitespaceChanged = JavaStructuralRepresentation.parse(parser, new SourceInput("B.java",
				"package   demo;\npublic\tclass A {\n\tint add( int a , int b ) { return a + b ; }\n}\n"))
				.representation().orElseThrow();

		var commentAdded = JavaStructuralRepresentation.parse(parser, new SourceInput("C.java", """
				package demo;
				/** Service docs */
				public class A {
				    // inline note
				    int add(int /* param */ a, int b) {
				        return a + b;
				    }
				}
				""")).representation().orElseThrow();

		assertTrue(first.wholeFileFingerprint().matchesContent(whitespaceChanged.wholeFileFingerprint()));
		assertTrue(first.wholeFileFingerprint().matchesContent(commentAdded.wholeFileFingerprint()));
		assertEquals(first.entities().getLast().facets(), whitespaceChanged.entities().getLast().facets());
		assertEquals(first.entities().getLast().facets(), commentAdded.entities().getLast().facets());

		assertFalse(first.rawFacts().hasSameRawContent(whitespaceChanged.rawFacts()));
		assertFalse(first.rawFacts().hasSameWhitespace(whitespaceChanged.rawFacts()));
		assertTrue(first.rawFacts().hasSameComments(whitespaceChanged.rawFacts()));
		assertFalse(first.rawFacts().hasSameComments(commentAdded.rawFacts()));
		assertEquals(List.of(CommentKind.JAVADOC, CommentKind.LINE, CommentKind.BLOCK),
				commentAdded.rawFacts().comments().stream().map(CommentEntry::kind).toList());
	}

	@Test
	void bodyOnlyChangeModifiesCallableBodyWithoutChangingTypeHeaderOrMemberOrder() {
		var before = JavaStructuralRepresentation.parse(parser, new SourceInput("Calc.java", """
				@SuppressWarnings({"unchecked", "rawtypes"})
				public class Calc {
				    public int compute(int a, int b) { return a + b; }
				}
				""")).representation().orElseThrow();

		var after = JavaStructuralRepresentation.parse(parser, new SourceInput("Calc.java", """
				@SuppressWarnings({"unchecked", "rawtypes"})
				public class Calc {
				    public int compute(int a, int b) {
				        class Helper { int apply() { return b - a; } }
				        return new Helper().apply();
				    }
				}
				""")).representation().orElseThrow();

		var beforeType = before.entities().getFirst();
		var afterType = after.entities().getFirst();
		assertTrue(beforeType.declarationFacet().matchesContent(afterType.declarationFacet()));
		assertTrue(beforeType.memberOrderFacet().orElseThrow()
				.matchesContent(afterType.memberOrderFacet().orElseThrow()));
		assertTrue(before.typeLayouts().get(beforeType.key()).orderFacet()
				.matchesContent(after.typeLayouts().get(afterType.key()).orderFacet()));

		var beforeMethod = before.entities().getLast();
		var afterMethod = after.entities().getLast();
		assertEquals(beforeMethod.key(), afterMethod.key());
		assertTrue(beforeMethod.declarationFacet().matchesContent(afterMethod.declarationFacet()));
		assertFalse(beforeMethod.bodyFacet().orElseThrow().matchesContent(afterMethod.bodyFacet().orElseThrow()));
	}

	@Test
	void separatesFieldDeclarationsInitializersSharedReferencesFieldGroupsAndInitializerBlockOrder() {
		String sourceA = """
				class Sample {
				    @Deprecated private int a = 1, b[] = {2, 3};
				    static { System.out.println("boot"); }
				    { a = 10; }
				}
				""";
		String sourceReordered = """
				class Sample {
				    { a = 10; }
				    @Deprecated private int a = 1, b[] = {2, 3};
				    static { System.out.println("boot"); }
				}
				""";
		var first = JavaStructuralRepresentation.parse(parser, new SourceInput("Sample.java", sourceA))
				.representation().orElseThrow();
		var reordered = JavaStructuralRepresentation.parse(parser, new SourceInput("Sample.java", sourceReordered))
				.representation().orElseThrow();

		var fieldA = first.entities().get(1);
		var fieldB = first.entities().get(2);
		assertEquals(List.of("@", "Deprecated", "private", "int", "a"),
				fieldA.declarationFacet().canonicalTokens());
		assertEquals(List.of("1"), fieldA.initializerFacet().orElseThrow().canonicalTokens());
		assertEquals(List.of("@", "Deprecated", "private", "int", "b", "[", "]"),
				fieldB.declarationFacet().canonicalTokens());
		assertEquals(List.of("{", "2", ",", "3", "}"),
				fieldB.initializerFacet().orElseThrow().canonicalTokens());
		assertEquals("1", fieldA.initializerRef().orElseThrow().reference().orElseThrow().source());
		assertEquals("{2, 3}", fieldB.initializerRef().orElseThrow().reference().orElseThrow().source());

		// F03: Multi-field declaration facet carries separate grounded [sharedPrefixRef, declaratorHeaderRef],
		// never fabricating a contiguous range across `a = 1,` for `b`.
		var bDeclRefs = fieldB.facetReferences().get(FacetKind.DECLARATION);
		assertEquals(2, bDeclRefs.size());
		assertEquals("@Deprecated private int", bDeclRefs.get(0).reference().orElseThrow().source());
		assertEquals("b[]", bDeclRefs.get(1).reference().orElseThrow().source());
		assertReferenceMatchesSource(sourceA, bDeclRefs.get(0).reference().orElseThrow());
		assertReferenceMatchesSource(sourceA, bDeclRefs.get(1).reference().orElseThrow());
		assertEquals("@Deprecated private int a = 1, b[] = {2, 3};",
				fieldB.sharedDeclarationRef().orElseThrow().reference().orElseThrow().source());
		assertReferenceMatchesSource(sourceA,
				fieldB.sharedDeclarationRef().orElseThrow().reference().orElseThrow());

		// F01: FieldDeclarationGroup captures the grouped [a, b] statement explicitly.
		assertEquals(1, first.fieldGroups().size());
		assertEquals(List.of(fieldA.key(), fieldB.key()), first.fieldGroups().getFirst().fieldKeys());
		assertReferenceMatchesSource(sourceA,
				first.fieldGroups().getFirst().provenance().reference().orElseThrow());

		assertEquals(2, first.initializerBlocks().size());
		assertTrue(first.initializerBlocks().get(0).isStatic());
		assertFalse(first.initializerBlocks().get(1).isStatic());
		assertReferenceMatchesSource(sourceA,
				first.initializerBlocks().get(0).provenance().reference().orElseThrow());

		var typeKey = first.entities().getFirst().key();
		assertNotEquals(first.typeLayouts().get(typeKey).elements(),
				reordered.typeLayouts().get(typeKey).elements());
		assertFalse(first.typeLayouts().get(typeKey).orderFacet()
				.matchesContent(reordered.typeLayouts().get(typeKey).orderFacet()));
	}

	@Test
	void capturesEnumsRecordsCompactConstructorsAnnotationsFileContextAndResidualsWithProvenance() {
		String allKindsSource = """
				package demo;
				;
				import java.io.Serializable;
				@interface Meta {
				    int count() default 1 + 2;
				    String label();
				}
				record Pair(int left, String right) implements Serializable {
				    public Pair { if (left < 0) throw new IllegalArgumentException(); }
				}
				enum Mode {
				    FAST(1) { void run() {} },
				    SAFE(2);
				    ;
				    Mode(int code) {}
				}
				""";
		var rep = JavaStructuralRepresentation.parse(parser, new SourceInput("AllKinds.java", allKindsSource))
				.representation().orElseThrow();

		assertReferenceMatchesSource(allKindsSource,
				rep.fileContext().packageDecl().orElseThrow().provenance().reference().orElseThrow());
		assertReferenceMatchesSource(allKindsSource,
				rep.fileContext().imports().getFirst().provenance().reference().orElseThrow());

		var countMember = rep.entities().stream()
				.filter(e -> e.key().kind() == Kind.ANNOTATION_MEMBER && e.key().name().equals("count"))
				.findFirst().orElseThrow();
		var labelMember = rep.entities().stream()
				.filter(e -> e.key().kind() == Kind.ANNOTATION_MEMBER && e.key().name().equals("label"))
				.findFirst().orElseThrow();
		assertEquals("1 + 2", countMember.annotationDefaultRef().orElseThrow().reference().orElseThrow().source());
		assertReferenceMatchesSource(allKindsSource,
				countMember.annotationDefaultRef().orElseThrow().reference().orElseThrow());
		assertTrue(labelMember.annotationDefaultRef().isEmpty());

		// F03: Compact constructor DECLARATION facet carries separate refs to compact header and record components.
		var compactCtor = rep.entities().stream()
				.filter(e -> e.key().kind() == Kind.CONSTRUCTOR && e.key().name().equals("Pair"))
				.findFirst().orElseThrow();
		var ctorDeclRefs = compactCtor.facetReferences().get(FacetKind.DECLARATION);
		assertEquals(3, ctorDeclRefs.size());
		assertEquals("public Pair", ctorDeclRefs.get(0).reference().orElseThrow().source());
		assertEquals("int left", ctorDeclRefs.get(1).reference().orElseThrow().source());
		assertEquals("String right", ctorDeclRefs.get(2).reference().orElseThrow().source());
		for (var ref : ctorDeclRefs) {
			assertReferenceMatchesSource(allKindsSource, ref.reference().orElseThrow());
		}

		var fastConstant = rep.entities().stream()
				.filter(e -> e.key().kind() == Kind.ENUM_CONSTANT && e.key().name().equals("FAST"))
				.findFirst().orElseThrow();
		var safeConstant = rep.entities().stream()
				.filter(e -> e.key().kind() == Kind.ENUM_CONSTANT && e.key().name().equals("SAFE"))
				.findFirst().orElseThrow();
		assertEquals(List.of("FAST", "(", "1", ")"), fastConstant.declarationFacet().canonicalTokens());
		assertTrue(fastConstant.bodyFacet().isPresent());
		assertEquals("{ void run() {} }", fastConstant.bodyRef().orElseThrow().reference().orElseThrow().source());
		assertTrue(safeConstant.bodyFacet().isEmpty());

		assertEquals(2, rep.residualRegions().size());
		var enumResidual = rep.residualRegions().get(0);
		assertEquals("Mode", enumResidual.enclosingType().orElseThrow().name());
		assertEquals(List.of(";"), enumResidual.facet().canonicalTokens());
		assertReferenceMatchesSource(allKindsSource, enumResidual.provenance().reference().orElseThrow());

		var topLevelResidual = rep.residualRegions().get(1);
		assertTrue(topLevelResidual.enclosingType().isEmpty());
		assertEquals(List.of(";"), topLevelResidual.facet().canonicalTokens());
		assertReferenceMatchesSource(allKindsSource, topLevelResidual.provenance().reference().orElseThrow());
		assertInstanceOf(ScopeElement.PackageElement.class, rep.topLevelLayout().elements().get(0));
		assertInstanceOf(ScopeElement.ResidualElement.class, rep.topLevelLayout().elements().get(1));
		assertInstanceOf(ScopeElement.ImportElement.class, rep.topLevelLayout().elements().get(2));

		String moduleSource = "@Deprecated open module demo.mod { requires java.base; exports demo.pkg; }";
		var moduleRep = JavaStructuralRepresentation.parse(parser, new SourceInput("module-info.java", moduleSource))
				.representation().orElseThrow();
		var mod = moduleRep.fileContext().module().orElseThrow();
		assertEquals("demo.mod", mod.name());
		assertTrue(mod.open());
		assertEquals(2, mod.directives().size());
		assertReferenceMatchesSource(moduleSource, mod.provenance().reference().orElseThrow());
		mod.directives().forEach(dir -> assertReferenceMatchesSource(moduleSource,
				dir.provenance().reference().orElseThrow()));
		assertTrue(moduleRep.residualRegions().isEmpty());
	}

	@Test
	void retainsExplicitReasonWhenOptionalContextOrInitializerRangeCannotBeEstablished() {
		String source = "package demo;\nimport java.util.List;\nclass A { static { int x = 1; } }";
		var unit = new JavaParser(new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21))
				.parse(source).getResult().orElseThrow();
		unit.getPackageDeclaration().orElseThrow().setRange(null);
		unit.getImport(0).setRange(null);
		unit.getType(0).getMembers().stream()
				.filter(com.github.javaparser.ast.body.InitializerDeclaration.class::isInstance)
				.findFirst().orElseThrow().setRange(null);

		var outcome = JavaStructuralRepresentation.extractAndProject(parser, new SourceInput("A.java", source), unit);
		assertEquals(Status.SUCCESS, outcome.parseResult().status());
		var rep = outcome.representation().orElseThrow();
		assertTrue(rep.fileContext().packageDecl().orElseThrow().provenance().reference().isEmpty());
		assertTrue(rep.fileContext().packageDecl().orElseThrow().provenance().unavailableReason().isPresent());
		assertTrue(rep.fileContext().imports().getFirst().provenance().unavailableReason().isPresent());
		assertTrue(rep.initializerBlocks().getFirst().provenance().unavailableReason().isPresent());

		var brokenEntityUnit = new JavaParser(new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21))
				.parse("class A { int value; }").getResult().orElseThrow();
		brokenEntityUnit.getType(0).getFieldByName("value").orElseThrow().getVariable(0).setRange(null);
		var failed = JavaStructuralRepresentation.extractAndProject(parser,
				new SourceInput("A.java", "class A { int value; }"), brokenEntityUnit);
		assertEquals(Status.FAILED, failed.parseResult().status());
		assertEquals(DiagnosticCode.SOURCE_RANGE_UNAVAILABLE, failed.parseResult().diagnostics().getFirst().code());
		assertTrue(failed.representation().isEmpty());
	}

	@Test
	void fingerprintEnforcesVersionCompatibilityLengthPrefixesEqualDigestFallbackAndImmutability() {
		var fp1 = Fingerprint.compute("FACET:BODY:METHOD", List.of("a", "bc"));
		var fp2 = Fingerprint.compute("FACET:BODY:METHOD", List.of("ab", "c"));
		var fpDifferentDomain = Fingerprint.compute("FACET:INITIALIZER:FIELD", List.of("a", "bc"));
		var fpDifferentVersion = Fingerprint.computeWithVersion("m2-ast-v2", "FACET:BODY:METHOD", List.of("a", "bc"));

		assertNotEquals(fp1.sha256(), fp2.sha256());
		assertFalse(fp1.matchesContent(fp2));
		assertNotEquals(fp1.sha256(), fpDifferentDomain.sha256());
		assertFalse(fp1.matchesContent(fpDifferentDomain));
		assertFalse(fp1.isCompatibleWith(fpDifferentVersion));
		assertThrows(IllegalArgumentException.class, () -> fp1.matchesContent(fpDifferentVersion));

		// F08 / RV-16: Controlled equal-digest fixture verifies canonical token sequence fallback.
		String sharedDigest = fp1.sha256();
		var equalDigestLeft = new Fingerprint(NORMALIZATION_VERSION, "FACET:BODY:METHOD",
				sharedDigest, List.of("return", "1", ";"));
		var equalDigestRight = new Fingerprint(NORMALIZATION_VERSION, "FACET:BODY:METHOD",
				sharedDigest, List.of("return", "2", ";"));
		assertEquals(equalDigestLeft.sha256(), equalDigestRight.sha256());
		assertFalse(equalDigestLeft.matchesContent(equalDigestRight));

		// Different paths and shifted line/column locations do not change content fingerprints.
		var left = JavaStructuralRepresentation.parse(parser,
				new SourceInput("dir1/Service.java", "class Service { int run() { return 42; } }"))
				.representation().orElseThrow();
		var right = JavaStructuralRepresentation.parse(parser,
				new SourceInput("dir2/Service.java", "\n\n\nclass Service {\n    int run() {\n        return 42;\n    }\n}\n"))
				.representation().orElseThrow();
		assertEquals(left.wholeFileFingerprint(), right.wholeFileFingerprint());
		assertEquals(left.entities().getLast().facets(), right.entities().getLast().facets());

		// Defensive copy invariants.
		var mutableTokens = new ArrayList<>(List.of("int", "x"));
		var copiedFp = Fingerprint.compute("FACET:DECLARATION:FIELD", mutableTokens);
		mutableTokens.clear();
		assertEquals(List.of("int", "x"), copiedFp.canonicalTokens());
		assertThrows(UnsupportedOperationException.class, () -> copiedFp.canonicalTokens().clear());

		var facetMap = new EnumMap<FacetKind, Fingerprint>(FacetKind.class);
		facetMap.put(FacetKind.DECLARATION, copiedFp);
		var ref = ProvenanceRef.of(new SourceReference("class Service {}", new SourceRange(1, 1, 1, 16)));
		var mutableRefs = new ArrayList<>(List.of(ref));
		var facetRefs = new EnumMap<FacetKind, List<ProvenanceRef>>(FacetKind.class);
		facetRefs.put(FacetKind.DECLARATION, mutableRefs);
		var classEntity = left.entities().getFirst().entity();
		var projected = new EntityProjection(classEntity, facetMap, facetRefs, ref, ref,
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
		facetMap.clear();
		mutableRefs.clear();
		facetRefs.clear();
		assertEquals(Map.of(FacetKind.DECLARATION, copiedFp), projected.facets());
		assertEquals(List.of(ref), projected.facetReferences().get(FacetKind.DECLARATION));
		assertThrows(UnsupportedOperationException.class, () -> projected.facets().clear());
		assertThrows(UnsupportedOperationException.class, () -> projected.facetReferences().clear());
		assertThrows(UnsupportedOperationException.class,
				() -> projected.facetReferences().get(FacetKind.DECLARATION).clear());
	}

	private static void assertReferenceMatchesSource(String source, SourceReference reference) {
		var lines = source.split("\n", -1);
		var range = reference.range();
		int start = range.startColumn() - 1;
		int end = range.endColumn();
		for (int i = 0; i < range.endLine() - 1; i++) {
			int offset = lines[i].length() + 1;
			end += offset;
			if (i < range.startLine() - 1) {
				start += offset;
			}
		}
		assertEquals(reference.source(), source.substring(start, end));
	}
}
