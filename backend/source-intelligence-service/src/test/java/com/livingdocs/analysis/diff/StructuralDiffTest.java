package com.livingdocs.analysis.diff;

import static com.livingdocs.analysis.diff.StructuralDiff.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import com.livingdocs.analysis.ast.JavaParseResult;
import com.livingdocs.analysis.ast.JavaParseResult.Coverage;
import com.livingdocs.analysis.ast.JavaParseResult.CoverageGap;
import com.livingdocs.analysis.ast.JavaParseResult.FileContext;
import com.livingdocs.analysis.ast.JavaParseResult.Kind;
import com.livingdocs.analysis.ast.JavaParseResult.ParseDiagnostic;
import com.livingdocs.analysis.ast.JavaParseResult.ParsedSource;
import com.livingdocs.analysis.ast.JavaParseResult.Severity;
import com.livingdocs.analysis.ast.JavaParseResult.SourceRange;
import com.livingdocs.analysis.ast.JavaParseResult.Status;
import com.livingdocs.analysis.ast.JavaSourceParser;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.EntityProjection;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.FacetKind;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.Fingerprint;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.ParseOutcome;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.ProvenanceRef;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.ResidualRegion;
import com.livingdocs.analysis.ast.SourceInput;
import com.livingdocs.analysis.diff.StructuralDiffInput.*;

class StructuralDiffTest {

	private final StructuralDiff diff = new StructuralDiff();

	@Test
	void retainsPinnedSnapshotsAndDistinguishesAbsentFromUnavailable() {
		var before = new SnapshotRef("repo", "before-sha");
		var after = new SnapshotRef("repo", "after-sha");
		var input = new StructuralDiffInput("repo", Intent.REVISION_COMPARISON,
				Optional.of(new Absent(before, "A.java", "verified")),
				new Present(after, new SourceInput("A.java", "class A {}")), Optional.empty());
		assertInstanceOf(Absent.class, input.before().orElseThrow());
		assertEquals("before-sha", input.before().orElseThrow().snapshot().sha());
		assertEquals("A.java", input.after().path());
		assertInstanceOf(Unavailable.class, new Unavailable(before, "A.java", "timeout"));
	}

	@Test
	void rv01IdenticalSourcesAcrossSameOrDifferentShasRemainUnchangedWithExactProvenance() {
		String source = """
				package demo;
				public class UserService {
				    public String findName(long id) { return "u-" + id; }
				}
				""";
		var shaA = new SnapshotRef("repo-1", "sha-aaa");
		var shaB = new SnapshotRef("repo-1", "sha-bbb");

		var differentShaResult = diff.compare(revision("repo-1",
				new Present(shaA, new SourceInput("src/UserService.java", source)),
				new Present(shaB, new SourceInput("src/UserService.java", source))));
		assertEquals(ProcessingStatus.COMPLETE, differentShaResult.status());
		assertEquals(StructuralEquality.EQUAL, differentShaResult.fileComparison().structuralEquality());
		assertFalse(differentShaResult.fileComparison().rawOnlyChange());
		assertTrue(differentShaResult.fileComparison().rawChanges().isEmpty());
		assertTrue(differentShaResult.fileComparison().contextChanges().isEmpty());
		assertEquals(new SnapshotProvenance(shaA, "src/UserService.java", SidePresence.PRESENT,
				Optional.empty(), Optional.empty()), differentShaResult.beforeSnapshot().orElseThrow());
		assertEquals(new SnapshotProvenance(shaB, "src/UserService.java", SidePresence.PRESENT,
				Optional.empty(), Optional.empty()), differentShaResult.afterSnapshot().orElseThrow());
		assertEquals(2, differentShaResult.entityFacts().size());
		for (var fact : differentShaResult.entityFacts()) {
			assertEquals(ChangeKind.UNCHANGED, fact.change());
			assertEquals(MatchBasis.EXACT_SYMBOL_KEY, fact.matchBasis());
			assertTrue(fact.changedFacets().isEmpty());
			assertEquals("sha-aaa", fact.before().orElseThrow().snapshot().sha());
			assertEquals("sha-bbb", fact.after().orElseThrow().snapshot().sha());
			assertEquals("src/UserService.java", fact.before().orElseThrow().path());
			assertEquals("src/UserService.java", fact.after().orElseThrow().path());
		}

		// F04: Same repository + SHA + path with identical PRESENT content remains valid.
		var sameShaResult = diff.compare(revision("repo-1",
				new Present(shaA, new SourceInput("src/UserService.java", source)),
				new Present(shaA, new SourceInput("src/UserService.java", source))));
		assertEquals(ProcessingStatus.COMPLETE, sameShaResult.status());
		assertEquals(StructuralEquality.EQUAL, sameShaResult.fileComparison().structuralEquality());
		assertFalse(sameShaResult.fileComparison().rawOnlyChange());
	}

	@Test
	void rv02WhitespaceCommentAndJavadocOnlyChangesPreserveStructuralEqualityAndDistinguishRawFacts() {
		var before = new SnapshotRef("repo", "sha-1");
		var after = new SnapshotRef("repo", "sha-2");
		String base = "package demo;\nclass Calc {\n    int sum(int a, int b) { return a + b; }\n}\n";
		String reindented = "package   demo;\n\nclass Calc {\n\t\tint sum( int a , int b ) {\n\t\t\treturn a + b;\n\t\t}\n}\n";

		var wsDiff = diff.compare(revision("repo",
				new Present(before, new SourceInput("Calc.java", base)),
				new Present(after, new SourceInput("Calc.java", reindented))));
		assertEquals(ProcessingStatus.COMPLETE, wsDiff.status());
		assertEquals(StructuralEquality.EQUAL, wsDiff.fileComparison().structuralEquality());
		assertTrue(wsDiff.fileComparison().rawOnlyChange());
		assertEquals(Set.of(RawChangeKind.WHITESPACE), wsDiff.fileComparison().rawChanges());
		assertTrue(wsDiff.entityFacts().stream().allMatch(f -> f.change() == ChangeKind.UNCHANGED));

		// Pure Javadoc-only edit without changing surrounding whitespace.
		String javadocV1 = "class Calc { /** Adds v1 */ int sum(int a, int b) { return a + b; } }";
		String javadocV2 = "class Calc { /** Adds v2 */ int sum(int a, int b) { return a + b; } }";
		var javadocOnly = diff.compare(revision("repo",
				new Present(before, new SourceInput("Calc.java", javadocV1)),
				new Present(after, new SourceInput("Calc.java", javadocV2))));
		assertEquals(ProcessingStatus.COMPLETE, javadocOnly.status());
		assertEquals(StructuralEquality.EQUAL, javadocOnly.fileComparison().structuralEquality());
		assertTrue(javadocOnly.fileComparison().rawOnlyChange());
		assertEquals(Set.of(RawChangeKind.COMMENT), javadocOnly.fileComparison().rawChanges());

		// Moved Javadoc comment across methods without changing text is also detected as COMMENT change.
		String movedDocBefore = "class Calc { /** Doc */ void a() {} void b() {} }";
		String movedDocAfter = "class Calc { void a() {} /** Doc */ void b() {} }";
		var movedDoc = diff.compare(revision("repo",
				new Present(before, new SourceInput("Calc.java", movedDocBefore)),
				new Present(after, new SourceInput("Calc.java", movedDocAfter))));
		assertEquals(ProcessingStatus.COMPLETE, movedDoc.status());
		assertTrue(movedDoc.fileComparison().rawOnlyChange());
		assertTrue(movedDoc.fileComparison().rawChanges().contains(RawChangeKind.COMMENT));
	}

	@Test
	void rv03AndRv04VerifyBodyOnlyAndEachIsolatedDeclarationMetadataChangeWithFacetGrounding() {
		var before = new SnapshotRef("repo", "sha-1");
		var after = new SnapshotRef("repo", "sha-2");

		// RV-03: body-only change keeps enclosing class UNCHANGED and marks method MODIFIED on BODY only.
		var bodyOnly = diff.compare(revision("repo",
				new Present(before, new SourceInput("OrderService.java", """
						public class OrderService {
						    public int fee(int distance) { return distance * 5; }
						}
						""")),
				new Present(after, new SourceInput("OrderService.java", """
						public class OrderService {
						    public int fee(int distance) { return distance * 10; }
						}
						"""))));
		assertEquals(ProcessingStatus.COMPLETE, bodyOnly.status());
		assertEquals(ChangeKind.UNCHANGED, bodyOnly.entityFacts().get(0).change());
		var methodFact = bodyOnly.entityFacts().get(1);
		assertEquals(ChangeKind.MODIFIED, methodFact.change());
		assertEquals(Set.of(FacetKind.BODY), methodFact.changedFacets());
		assertEquals("{ return distance * 5; }",
				methodFact.before().orElseThrow().facetReferences().get(FacetKind.BODY)
						.getFirst().reference().orElseThrow().source());
		assertEquals("{ return distance * 10; }",
				methodFact.after().orElseThrow().facetReferences().get(FacetKind.BODY)
						.getFirst().reference().orElseThrow().source());

		// RV-04 + F08: Isolated declaration metadata changes (modifier, return type, parameter name,
		// throws, type parameter, annotation argument) each trigger DECLARATION only with grounded header ref.
		String baseMethod = "@Tag(\"v1\") public <T> int run(String userId) throws java.io.IOException { return 1; }";
		List<String> isolatedVariants = List.of(
				"@Tag(\"v1\") protected <T> int run(String userId) throws java.io.IOException { return 1; }",
				"@Tag(\"v1\") public <T> long run(String userId) throws java.io.IOException { return 1; }",
				"@Tag(\"v1\") public <T> int run(String accountId) throws java.io.IOException { return 1; }",
				"@Tag(\"v1\") public <T> int run(String userId) throws Exception { return 1; }",
				"@Tag(\"v1\") public <U> int run(String userId) throws java.io.IOException { return 1; }",
				"@Tag(\"v2\") public <T> int run(String userId) throws java.io.IOException { return 1; }");
		for (String variant : isolatedVariants) {
			var res = diff.compare(revision("repo",
					new Present(before, new SourceInput("Api.java", "class Api { " + baseMethod + " }")),
					new Present(after, new SourceInput("Api.java", "class Api { " + variant + " }"))));
			assertEquals(ProcessingStatus.COMPLETE, res.status());
			var runFact = findFact(res, Kind.METHOD, "run");
			assertEquals(ChangeKind.MODIFIED, runFact.change(), variant);
			assertEquals(Set.of(FacetKind.DECLARATION), runFact.changedFacets(), variant);
			String headerEvidence = runFact.after().orElseThrow()
					.facetReferences().get(FacetKind.DECLARATION).getFirst().reference().orElseThrow().source();
			assertEquals(variant.substring(0, variant.indexOf(" {")), headerEvidence);
		}

		// Isolated annotation default change covers both signature and default value without synthetic range.
		String annBefore = "@interface Config { int retries() default 3; }";
		String annAfter = "@interface Config { int retries() default 5; }";
		var defaultChange = diff.compare(revision("repo",
				new Present(before, new SourceInput("Config.java", annBefore)),
				new Present(after, new SourceInput("Config.java", annAfter))));
		var retriesFact = findFact(defaultChange, Kind.ANNOTATION_MEMBER, "retries");
		assertEquals(ChangeKind.MODIFIED, retriesFact.change());
		assertEquals(Set.of(FacetKind.DECLARATION), retriesFact.changedFacets());
		assertEquals("3", retriesFact.before().orElseThrow().annotationDefaultRef().orElseThrow()
				.reference().orElseThrow().source());
		assertEquals("5", retriesFact.after().orElseThrow().annotationDefaultRef().orElseThrow()
				.reference().orElseThrow().source());
		var afterDefaultFacetRefs = retriesFact.after().orElseThrow().facetReferences().get(FacetKind.DECLARATION);
		assertEquals(2, afterDefaultFacetRefs.size());
		assertEquals("int retries()", afterDefaultFacetRefs.get(0).reference().orElseThrow().source());
		assertEquals("5", afterDefaultFacetRefs.get(1).reference().orElseThrow().source());
	}

	@Test
	void f03VerifiesSharedFieldPrefixGroundingInitializerIsolationAndCompactConstructorComponentRefs() {
		var before = new SnapshotRef("repo", "sha-1");
		var after = new SnapshotRef("repo", "sha-2");

		// 1. Shared modifier/type change affects both fields and grounds the shared prefix without spanning across `a = 1,`.
		String sharedBefore = "class Fields { @Deprecated private int a = 1, b = 2; }";
		String sharedAfter = "class Fields { @Deprecated protected long a = 1, b = 2; }";
		var sharedChange = diff.compare(revision("repo",
				new Present(before, new SourceInput("Fields.java", sharedBefore)),
				new Present(after, new SourceInput("Fields.java", sharedAfter))));
		assertEquals(ProcessingStatus.COMPLETE, sharedChange.status());
		for (String fieldName : List.of("a", "b")) {
			var fact = findFact(sharedChange, Kind.FIELD, fieldName);
			assertEquals(ChangeKind.MODIFIED, fact.change());
			assertEquals(Set.of(FacetKind.DECLARATION), fact.changedFacets());
			var declRefs = fact.after().orElseThrow().facetReferences().get(FacetKind.DECLARATION);
			assertEquals(2, declRefs.size());
			assertEquals("@Deprecated protected long", declRefs.get(0).reference().orElseThrow().source());
			assertEquals(fieldName, declRefs.get(1).reference().orElseThrow().source());
			assertReferenceMatchesSource(sharedAfter, declRefs.get(0).reference().orElseThrow());
			assertReferenceMatchesSource(sharedAfter, declRefs.get(1).reference().orElseThrow());
		}

		// 2. Changing initializer of `a` does NOT modify `b` or its initializer.
		String initIsolatedAfter = "class Fields { @Deprecated private int a = 99, b = 2; }";
		var initIsolated = diff.compare(revision("repo",
				new Present(before, new SourceInput("Fields.java", sharedBefore)),
				new Present(after, new SourceInput("Fields.java", initIsolatedAfter))));
		assertEquals(ProcessingStatus.COMPLETE, initIsolated.status());
		var aFact = findFact(initIsolated, Kind.FIELD, "a");
		var bFact = findFact(initIsolated, Kind.FIELD, "b");
		assertEquals(ChangeKind.MODIFIED, aFact.change());
		assertEquals(Set.of(FacetKind.INITIALIZER), aFact.changedFacets());
		assertEquals("99", aFact.after().orElseThrow().facetReferences().get(FacetKind.INITIALIZER)
				.getFirst().reference().orElseThrow().source());
		assertEquals(ChangeKind.UNCHANGED, bFact.change());

		// 3. F03 compact constructor: renaming record component while keeping component type and constructor body
		// keeps exact CONSTRUCTOR SymbolKey, marks DECLARATION modified, keeps BODY unchanged, and points to changed component.
		String recBefore = """
				record Pair(int left, String right) {
				    public Pair { System.out.println("validate"); }
				}
				""";
		String recAfter = """
				record Pair(int first, String right) {
				    public Pair { System.out.println("validate"); }
				}
				""";
		var recDiff = diff.compare(revision("repo",
				new Present(before, new SourceInput("Pair.java", recBefore)),
				new Present(after, new SourceInput("Pair.java", recAfter))));
		assertEquals(ProcessingStatus.COMPLETE, recDiff.status());
		var ctorFact = findFact(recDiff, Kind.CONSTRUCTOR, "Pair");
		assertEquals(ChangeKind.MODIFIED, ctorFact.change());
		assertEquals(MatchBasis.EXACT_SYMBOL_KEY, ctorFact.matchBasis());
		assertEquals(ctorFact.before().orElseThrow().key(), ctorFact.after().orElseThrow().key());
		assertEquals(Set.of(FacetKind.DECLARATION), ctorFact.changedFacets());
		var ctorAfterRefs = ctorFact.after().orElseThrow().facetReferences().get(FacetKind.DECLARATION);
		assertEquals(List.of("public Pair", "int first", "String right"),
				ctorAfterRefs.stream().map(r -> r.reference().orElseThrow().source()).toList());
		for (var ref : ctorAfterRefs) {
			assertReferenceMatchesSource(recAfter, ref.reference().orElseThrow());
		}
	}

	@Test
	void rv05TracksFieldAndBlockInitializersLocalConstructsAndInitializerFieldReordering() {
		var before = new SnapshotRef("repo", "sha-1");
		var after = new SnapshotRef("repo", "sha-2");

		var initAndLambda = diff.compare(revision("repo",
				new Present(before, new SourceInput("Worker.java", """
						class Worker {
						    int limit = 10;
						    static { System.out.println("v1"); }
						    void execute() {
						        Runnable r = () -> System.out.println(1);
						        Object anon = new Object() { int v = 1; };
						        class Local { int calc() { return 1; } }
						    }
						}
						""")),
				new Present(after, new SourceInput("Worker.java", """
						class Worker {
						    int limit = 20;
						    static { System.out.println("v2"); }
						    void execute() {
						        Runnable r = () -> System.out.println(2);
						        Object anon = new Object() { int v = 2; };
						        class Local { int calc() { return 2; } }
						    }
						}
						"""))));
		assertEquals(ProcessingStatus.COMPLETE, initAndLambda.status());
		assertEquals(Set.of(FacetKind.INITIALIZER), findFact(initAndLambda, Kind.FIELD, "limit").changedFacets());
		assertEquals(Set.of(FacetKind.BODY), findFact(initAndLambda, Kind.METHOD, "execute").changedFacets());
		assertTrue(initAndLambda.fileComparison().contextChanges()
				.contains(ContextChangeKind.INITIALIZER_BLOCK_CHANGED));

		var reordered = diff.compare(revision("repo",
				new Present(before, new SourceInput("InitOrder.java", """
						class InitOrder {
						    int state = 1;
						    { state = 2; }
						}
						""")),
				new Present(after, new SourceInput("InitOrder.java", """
						class InitOrder {
						    { state = 2; }
						    int state = 1;
						}
						"""))));
		assertEquals(ProcessingStatus.COMPLETE, reordered.status());
		assertEquals(StructuralEquality.DIFFERENT, reordered.fileComparison().structuralEquality());
		assertTrue(reordered.fileComparison().contextChanges().contains(ContextChangeKind.SCOPE_ORDER_CHANGED));
		assertEquals("{ state = 2; }", reordered.beforeContext().orElseThrow()
				.initializerBlocks().getFirst().provenance().reference().orElseThrow().source());

		var crossTypeInitAndOrder = diff.compare(revision("repo",
				new Present(before, new SourceInput("MultiClassInitOrder.java", """
						class A { int x = 1; { x = 2; } }
						class B {}
						""")),
				new Present(after, new SourceInput("MultiClassInitOrder.java", """
						class A { { x = 2; } int x = 1; }
						class B { { System.out.println(); } }
						"""))));
		assertEquals(ProcessingStatus.COMPLETE, crossTypeInitAndOrder.status());
		assertEquals(StructuralEquality.DIFFERENT, crossTypeInitAndOrder.fileComparison().structuralEquality());
		assertEquals(
				Set.of(ContextChangeKind.INITIALIZER_BLOCK_CHANGED, ContextChangeKind.SCOPE_ORDER_CHANGED),
				crossTypeInitAndOrder.fileComparison().contextChanges());

		var sameTypeStaticAndInstanceOrder = diff.compare(revision("repo",
				new Present(before, new SourceInput("SameTypeInitOrder.java", """
						class A {
						    int x = 1;
						    { x = 2; }
						}
						""")),
				new Present(after, new SourceInput("SameTypeInitOrder.java", """
						class A {
						    static { System.out.println("init"); }
						    { x = 2; }
						    int x = 1;
						    { System.out.println("extra"); }
						}
						"""))));
		assertEquals(ProcessingStatus.COMPLETE, sameTypeStaticAndInstanceOrder.status());
		assertEquals(
				Set.of(ContextChangeKind.INITIALIZER_BLOCK_CHANGED, ContextChangeKind.SCOPE_ORDER_CHANGED),
				sameTypeStaticAndInstanceOrder.fileComparison().contextChanges());

		var topLevelClassSwapWithInitializers = diff.compare(revision("repo",
				new Present(before, new SourceInput("ClassSwapWithInit.java", """
						class A { static { System.out.println("a"); } }
						class B { static { System.out.println("b"); } }
						""")),
				new Present(after, new SourceInput("ClassSwapWithInit.java", """
						class B { static { System.out.println("b"); } }
						class A { static { System.out.println("a"); } }
						"""))));
		assertEquals(ProcessingStatus.COMPLETE, topLevelClassSwapWithInitializers.status());
		assertEquals(
				Set.of(ContextChangeKind.SCOPE_ORDER_CHANGED),
				topLevelClassSwapWithInitializers.fileComparison().contextChanges());

		var twoDistinctInitializersSwapped = diff.compare(revision("repo",
				new Present(before, new SourceInput("SwappedDistinctInits.java", """
						class A { { x = 2; } int x = 1; { x = 3; } }
						""")),
				new Present(after, new SourceInput("SwappedDistinctInits.java", """
						class A { { x = 3; } int x = 1; { x = 2; } }
						"""))));
		assertEquals(ProcessingStatus.COMPLETE, twoDistinctInitializersSwapped.status());
		assertEquals(
				Set.of(ContextChangeKind.INITIALIZER_BLOCK_CHANGED, ContextChangeKind.SCOPE_ORDER_CHANGED),
				twoDistinctInitializersSwapped.fileComparison().contextChanges());
	}

	@Test
	void rv06ComparesOverloadsDefaultPackageNestedTypesRecordsCompactConstructorsEnumsAndAnnotations() {
		var before = new SnapshotRef("repo", "sha-1");
		var after = new SnapshotRef("repo", "sha-2");
		String beforeSrc = """
				class Outer {
				    void run(int x) { System.out.println(x); }
				    void run(String s) { System.out.println(s); }
				    class Inner { void run(int x) {} }
				}
				record Pair(int a, String b) {
				    Pair { if (a < 0) throw new IllegalArgumentException(); }
				}
				enum State {
				    READY(1) { void step() {} },
				    DONE(2) {}
				}
				@interface Tag { String value() default "v1"; }
				""";
		String afterSrc = """
				class Outer {
				    void run(int x) { System.out.println(x); }
				    void run(String s) { System.out.println(s.trim()); }
				    class Inner { void run(int x) {} }
				}
				record Pair(int a, String b) {
				    Pair(int a, String b) { this.a = a; this.b = b; }
				}
				enum State {
				    READY(1) { void step() { System.out.println(); } },
				    DONE(2) {}
				}
				@interface Tag { String value() default "v1"; }
				""";

		var result = diff.compare(revision("repo",
				new Present(before, new SourceInput("Multi.java", beforeSrc)),
				new Present(after, new SourceInput("Multi.java", afterSrc))));
		assertEquals(ProcessingStatus.COMPLETE, result.status());

		var runMethods = result.entityFacts().stream()
				.filter(f -> f.after().isPresent() && f.after().orElseThrow().key().kind() == Kind.METHOD
						&& f.after().orElseThrow().key().name().equals("run"))
				.toList();
		assertEquals(3, runMethods.size());
		assertEquals(List.of(ChangeKind.UNCHANGED, ChangeKind.MODIFIED, ChangeKind.UNCHANGED),
				runMethods.stream().map(EntityFact::change).toList());

		var pairCtor = findFact(result, Kind.CONSTRUCTOR, "Pair");
		assertEquals(ChangeKind.MODIFIED, pairCtor.change());
		assertEquals(Set.of(FacetKind.DECLARATION, FacetKind.BODY), pairCtor.changedFacets());

		var readyFact = findFact(result, Kind.ENUM_CONSTANT, "READY");
		assertEquals(ChangeKind.MODIFIED, readyFact.change());
		assertEquals(Set.of(FacetKind.BODY), readyFact.changedFacets());
		assertEquals(ChangeKind.UNCHANGED, findFact(result, Kind.ENUM_CONSTANT, "DONE").change());
		assertEquals(ChangeKind.UNCHANGED, findFact(result, Kind.ENUM, "State").change());
	}

	@Test
	void rv07AndRv08HandleSignatureOwnerPackageRenamesExplicitFileMovesAndAmbiguousPairsWithoutHeuristicGuessing() {
		var before = new SnapshotRef("repo", "sha-1");
		var after = new SnapshotRef("repo", "sha-2");

		var sigChange = diff.compare(revision("repo",
				new Present(before, new SourceInput("Svc.java",
						"package p1; class Svc { void process(int id) {} void legacy() {} }")),
				new Present(after, new SourceInput("Svc.java",
						"package p1; class Svc { void process(long id) {} void modern() {} }"))));
		assertEquals(ProcessingStatus.COMPLETE, sigChange.status());
		var removed = sigChange.entityFacts().stream().filter(f -> f.change() == ChangeKind.REMOVED).toList();
		var added = sigChange.entityFacts().stream().filter(f -> f.change() == ChangeKind.ADDED).toList();
		assertEquals(2, removed.size());
		assertEquals(2, added.size());
		assertTrue(removed.stream().allMatch(f -> f.unmatchedReason().orElseThrow() == UnmatchedReason.NO_COUNTERPART_KEY));
		assertTrue(added.stream().allMatch(f -> f.unmatchedReason().orElseThrow() == UnmatchedReason.NO_COUNTERPART_KEY));
		assertTrue(sigChange.fileComparison().reasons().stream()
				.anyMatch(reason -> reason.contains("cross-key equivalence is unresolved")));

		var movedSameKey = diff.compare(new StructuralDiffInput("repo", Intent.REVISION_COMPARISON,
				Optional.of(new Present(before, new SourceInput("module-a/src/Svc.java",
						"package shared; class Svc { int run() { return 1; } }"))),
				new Present(after, new SourceInput("module-b/src/Svc.java",
						"package shared; class Svc { int run() { return 2; } }")),
				Optional.of(new FileAssociation("module-a/src/Svc.java", "module-b/src/Svc.java"))));
		assertEquals(ProcessingStatus.COMPLETE, movedSameKey.status());
		assertEquals(PathRelation.PATH_CHANGED, movedSameKey.fileComparison().pathRelation());
		var movedMethod = findFact(movedSameKey, Kind.METHOD, "run");
		assertEquals(ChangeKind.MODIFIED, movedMethod.change());
		assertEquals("module-a/src/Svc.java", movedMethod.before().orElseThrow().path());
		assertEquals("module-b/src/Svc.java", movedMethod.after().orElseThrow().path());

		var renamedAmbiguous = diff.compare(new StructuralDiffInput("repo", Intent.REVISION_COMPARISON,
				Optional.of(new Present(before, new SourceInput("v1/OldSvc.java",
						"package v1; class OldSvc { void handle(int a) {} void handle(String b) {} }"))),
				new Present(after, new SourceInput("v2/NewSvc.java",
						"package v2; class NewSvc { void dispatch(long a) {} void dispatch(Object b) {} }")),
				Optional.of(new FileAssociation("v1/OldSvc.java", "v2/NewSvc.java"))));
		assertEquals(ProcessingStatus.COMPLETE, renamedAmbiguous.status());
		assertEquals(PathRelation.PATH_CHANGED, renamedAmbiguous.fileComparison().pathRelation());
		assertEquals(3, renamedAmbiguous.entityFacts().stream().filter(f -> f.change() == ChangeKind.REMOVED).count());
		assertEquals(3, renamedAmbiguous.entityFacts().stream().filter(f -> f.change() == ChangeKind.ADDED).count());
	}

	@Test
	void rv09AndF02PreserveSnapshotProvenanceAcrossFileAddRemoveUnavailableAndParseFailures() {
		var before = new SnapshotRef("repo-x", "sha-1");
		var after = new SnapshotRef("repo-x", "sha-2");

		var addedFile = diff.compare(revision("repo-x",
				new Absent(before, "src/Created.java", "Verified absent in tree manifest"),
				new Present(after, new SourceInput("src/Created.java", "class Created { void init() {} }"))));
		assertEquals(ProcessingStatus.COMPLETE, addedFile.status());
		assertEquals(PresenceRelation.ADDED, addedFile.fileComparison().presence());
		assertEquals(new SnapshotProvenance(before, "src/Created.java", SidePresence.ABSENT,
				Optional.of("Verified absent in tree manifest"), Optional.empty()),
				addedFile.beforeSnapshot().orElseThrow());
		assertEquals(new SnapshotProvenance(after, "src/Created.java", SidePresence.PRESENT,
				Optional.empty(), Optional.empty()), addedFile.afterSnapshot().orElseThrow());
		assertEquals(2, addedFile.entityFacts().size());
		assertTrue(addedFile.entityFacts().stream().allMatch(f ->
				f.change() == ChangeKind.ADDED && f.unmatchedReason().orElseThrow() == UnmatchedReason.FILE_ADDED));

		var removedFile = diff.compare(revision("repo-x",
				new Present(before, new SourceInput("src/Deleted.java", "class Deleted { void close() {} }")),
				new Absent(after, "src/Deleted.java", "Verified deleted in commit")));
		assertEquals(ProcessingStatus.COMPLETE, removedFile.status());
		assertEquals(PresenceRelation.REMOVED, removedFile.fileComparison().presence());
		assertEquals(new SnapshotProvenance(before, "src/Deleted.java", SidePresence.PRESENT,
				Optional.empty(), Optional.empty()), removedFile.beforeSnapshot().orElseThrow());
		assertEquals(new SnapshotProvenance(after, "src/Deleted.java", SidePresence.ABSENT,
				Optional.of("Verified deleted in commit"), Optional.empty()),
				removedFile.afterSnapshot().orElseThrow());
		assertEquals(2, removedFile.entityFacts().size());
		assertTrue(removedFile.entityFacts().stream().allMatch(f ->
				f.change() == ChangeKind.REMOVED && f.unmatchedReason().orElseThrow() == UnmatchedReason.FILE_REMOVED));

		var unavailableAfter = diff.compare(revision("repo-x",
				new Present(before, new SourceInput("src/Existing.java", "class Existing { void keep() {} }")),
				new Unavailable(after, "src/Existing.java", "S02 upstream 503")));
		assertEquals(ProcessingStatus.INCOMPLETE, unavailableAfter.status());
		assertEquals(PresenceRelation.UNRESOLVED, unavailableAfter.fileComparison().presence());
		assertEquals(new SnapshotProvenance(before, "src/Existing.java", SidePresence.PRESENT,
				Optional.empty(), Optional.empty()), unavailableAfter.beforeSnapshot().orElseThrow());
		assertEquals(new SnapshotProvenance(after, "src/Existing.java", SidePresence.UNAVAILABLE,
				Optional.empty(), Optional.of("S02 upstream 503")),
				unavailableAfter.afterSnapshot().orElseThrow());
		assertTrue(unavailableAfter.entityFacts().isEmpty());
	}

	@Test
	void rv10AndF04EnforceMixedScopePrecedenceEvenOnBrokenJavaWhileKeepingJavaVsUnavailableFailed() {
		var before = new SnapshotRef("repo", "sha-1");
		var after = new SnapshotRef("repo", "sha-2");

		// Two non-Java paths -> OUT_OF_SCOPE even when one side is Unavailable, preserving both snapshot identities.
		var outOfScope = diff.compare(new StructuralDiffInput("repo", Intent.REVISION_COMPARISON,
				Optional.of(new Unavailable(before, "script.py", "S02 timeout")),
				new Present(after, new SourceInput("README.md", "# docs")),
				Optional.of(new FileAssociation("script.py", "README.md"))));
		assertEquals(ProcessingStatus.OUT_OF_SCOPE, outOfScope.status());
		assertEquals(SidePresence.UNAVAILABLE, outOfScope.beforeSnapshot().orElseThrow().presence());
		assertEquals(SidePresence.PRESENT, outOfScope.afterSnapshot().orElseThrow().presence());
		assertTrue(outOfScope.diagnostics().stream().anyMatch(d ->
				d.side() == SideRole.BEFORE && d.code() == DiagnosticCode.UNSUPPORTED_LANGUAGE));
		assertTrue(outOfScope.diagnostics().stream().anyMatch(d ->
				d.side() == SideRole.BEFORE && d.code() == DiagnosticCode.SNAPSHOT_UNAVAILABLE));
		assertTrue(outOfScope.diagnostics().stream().anyMatch(d ->
				d.side() == SideRole.AFTER && d.code() == DiagnosticCode.UNSUPPORTED_LANGUAGE));

		// F04: Mixed scope .java -> .txt returns INCOMPLETE / MIXED_LANGUAGE_SCOPE EVEN when Java side fails to parse,
		// retaining Java PARSE_FAILED diagnostic and emitting no add/remove facts.
		var brokenJavaToTxt = diff.compare(new StructuralDiffInput("repo", Intent.REVISION_COMPARISON,
				Optional.of(new Present(before, new SourceInput("A.java", "class A { broken }"))),
				new Present(after, new SourceInput("A.txt", "notes")),
				Optional.of(new FileAssociation("A.java", "A.txt"))));
		assertEquals(ProcessingStatus.INCOMPLETE, brokenJavaToTxt.status());
		assertTrue(brokenJavaToTxt.entityFacts().isEmpty());
		assertTrue(brokenJavaToTxt.diagnostics().stream()
				.anyMatch(d -> d.code() == DiagnosticCode.MIXED_LANGUAGE_SCOPE));
		assertTrue(brokenJavaToTxt.diagnostics().stream().anyMatch(d ->
				d.side() == SideRole.BEFORE && d.code() == DiagnosticCode.PARSE_FAILED
						&& d.snapshot().equals(Optional.of(before))));
		assertTrue(brokenJavaToTxt.diagnostics().stream().anyMatch(d ->
				d.side() == SideRole.AFTER && d.code() == DiagnosticCode.UNSUPPORTED_LANGUAGE));

		// F04: Reverse direction .txt -> .java with broken Java ALSO returns INCOMPLETE / MIXED_LANGUAGE_SCOPE.
		var txtToBrokenJava = diff.compare(new StructuralDiffInput("repo", Intent.REVISION_COMPARISON,
				Optional.of(new Present(before, new SourceInput("A.txt", "notes"))),
				new Present(after, new SourceInput("A.java", "class A { int x = ; }")),
				Optional.of(new FileAssociation("A.txt", "A.java"))));
		assertEquals(ProcessingStatus.INCOMPLETE, txtToBrokenJava.status());
		assertTrue(txtToBrokenJava.entityFacts().isEmpty());
		assertTrue(txtToBrokenJava.diagnostics().stream()
				.anyMatch(d -> d.code() == DiagnosticCode.MIXED_LANGUAGE_SCOPE));
		assertTrue(txtToBrokenJava.diagnostics().stream().anyMatch(d ->
				d.side() == SideRole.BEFORE && d.code() == DiagnosticCode.UNSUPPORTED_LANGUAGE));
		assertTrue(txtToBrokenJava.diagnostics().stream().anyMatch(d ->
				d.side() == SideRole.AFTER && d.code() == DiagnosticCode.PARSE_FAILED
						&& d.snapshot().equals(Optional.of(after))));

		// F04: Java/Java pair with FAILED on one side and UNAVAILABLE on the other returns FAILED, preserving both diagnostics & snapshots.
		var failedVsUnavailable = diff.compare(revision("repo",
				new Present(before, new SourceInput("A.java", "class A { int x = ; }")),
				new Unavailable(after, "A.java", "S02 fetch timeout")));
		assertEquals(ProcessingStatus.FAILED, failedVsUnavailable.status());
		assertTrue(failedVsUnavailable.entityFacts().isEmpty());
		assertEquals(SidePresence.PRESENT, failedVsUnavailable.beforeSnapshot().orElseThrow().presence());
		assertEquals(SidePresence.UNAVAILABLE, failedVsUnavailable.afterSnapshot().orElseThrow().presence());
		assertTrue(failedVsUnavailable.diagnostics().stream().anyMatch(d ->
				d.side() == SideRole.BEFORE && d.code() == DiagnosticCode.PARSE_FAILED));
		assertTrue(failedVsUnavailable.diagnostics().stream().anyMatch(d ->
				d.side() == SideRole.AFTER && d.code() == DiagnosticCode.SNAPSHOT_UNAVAILABLE));

		// Partial coverage seam: PARTIAL + valid -> INCOMPLETE; PARTIAL + FAILED -> FAILED.
		var partialSource = new ParsedSource("A.java", Optional.empty(), List.of(),
				new FileContext(List.of(), Optional.empty()), List.of());
		var partialDiag = new ParseDiagnostic(JavaParseResult.DiagnosticCode.EXTRACTION_FAILURE,
				Severity.ERROR, "Unverified member skipped", Optional.empty());
		var partialCoverage = new Coverage(List.of(new SourceRange(1, 1, 1, 10)),
				List.of(new CoverageGap(Optional.empty(), "Method region unverified")));
		var partialParseResult = new JavaParseResult(Status.PARTIAL, Optional.of(partialSource),
				List.of(partialDiag), Optional.of(partialCoverage));
		var seamDiff = new StructuralDiff(source -> source.sourceContent().contains("PARTIAL_MARKER")
				? new ParseOutcome(partialParseResult, Optional.empty())
				: JavaStructuralRepresentation.parse(new JavaSourceParser(), source));

		var partialVsSuccess = seamDiff.compare(revision("repo",
				new Present(before, new SourceInput("A.java", "class A { /* PARTIAL_MARKER */ }")),
				new Present(after, new SourceInput("A.java", "class A {}"))));
		assertEquals(ProcessingStatus.INCOMPLETE, partialVsSuccess.status());

		var partialVsFailed = seamDiff.compare(revision("repo",
				new Present(before, new SourceInput("A.java", "class A { /* PARTIAL_MARKER */ }")),
				new Present(after, new SourceInput("A.java", "class A { int x = ; }"))));
		assertEquals(ProcessingStatus.FAILED, partialVsFailed.status());
	}

	@Test
	void rv11AndF05RetainTrustworthyInitialInventoryEvenWhenResidualsCauseIncompleteAttribution() {
		var targetSnap = new SnapshotRef("repo", "sha-init");

		var validInitial = diff.compare(new StructuralDiffInput("repo", Intent.INITIAL_ASSESSMENT,
				Optional.empty(),
				new Present(targetSnap, new SourceInput("src/App.java", """
						package demo;
						public class App {
						    public void start() {}
						}
						""")),
				Optional.empty()));
		assertEquals(ProcessingStatus.COMPLETE, validInitial.status());
		assertTrue(validInitial.entityFacts().isEmpty());
		assertTrue(validInitial.coverage().fullCoverage());
		assertTrue(validInitial.beforeSnapshot().isEmpty());
		assertEquals(new SnapshotProvenance(targetSnap, "src/App.java", SidePresence.PRESENT,
				Optional.empty(), Optional.empty()), validInitial.afterSnapshot().orElseThrow());
		assertEquals(2, validInitial.initialInventory().orElseThrow().entities().size());

		// F05: Initial assessment with residual `;` is INCOMPLETE but RETAINS verified target inventory.
		var residualInitial = diff.compare(new StructuralDiffInput("repo", Intent.INITIAL_ASSESSMENT,
				Optional.empty(),
				new Present(targetSnap, new SourceInput("src/App.java", "class App { int count = 1; ; }")),
				Optional.empty()));
		assertEquals(ProcessingStatus.INCOMPLETE, residualInitial.status());
		assertFalse(residualInitial.coverage().fullCoverage());
		assertTrue(residualInitial.entityFacts().isEmpty());
		assertEquals(SidePresence.PRESENT, residualInitial.afterSnapshot().orElseThrow().presence());
		var retainedInventory = residualInitial.initialInventory().orElseThrow();
		assertEquals(2, retainedInventory.entities().size());
		assertEquals(1, retainedInventory.residualRegions().size());
		assertTrue(residualInitial.fileComparison().contextChanges()
				.contains(ContextChangeKind.RESIDUAL_STRUCTURAL_CHANGE));

		// Unverified PARTIAL / FAILED / OUT_OF_SCOPE initial targets never fabricate initialInventory, but keep afterSnapshot.
		var nonJavaInitial = diff.compare(new StructuralDiffInput("repo", Intent.INITIAL_ASSESSMENT,
				Optional.empty(), new Present(targetSnap, new SourceInput("README.md", "# App")), Optional.empty()));
		assertEquals(ProcessingStatus.OUT_OF_SCOPE, nonJavaInitial.status());
		assertTrue(nonJavaInitial.initialInventory().isEmpty());
		assertEquals("README.md", nonJavaInitial.afterSnapshot().orElseThrow().path());

		var brokenInitial = diff.compare(new StructuralDiffInput("repo", Intent.INITIAL_ASSESSMENT,
				Optional.empty(), new Present(targetSnap, new SourceInput("App.java", "class App {")), Optional.empty()));
		assertEquals(ProcessingStatus.FAILED, brokenInitial.status());
		assertTrue(brokenInitial.initialInventory().isEmpty());
		assertEquals("App.java", brokenInitial.afterSnapshot().orElseThrow().path());
	}

	@Test
	void rv12F01AndF06CoverFieldGroupingAttributionUnchangedResidualAfterTypeRemovalAndSingleSourceDiagnosticGrounding() {
		var before = new SnapshotRef("repo", "sha-before");
		var after = new SnapshotRef("repo", "sha-after");

		// Package, import, and module context changes.
		assertEquals(Set.of(ContextChangeKind.PACKAGE_CHANGED),
				diff.compare(revision("repo",
						new Present(before, new SourceInput("package-info.java", "@Deprecated package demo;")),
						new Present(after, new SourceInput("package-info.java", "@Deprecated(forRemoval = true) package demo;"))))
						.fileComparison().contextChanges());

		assertEquals(Set.of(ContextChangeKind.IMPORTS_CHANGED),
				diff.compare(revision("repo",
						new Present(before, new SourceInput("A.java", "import java.util.List;\nimport java.util.Set;\nclass A {}")),
						new Present(after, new SourceInput("A.java", "import java.util.Set;\nimport java.util.List;\nclass A {}"))))
						.fileComparison().contextChanges());

		assertEquals(Set.of(ContextChangeKind.MODULE_CHANGED, ContextChangeKind.SCOPE_ORDER_CHANGED),
				diff.compare(revision("repo",
						new Present(before, new SourceInput("module-info.java", "module demo { requires java.base; }")),
						new Present(after, new SourceInput("module-info.java", "open module demo { requires java.base; exports demo; }"))))
						.fileComparison().contextChanges());

		// F01 (False negative check): Method body change MUST NOT mask multi-field declaration grouping split/merge!
		var splitAndBodyChange = diff.compare(revision("repo",
				new Present(before, new SourceInput("A.java", "class A { int a, b; int run() { return 1; } }")),
				new Present(after, new SourceInput("A.java", "class A { int a; int b; int run() { return 2; } }"))));
		assertEquals(ProcessingStatus.INCOMPLETE, splitAndBodyChange.status());
		assertTrue(splitAndBodyChange.fileComparison().contextChanges()
				.contains(ContextChangeKind.RESIDUAL_STRUCTURAL_CHANGE));
		assertEquals(ChangeKind.UNCHANGED, findFact(splitAndBodyChange, Kind.FIELD, "a").change());
		assertEquals(ChangeKind.UNCHANGED, findFact(splitAndBodyChange, Kind.FIELD, "b").change());
		var runFact = findFact(splitAndBodyChange, Kind.METHOD, "run");
		assertEquals(ChangeKind.MODIFIED, runFact.change());
		assertEquals(Set.of(FacetKind.BODY), runFact.changedFacets());
		var groupDiag = splitAndBodyChange.diagnostics().getFirst();
		assertEquals(SideRole.AFTER, groupDiag.side());
		assertEquals(Optional.of(after), groupDiag.snapshot());
		assertEquals(Optional.of("A.java"), groupDiag.path());
		assertTrue(groupDiag.range().isPresent());

		// F01 (False positive check): Removing `class B {}` after `class A {} ;` keeps `;` anchored after A -> COMPLETE!
		var removeTypeKeepSemicolon = diff.compare(revision("repo",
				new Present(before, new SourceInput("Types.java", "class A {} ; class B {}")),
				new Present(after, new SourceInput("Types.java", "class A {} ;"))));
		assertEquals(ProcessingStatus.COMPLETE, removeTypeKeepSemicolon.status(),
				() -> removeTypeKeepSemicolon.diagnostics().toString());
		assertTrue(removeTypeKeepSemicolon.coverage().fullCoverage());
		assertFalse(removeTypeKeepSemicolon.fileComparison().contextChanges()
				.contains(ContextChangeKind.RESIDUAL_STRUCTURAL_CHANGE));
		assertEquals(ChangeKind.UNCHANGED, findFact(removeTypeKeepSemicolon, Kind.CLASS, "A").change());
		assertEquals(ChangeKind.REMOVED, findFact(removeTypeKeepSemicolon, Kind.CLASS, "B").change());

		// RV-12: Explicit residual change alongside an entity change preserves entity fact and reports residual.
		var residualAndEntity = diff.compare(revision("repo",
				new Present(before, new SourceInput("Mixed.java", "class Mixed { int count = 1; }")),
				new Present(after, new SourceInput("Mixed.java", "class Mixed { int count = 2; ; }"))));
		assertEquals(ProcessingStatus.INCOMPLETE, residualAndEntity.status());
		assertEquals(ChangeKind.MODIFIED, findFact(residualAndEntity, Kind.FIELD, "count").change());
		assertEquals(SideRole.AFTER, residualAndEntity.diagnostics().getFirst().side());
		assertEquals(Optional.of(after), residualAndEntity.diagnostics().getFirst().snapshot());

		// F06: When AFTER residual has no exact range, diagnostic on AFTER never borrows BEFORE's range and keeps explicit reason.
		var f06Diff = new StructuralDiff(source -> {
			var parsed = JavaStructuralRepresentation.parse(new JavaSourceParser(), source);
			if (source.filePath().equals("New.java")) {
				var rep = parsed.representation().orElseThrow();
				var ungroundedResidual = new ResidualRegion(
						rep.residualRegions().getFirst().enclosingType(),
						Optional.empty(),
						0,
						Fingerprint.compute("RESIDUAL:TYPE", List.of(";", ";")),
						ProvenanceRef.unavailable("Synthetic residual token lacks source coordinates"));
				var modifiedRep = new JavaStructuralRepresentation(
						rep.filePath(), rep.normalizationVersion(), rep.parserVersion(), rep.languagePolicy(),
						rep.parsedSource(), rep.fileContext(), rep.entities(), rep.fieldGroups(),
						rep.initializerBlocks(), List.of(ungroundedResidual), rep.topLevelLayout(),
						rep.typeLayouts(), rep.moduleLayout(), rep.wholeFileFingerprint(), rep.rawFacts());
				return new ParseOutcome(parsed.parseResult(), Optional.of(modifiedRep));
			}
			return parsed;
		});
		var f06Result = f06Diff.compare(new StructuralDiffInput("repo", Intent.REVISION_COMPARISON,
				Optional.of(new Present(before, new SourceInput("Old.java", "class A { ; }"))),
				new Present(after, new SourceInput("New.java", "class A { ; }")),
				Optional.of(new FileAssociation("Old.java", "New.java"))));
		assertEquals(ProcessingStatus.INCOMPLETE, f06Result.status());
		var f06Diag = f06Result.diagnostics().getFirst();
		assertEquals(SideRole.AFTER, f06Diag.side());
		assertEquals(Optional.of(after), f06Diag.snapshot());
		assertEquals(Optional.of("New.java"), f06Diag.path());
		assertTrue(f06Diag.range().isEmpty(), "Must not borrow BEFORE range for AFTER diagnostic");
		assertEquals(Optional.of("Synthetic residual token lacks source coordinates"),
				f06Diag.rangeUnavailableReason());

		// F06: Removed residual from BEFORE grounds diagnostic to BEFORE snapshot, path, and range.
		var removedResidualResult = diff.compare(revision("repo",
				new Present(before, new SourceInput("OldOnly.java", "class A { int x = 1; ; }")),
				new Present(after, new SourceInput("OldOnly.java", "class A { int x = 1; }"))));
		assertEquals(ProcessingStatus.INCOMPLETE, removedResidualResult.status());
		var beforeDiag = removedResidualResult.diagnostics().getFirst();
		assertEquals(SideRole.BEFORE, beforeDiag.side());
		assertEquals(Optional.of(before), beforeDiag.snapshot());
		assertEquals(Optional.of("OldOnly.java"), beforeDiag.path());
		assertEquals(Optional.of(new SourceRange(1, 22, 1, 22)), beforeDiag.range());
	}

	@Test
	void rv13ConservativelyDiffsLiteralsAnnotationAndMemberOrderQualifiedTypesAndArraysVsVarargs() {
		var before = new SnapshotRef("repo", "sha-1");
		var after = new SnapshotRef("repo", "sha-2");

		var literalDiff = diff.compare(revision("repo",
				new Present(before, new SourceInput("Lit.java", "class Lit { String s() { return \"a b\"; } }")),
				new Present(after, new SourceInput("Lit.java", "class Lit { String s() { return \"a  b\"; } }"))));
		assertEquals(ChangeKind.MODIFIED, findFact(literalDiff, Kind.METHOD, "s").change());

		var memberReorder = diff.compare(revision("repo",
				new Present(before, new SourceInput("Order.java", "class Order { void first() {} void second() {} }")),
				new Present(after, new SourceInput("Order.java", "class Order { void second() {} void first() {} }"))));
		assertEquals(ProcessingStatus.COMPLETE, memberReorder.status());
		var orderClass = findFact(memberReorder, Kind.CLASS, "Order");
		assertEquals(ChangeKind.MODIFIED, orderClass.change());
		assertEquals(Set.of(FacetKind.MEMBER_ORDER), orderClass.changedFacets());
		assertEquals(ChangeKind.UNCHANGED, findFact(memberReorder, Kind.METHOD, "first").change());
		assertEquals(ChangeKind.UNCHANGED, findFact(memberReorder, Kind.METHOD, "second").change());

		var syntaxConservatism = diff.compare(revision("repo",
				new Present(before, new SourceInput("Types.java", """
						import java.util.List;
						class Types {
						    int[] items;
						    List<String> names() { return null; }
						    void accept(String[] values) {}
						}
						""")),
				new Present(after, new SourceInput("Types.java", """
						import java.util.List;
						class Types {
						    int items[];
						    java.util.List<String> names() { return null; }
						    void accept(String... values) {}
						}
						"""))));
		assertEquals(ProcessingStatus.COMPLETE, syntaxConservatism.status());
		assertEquals(ChangeKind.MODIFIED, findFact(syntaxConservatism, Kind.FIELD, "items").change());
		assertEquals(ChangeKind.MODIFIED, findFact(syntaxConservatism, Kind.METHOD, "names").change());
		assertEquals(1, syntaxConservatism.entityFacts().stream()
				.filter(f -> f.change() == ChangeKind.REMOVED).count());
		assertEquals(1, syntaxConservatism.entityFacts().stream()
				.filter(f -> f.change() == ChangeKind.ADDED).count());
	}

	@Test
	void rv14AndF04ValidateRepositoryRelativePathsContradictorySnapshotsAndSameShaUnavailable() {
		var sha1 = new SnapshotRef("repo-a", "sha-1");
		var sha2 = new SnapshotRef("repo-a", "sha-2");

		assertInvalidInput(diff.compare(null));

		// F04: Non-trimmed legitimate relative path with leading space in segment is accepted consistently with M1.
		var validLeadingSpaceSegment = diff.compare(revision("repo-a",
				new Present(sha1, new SourceInput(" folder/Example.java", "class Example {}")),
				new Present(sha2, new SourceInput(" folder/Example.java", "class Example {}"))));
		assertEquals(ProcessingStatus.COMPLETE, validLeadingSpaceSegment.status());
		assertEquals(" folder/Example.java", validLeadingSpaceSegment.afterSnapshot().orElseThrow().path());

		// F04: Repository-relative path rejections across Present, Absent, Unavailable, and FileAssociation.
		List<String> invalidPaths = List.of(
				"",
				"   ",
				"/src/Example.java",
				"C:/src/Example.java",
				"d:Example.java",
				"src\\Example.java",
				"\\\\server\\share\\Example.java",
				"../Example.java",
				"src/../Example.java",
				"./Example.java",
				"src/./Example.java",
				".",
				"..",
				"src//Example.java",
				"src/",
				"src/\0Example.java",
				"src/\nExample.java");
		for (String badPath : invalidPaths) {
			assertInvalidInput(diff.compare(revision("repo-a",
					new Present(sha1, new SourceInput(badPath, "class A {}")),
					new Present(sha2, new SourceInput("A.java", "class A {}")))));
			assertInvalidInput(diff.compare(revision("repo-a",
					new Absent(sha1, badPath, "absent"),
					new Present(sha2, new SourceInput("A.java", "class A {}")))));
			assertInvalidInput(diff.compare(revision("repo-a",
					new Unavailable(sha1, badPath, "timeout"),
					new Present(sha2, new SourceInput("A.java", "class A {}")))));
			assertInvalidInput(diff.compare(new StructuralDiffInput("repo-a", Intent.REVISION_COMPARISON,
					Optional.of(new Present(sha1, new SourceInput("A.java", "class A {}"))),
					new Present(sha2, new SourceInput("B.java", "class A {}")),
					Optional.of(new FileAssociation(badPath, "B.java")))));
		}

		// F04: Contradictory inputs with identical repo + SHA + path -> FAILED / INVALID_INPUT.
		assertInvalidInput(diff.compare(revision("repo-a",
				new Present(sha1, new SourceInput("src/A.java", "class A { int x = 1; }")),
				new Present(sha1, new SourceInput("src/A.java", "class A { int x = 2; }")))));
		assertInvalidInput(diff.compare(revision("repo-a",
				new Present(sha1, new SourceInput("docs/readme.txt", "v1")),
				new Present(sha1, new SourceInput("docs/readme.txt", "v2")))));
		assertInvalidInput(diff.compare(revision("repo-a",
				new Present(sha1, new SourceInput("src/A.java", "class A {}")),
				new Absent(sha1, "src/A.java", "claimed absent"))));
		assertInvalidInput(diff.compare(revision("repo-a",
				new Absent(sha1, "src/A.java", "claimed absent"),
				new Present(sha1, new SourceInput("src/A.java", "class A {}")))));

		// F04: Same repo + SHA + path where one side is UNAVAILABLE is missing data, NOT contradictory input:
		// valid Java -> INCOMPLETE; broken Java -> FAILED with both diagnostics.
		var sameShaValidAndUnavailable = diff.compare(revision("repo-a",
				new Present(sha1, new SourceInput("src/A.java", "class A {}")),
				new Unavailable(sha1, "src/A.java", "transient fetch error")));
		assertEquals(ProcessingStatus.INCOMPLETE, sameShaValidAndUnavailable.status());
		assertTrue(sameShaValidAndUnavailable.entityFacts().isEmpty());
		assertEquals(SidePresence.PRESENT, sameShaValidAndUnavailable.beforeSnapshot().orElseThrow().presence());
		assertEquals(SidePresence.UNAVAILABLE, sameShaValidAndUnavailable.afterSnapshot().orElseThrow().presence());
		assertTrue(sameShaValidAndUnavailable.diagnostics().stream()
				.anyMatch(d -> d.side() == SideRole.AFTER && d.code() == DiagnosticCode.SNAPSHOT_UNAVAILABLE));

		var sameShaBrokenAndUnavailable = diff.compare(revision("repo-a",
				new Present(sha1, new SourceInput("src/A.java", "class A { int x = ; }")),
				new Unavailable(sha1, "src/A.java", "transient fetch error")));
		assertEquals(ProcessingStatus.FAILED, sameShaBrokenAndUnavailable.status());
		assertTrue(sameShaBrokenAndUnavailable.diagnostics().stream()
				.anyMatch(d -> d.side() == SideRole.BEFORE && d.code() == DiagnosticCode.PARSE_FAILED));
		assertTrue(sameShaBrokenAndUnavailable.diagnostics().stream()
				.anyMatch(d -> d.side() == SideRole.AFTER && d.code() == DiagnosticCode.SNAPSHOT_UNAVAILABLE));

		// Repository binding, intent, and association mismatches.
		assertInvalidInput(diff.compare(revision("repo-a",
				new Present(new SnapshotRef("repo-b", "sha-1"), new SourceInput("a.py", "x = 1")),
				new Present(sha2, new SourceInput("a.py", "x = 2")))));
		assertInvalidInput(diff.compare(revision("repo-a",
				new Present(new SnapshotRef("repo-a", " "), new SourceInput("A.java", "class A {}")),
				new Present(sha2, new SourceInput("A.java", "class A {}")))));
		assertInvalidInput(diff.compare(new StructuralDiffInput("repo-a", Intent.REVISION_COMPARISON,
				Optional.of(new Present(sha1, new SourceInput("A.java", null))),
				new Present(sha2, new SourceInput("A.txt", "doc")),
				Optional.of(new FileAssociation("A.java", "A.txt")))));
		assertInvalidInput(diff.compare(revision("repo-a",
				new Present(sha1, new SourceInput("Old.java", "class Old {}")),
				new Present(sha2, new SourceInput("New.java", "class New {}")))));
		assertInvalidInput(diff.compare(new StructuralDiffInput("repo-a", Intent.INITIAL_ASSESSMENT,
				Optional.of(new Absent(sha1, "A.java", "none")),
				new Present(sha2, new SourceInput("A.java", "class A {}")), Optional.empty())));
	}

	@Test
	void rv15Rv16AndF07VerifyConcurrencyEqualDigestFallbackOutputInvariantsAndImmutability() throws Exception {
		var before = new SnapshotRef("repo", "sha-1");
		var after = new SnapshotRef("repo", "sha-2");
		var input = revision("repo",
				new Present(before, new SourceInput("Concurrent.java", """
						package app;
						class Concurrent {
						    int a = 1;
						    void keep() {}
						    void change() { System.out.println("v1"); }
						    void removeMe() {}
						}
						""")),
				new Present(after, new SourceInput("Concurrent.java", """
						package app;
						class Concurrent {
						    int a = 1;
						    void keep() {}
						    void change() { System.out.println("v2"); }
						    void addMe() {}
						}
						""")));

		var baseline = diff.compare(input);
		try (var pool = Executors.newFixedThreadPool(4)) {
			var tasks = new ArrayList<Callable<StructuralDiffResult>>();
			for (int i = 0; i < 16; i++) {
				tasks.add(() -> diff.compare(input));
			}
			for (var future : pool.invokeAll(tasks)) {
				assertEquals(baseline, future.get());
			}
		}

		assertEquals(List.of("Concurrent", "a", "keep", "change", "removeMe", "addMe"),
				baseline.entityFacts().stream()
						.map(f -> f.before().or(f::after).orElseThrow().key().name())
						.toList());

		// F08 / RV-16: Controlled equal-digest fixture inside StructuralDiff still detects MODIFIED BODY via canonical token check.
		var collidingDiff = new StructuralDiff(source -> {
			var parsed = JavaStructuralRepresentation.parse(new JavaSourceParser(), source);
			if (source.sourceContent().contains("COLLIDE_AFTER")) {
				var rep = parsed.representation().orElseThrow();
				var beforeBase = JavaStructuralRepresentation.parse(new JavaSourceParser(),
						new SourceInput("Collide.java", "class Collide { int f() { return 1; } }"))
						.representation().orElseThrow();
				String forcedSha = beforeBase.entities().getLast().bodyFacet().orElseThrow().sha256();
				var updatedEntities = new ArrayList<EntityProjection>();
				for (var ent : rep.entities()) {
					if (ent.key().kind() == Kind.METHOD) {
						var fakeBodyFp = new Fingerprint(rep.normalizationVersion(), "FACET:BODY:METHOD",
								forcedSha, ent.bodyFacet().orElseThrow().canonicalTokens());
						var facets = new EnumMap<>(ent.facets());
						facets.put(FacetKind.BODY, fakeBodyFp);
						updatedEntities.add(new EntityProjection(ent.entity(), facets, ent.facetReferences(),
								ent.declarationRef(), ent.headerRef(), ent.sharedDeclarationRef(),
								ent.sharedPrefixRef(), ent.bodyRef(), ent.initializerRef(),
								ent.annotationDefaultRef()));
					} else {
						updatedEntities.add(ent);
					}
				}
				var forcedRep = new JavaStructuralRepresentation(
						rep.filePath(), rep.normalizationVersion(), rep.parserVersion(), rep.languagePolicy(),
						rep.parsedSource(), rep.fileContext(), updatedEntities, rep.fieldGroups(),
						rep.initializerBlocks(), rep.residualRegions(), rep.topLevelLayout(),
						rep.typeLayouts(), rep.moduleLayout(), rep.wholeFileFingerprint(), rep.rawFacts());
				return new ParseOutcome(parsed.parseResult(), Optional.of(forcedRep));
			}
			return parsed;
		});
		var collidedResult = collidingDiff.compare(revision("repo",
				new Present(before, new SourceInput("Collide.java", "class Collide { int f() { return 1; } }")),
				new Present(after, new SourceInput("Collide.java", "class Collide { int f() { /* COLLIDE_AFTER */ return 2; } }"))));
		var collidedMethod = findFact(collidedResult, Kind.METHOD, "f");
		assertEquals(
				collidedMethod.before().orElseThrow().facetFingerprints().get(FacetKind.BODY).sha256(),
				collidedMethod.after().orElseThrow().facetFingerprints().get(FacetKind.BODY).sha256());
		assertEquals(ChangeKind.MODIFIED, collidedMethod.change());
		assertEquals(Set.of(FacetKind.BODY), collidedMethod.changedFacets());

		// F07: Output invariant checks.
		var keepFact = findFact(baseline, Kind.METHOD, "keep");
		var changeFact = findFact(baseline, Kind.METHOD, "change");
		assertThrows(IllegalArgumentException.class, () -> new EntityFact(
				ChangeKind.UNCHANGED, keepFact.before(), changeFact.after(), Set.of(),
				MatchBasis.EXACT_SYMBOL_KEY, Optional.empty(), JavaStructuralRepresentation.NORMALIZATION_VERSION));
		assertThrows(IllegalArgumentException.class, () -> new EntityFact(
				ChangeKind.MODIFIED, keepFact.before(), changeFact.after(), Set.of(FacetKind.BODY),
				MatchBasis.EXACT_SYMBOL_KEY, Optional.empty(), JavaStructuralRepresentation.NORMALIZATION_VERSION));
		assertThrows(IllegalArgumentException.class, () -> new EntityFact(
				ChangeKind.ADDED, Optional.empty(), changeFact.after(), Set.of(),
				MatchBasis.UNMATCHED_AFTER, Optional.of(UnmatchedReason.FILE_REMOVED),
				JavaStructuralRepresentation.NORMALIZATION_VERSION));
		assertThrows(IllegalArgumentException.class, () -> new EntityFact(
				ChangeKind.REMOVED, keepFact.before(), Optional.empty(), Set.of(),
				MatchBasis.UNMATCHED_BEFORE, Optional.of(UnmatchedReason.FILE_ADDED),
				JavaStructuralRepresentation.NORMALIZATION_VERSION));

		var sampleDiag = new DiffDiagnostic(SideRole.BEFORE, DiagnosticCode.PARSE_FAILED, Severity.ERROR,
				"parse error", Optional.of(before), Optional.of("A.java"), Optional.empty(), Optional.empty());
		// COMPLETE vs fullCoverage inconsistency.
		assertThrows(IllegalArgumentException.class, () -> new StructuralDiffResult(
				ProcessingStatus.COMPLETE, Intent.REVISION_COMPARISON, Optional.of("repo"),
				JavaStructuralRepresentation.NORMALIZATION_VERSION, JavaStructuralRepresentation.PARSER_VERSION,
				JavaStructuralRepresentation.LANGUAGE_POLICY, baseline.beforeSnapshot(), baseline.afterSnapshot(),
				List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
				baseline.fileComparison(), new CoverageSummary(false, List.of("gap")), List.of(sampleDiag)));
		// COMPLETE initial assessment missing initialInventory.
		assertThrows(IllegalArgumentException.class, () -> new StructuralDiffResult(
				ProcessingStatus.COMPLETE, Intent.INITIAL_ASSESSMENT, Optional.of("repo"),
				JavaStructuralRepresentation.NORMALIZATION_VERSION, JavaStructuralRepresentation.PARSER_VERSION,
				JavaStructuralRepresentation.LANGUAGE_POLICY, Optional.empty(), baseline.afterSnapshot(),
				List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
				new FileComparison(PresenceRelation.INITIAL_TARGET, PathRelation.SINGLE_SIDE,
						Optional.empty(), Optional.of("Concurrent.java"), StructuralEquality.UNKNOWN,
						Set.of(), Set.of(), false, List.of()),
				new CoverageSummary(true, List.of()), List.of()));

		// Defensive copies and immutability on result models.
		assertThrows(UnsupportedOperationException.class, () -> baseline.entityFacts().clear());
		assertThrows(UnsupportedOperationException.class,
				() -> baseline.fileComparison().contextChanges().clear());
		assertThrows(UnsupportedOperationException.class, () -> changeFact.changedFacets().clear());
		assertThrows(UnsupportedOperationException.class,
				() -> changeFact.after().orElseThrow().facetFingerprints().clear());
		assertThrows(UnsupportedOperationException.class,
				() -> changeFact.after().orElseThrow().facetReferences().get(FacetKind.DECLARATION).clear());

		var mutableFacets = new EnumMap<FacetKind, Fingerprint>(FacetKind.class);
		var declFp = changeFact.after().orElseThrow().facetFingerprints().get(FacetKind.DECLARATION);
		mutableFacets.put(FacetKind.DECLARATION, declFp);
		var mutableRefList = new ArrayList<>(
				changeFact.after().orElseThrow().facetReferences().get(FacetKind.DECLARATION));
		var mutableFacetRefs = new EnumMap<FacetKind, List<ProvenanceRef>>(FacetKind.class);
		mutableFacetRefs.put(FacetKind.DECLARATION, mutableRefList);
		var entitySide = new EntitySide(after, "Concurrent.java", changeFact.after().orElseThrow().key(),
				changeFact.after().orElseThrow().declarationRef(),
				changeFact.after().orElseThrow().headerRef(),
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
				mutableFacetRefs, mutableFacets);
		mutableFacets.clear();
		mutableRefList.clear();
		mutableFacetRefs.clear();
		assertEquals(1, entitySide.facetFingerprints().size());
		assertEquals(1, entitySide.facetReferences().get(FacetKind.DECLARATION).size());

		// RV-16: Incompatible normalization versions across sides fail closed with IllegalArgumentException.
		var mismatchDiff = new StructuralDiff(source -> {
			var normal = JavaStructuralRepresentation.parse(new JavaSourceParser(), source);
			if (source.sourceContent().contains("VERSION_V2")) {
				var rep = normal.representation().orElseThrow();
				var v2Rep = new JavaStructuralRepresentation(
						rep.filePath(), "m2-ast-v2", rep.parserVersion(), rep.languagePolicy(),
						rep.parsedSource(), rep.fileContext(), rep.entities(), rep.fieldGroups(),
						rep.initializerBlocks(), rep.residualRegions(), rep.topLevelLayout(),
						rep.typeLayouts(), rep.moduleLayout(),
						Fingerprint.computeWithVersion("m2-ast-v2", "FILE:STRUCTURAL_TOKENS",
								rep.wholeFileFingerprint().canonicalTokens()),
						rep.rawFacts());
				return new ParseOutcome(normal.parseResult(), Optional.of(v2Rep));
			}
			return normal;
		});
		assertThrows(IllegalArgumentException.class, () -> mismatchDiff.compare(revision("repo",
				new Present(before, new SourceInput("V.java", "class V {}")),
				new Present(after, new SourceInput("V.java", "class V { /* VERSION_V2 */ }")))));
	}

	private static StructuralDiffInput revision(String repo, Side before, Side after) {
		return new StructuralDiffInput(repo, Intent.REVISION_COMPARISON, Optional.of(before), after, Optional.empty());
	}

	private static EntityFact findFact(StructuralDiffResult result, Kind kind, String name) {
		return result.entityFacts().stream()
				.filter(fact -> {
					var side = fact.after().or(fact::before).orElseThrow();
					return side.key().kind() == kind && side.key().name().equals(name);
				})
				.findFirst()
				.orElseThrow();
	}

	private static void assertInvalidInput(StructuralDiffResult result) {
		assertEquals(ProcessingStatus.FAILED, result.status(), () -> result.diagnostics().toString());
		assertFalse(result.coverage().fullCoverage());
		assertTrue(result.beforeSnapshot().isEmpty());
		assertTrue(result.afterSnapshot().isEmpty());
		assertTrue(result.entityFacts().isEmpty());
		assertTrue(result.initialInventory().isEmpty());
		assertTrue(result.diagnostics().stream().allMatch(d -> d.code() == DiagnosticCode.INVALID_INPUT),
				() -> result.diagnostics().toString());
	}

	private static void assertReferenceMatchesSource(String source, JavaParseResult.SourceReference reference) {
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
