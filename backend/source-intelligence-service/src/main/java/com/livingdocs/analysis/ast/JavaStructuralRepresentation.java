package com.livingdocs.analysis.ast;

import static com.livingdocs.analysis.ast.JavaParseResult.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.modules.ModuleDeclaration;

/**
 * Immutable, AST-free structural projection of a single Java compilation unit.
 * Built in one parse pass via {@link JavaSourceParser}'s package-private bridge.
 */
public record JavaStructuralRepresentation(
		String filePath,
		String normalizationVersion,
		String parserVersion,
		String languagePolicy,
		ParsedSource parsedSource,
		FileContextProjection fileContext,
		List<EntityProjection> entities,
		List<FieldDeclarationGroup> fieldGroups,
		List<InitializerBlockProjection> initializerBlocks,
		List<ResidualRegion> residualRegions,
		ScopeLayout topLevelLayout,
		Map<SymbolKey, ScopeLayout> typeLayouts,
		Optional<ScopeLayout> moduleLayout,
		Fingerprint wholeFileFingerprint,
		RawSourceFacts rawFacts) {

	public static final String NORMALIZATION_VERSION = "m2-ast-v1";
	public static final String PARSER_VERSION = "javaparser-core:3.28.2";
	public static final String LANGUAGE_POLICY = "JAVA_21_NON_PREVIEW";

	public JavaStructuralRepresentation {
		Objects.requireNonNull(filePath, "filePath");
		if (Objects.requireNonNull(normalizationVersion, "normalizationVersion").isBlank()) {
			throw new IllegalArgumentException("Normalization version cannot be blank");
		}
		if (Objects.requireNonNull(parserVersion, "parserVersion").isBlank()) {
			throw new IllegalArgumentException("Parser version cannot be blank");
		}
		if (Objects.requireNonNull(languagePolicy, "languagePolicy").isBlank()) {
			throw new IllegalArgumentException("Language policy cannot be blank");
		}
		Objects.requireNonNull(parsedSource, "parsedSource");
		Objects.requireNonNull(fileContext, "fileContext");
		entities = List.copyOf(entities);
		fieldGroups = List.copyOf(fieldGroups);
		initializerBlocks = List.copyOf(initializerBlocks);
		residualRegions = List.copyOf(residualRegions);
		Objects.requireNonNull(topLevelLayout, "topLevelLayout");
		typeLayouts = copyTypeLayouts(typeLayouts);
		Objects.requireNonNull(moduleLayout, "moduleLayout");
		Objects.requireNonNull(wholeFileFingerprint, "wholeFileFingerprint");
		Objects.requireNonNull(rawFacts, "rawFacts");
	}

	public static ParseOutcome parse(JavaSourceParser parser, SourceInput input) {
		Objects.requireNonNull(parser, "parser");
		var bridged = parser.parseAndProject(input, JavaStructuralRepresentation::project);
		return new ParseOutcome(bridged.parseResult(), bridged.projection());
	}

	static ParseOutcome extractAndProject(JavaSourceParser parser, SourceInput input, CompilationUnit unit) {
		Objects.requireNonNull(parser, "parser");
		Objects.requireNonNull(input, "input");
		Objects.requireNonNull(unit, "unit");
		var bridged = parser.extractAndProject(input, unit, JavaStructuralRepresentation::project);
		return new ParseOutcome(bridged.parseResult(), bridged.projection());
	}

	public record ParseOutcome(JavaParseResult parseResult, Optional<JavaStructuralRepresentation> representation) {
		public ParseOutcome {
			Objects.requireNonNull(parseResult, "parseResult");
			Objects.requireNonNull(representation, "representation");
			boolean expectsProjection = parseResult.status() == Status.SUCCESS;
			if (representation.isPresent() != expectsProjection) {
				throw new IllegalArgumentException("Representation presence must match SUCCESS parse status");
			}
		}
	}

	public enum FacetKind {
		DECLARATION, BODY, INITIALIZER, MEMBER_ORDER
	}

	public record Fingerprint(String normalizationVersion, String domainTag, String sha256,
			List<String> canonicalTokens) {

		public Fingerprint {
			if (Objects.requireNonNull(normalizationVersion, "normalizationVersion").isBlank()) {
				throw new IllegalArgumentException("Normalization version cannot be blank");
			}
			if (Objects.requireNonNull(domainTag, "domainTag").isBlank()) {
				throw new IllegalArgumentException("Domain tag cannot be blank");
			}
			if (Objects.requireNonNull(sha256, "sha256").length() != 64 || !isLowerHex(sha256)) {
				throw new IllegalArgumentException("Expected a 64-character lowercase hex SHA-256 digest");
			}
			canonicalTokens = List.copyOf(canonicalTokens);
		}

		public static Fingerprint compute(String domainTag, List<String> canonicalTokens) {
			return computeWithVersion(NORMALIZATION_VERSION, domainTag, canonicalTokens);
		}

		public static Fingerprint computeWithVersion(String normalizationVersion, String domainTag,
				List<String> canonicalTokens) {
			if (Objects.requireNonNull(normalizationVersion, "normalizationVersion").isBlank()) {
				throw new IllegalArgumentException("Normalization version cannot be blank");
			}
			if (Objects.requireNonNull(domainTag, "domainTag").isBlank()) {
				throw new IllegalArgumentException("Domain tag cannot be blank");
			}
			var copied = List.copyOf(canonicalTokens);
			var buffer = new ByteArrayOutputStream();
			writeLengthPrefixedUtf8(buffer, normalizationVersion);
			writeLengthPrefixedUtf8(buffer, domainTag);
			writeInt32(buffer, copied.size());
			for (var token : copied) {
				writeLengthPrefixedUtf8(buffer, Objects.requireNonNull(token, "token"));
			}
			return new Fingerprint(normalizationVersion, domainTag, sha256Hex(buffer.toByteArray()), copied);
		}

		public boolean isCompatibleWith(Fingerprint other) {
			Objects.requireNonNull(other, "other");
			return normalizationVersion.equals(other.normalizationVersion);
		}

		public boolean matchesContent(Fingerprint other) {
			Objects.requireNonNull(other, "other");
			if (!isCompatibleWith(other)) {
				throw new IllegalArgumentException("Incompatible normalization versions: "
						+ normalizationVersion + " vs " + other.normalizationVersion);
			}
			return sha256.equals(other.sha256)
					&& domainTag.equals(other.domainTag)
					&& canonicalTokens.equals(other.canonicalTokens);
		}

		private static boolean isLowerHex(String value) {
			for (int i = 0; i < value.length(); i++) {
				char ch = value.charAt(i);
				if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) {
					return false;
				}
			}
			return true;
		}
	}

	public record ProvenanceRef(Optional<SourceReference> reference, Optional<String> unavailableReason) {
		public ProvenanceRef {
			Objects.requireNonNull(reference, "reference");
			Objects.requireNonNull(unavailableReason, "unavailableReason");
			if (reference.isPresent() == unavailableReason.isPresent()) {
				throw new IllegalArgumentException(
						"ProvenanceRef requires either an exact SourceReference or an unavailable reason");
			}
			if (unavailableReason.isPresent() && unavailableReason.orElseThrow().isBlank()) {
				throw new IllegalArgumentException("Unavailable provenance reason cannot be blank");
			}
		}

		public static ProvenanceRef of(SourceReference reference) {
			return new ProvenanceRef(Optional.of(Objects.requireNonNull(reference, "reference")), Optional.empty());
		}

		public static ProvenanceRef unavailable(String reason) {
			return new ProvenanceRef(Optional.empty(), Optional.of(Objects.requireNonNull(reason, "reason")));
		}
	}

	public record EntityProjection(
			SourceEntity entity,
			Map<FacetKind, Fingerprint> facets,
			Map<FacetKind, List<ProvenanceRef>> facetReferences,
			ProvenanceRef declarationRef,
			ProvenanceRef headerRef,
			Optional<ProvenanceRef> sharedDeclarationRef,
			Optional<ProvenanceRef> sharedPrefixRef,
			Optional<ProvenanceRef> bodyRef,
			Optional<ProvenanceRef> initializerRef,
			Optional<ProvenanceRef> annotationDefaultRef) {

		public EntityProjection {
			Objects.requireNonNull(entity, "entity");
			facets = copyFacets(facets);
			facetReferences = copyFacetReferences(facetReferences);
			if (!facets.containsKey(FacetKind.DECLARATION)) {
				throw new IllegalArgumentException("Every entity projection requires a DECLARATION facet");
			}
			if (!facets.keySet().equals(facetReferences.keySet())) {
				throw new IllegalArgumentException("Facet references must match present facet kinds");
			}
			Objects.requireNonNull(declarationRef, "declarationRef");
			Objects.requireNonNull(headerRef, "headerRef");
			Objects.requireNonNull(sharedDeclarationRef, "sharedDeclarationRef");
			Objects.requireNonNull(sharedPrefixRef, "sharedPrefixRef");
			Objects.requireNonNull(bodyRef, "bodyRef");
			Objects.requireNonNull(initializerRef, "initializerRef");
			Objects.requireNonNull(annotationDefaultRef, "annotationDefaultRef");
			if (facets.containsKey(FacetKind.BODY) != bodyRef.isPresent()) {
				throw new IllegalArgumentException("BODY facet and body provenance must match");
			}
			if (facets.containsKey(FacetKind.INITIALIZER) != initializerRef.isPresent()) {
				throw new IllegalArgumentException("INITIALIZER facet and initializer provenance must match");
			}
			boolean isField = entity.key().kind() == Kind.FIELD;
			if (sharedDeclarationRef.isPresent() != isField || sharedPrefixRef.isPresent() != isField) {
				throw new IllegalArgumentException("Shared field references apply exclusively to FIELD entities");
			}
		}

		public SymbolKey key() {
			return entity.key();
		}

		public Fingerprint declarationFacet() {
			return facets.get(FacetKind.DECLARATION);
		}

		public Optional<Fingerprint> bodyFacet() {
			return Optional.ofNullable(facets.get(FacetKind.BODY));
		}

		public Optional<Fingerprint> initializerFacet() {
			return Optional.ofNullable(facets.get(FacetKind.INITIALIZER));
		}

		public Optional<Fingerprint> memberOrderFacet() {
			return Optional.ofNullable(facets.get(FacetKind.MEMBER_ORDER));
		}
	}

	public record FieldDeclarationGroup(
			SymbolKey enclosingType,
			int indexInType,
			List<SymbolKey> fieldKeys,
			Fingerprint statementFacet,
			ProvenanceRef sharedPrefixRef,
			ProvenanceRef provenance) {

		public FieldDeclarationGroup {
			Objects.requireNonNull(enclosingType, "enclosingType");
			if (indexInType < 0) {
				throw new IllegalArgumentException("Field group index cannot be negative");
			}
			fieldKeys = List.copyOf(fieldKeys);
			if (fieldKeys.isEmpty()) {
				throw new IllegalArgumentException("Field declaration group requires at least one field key");
			}
			Objects.requireNonNull(statementFacet, "statementFacet");
			Objects.requireNonNull(sharedPrefixRef, "sharedPrefixRef");
			Objects.requireNonNull(provenance, "provenance");
		}
	}

	public record PackageProjection(String name, List<AnnotationUsage> annotationUsages, Fingerprint facet,
			ProvenanceRef provenance) {
		public PackageProjection {
			Objects.requireNonNull(name, "name");
			annotationUsages = List.copyOf(annotationUsages);
			Objects.requireNonNull(facet, "facet");
			Objects.requireNonNull(provenance, "provenance");
		}
	}

	public record ImportProjection(ImportDeclaration declaration, Fingerprint facet, ProvenanceRef provenance) {
		public ImportProjection {
			Objects.requireNonNull(declaration, "declaration");
			Objects.requireNonNull(facet, "facet");
			Objects.requireNonNull(provenance, "provenance");
		}
	}

	public record ModuleDirectiveProjection(Fingerprint facet, ProvenanceRef provenance) {
		public ModuleDirectiveProjection {
			Objects.requireNonNull(facet, "facet");
			Objects.requireNonNull(provenance, "provenance");
		}
	}

	public record ModuleProjection(String name, boolean open, List<AnnotationUsage> annotationUsages,
			Fingerprint headerFacet, List<ModuleDirectiveProjection> directives, ProvenanceRef provenance) {
		public ModuleProjection {
			Objects.requireNonNull(name, "name");
			annotationUsages = List.copyOf(annotationUsages);
			Objects.requireNonNull(headerFacet, "headerFacet");
			directives = List.copyOf(directives);
			Objects.requireNonNull(provenance, "provenance");
		}
	}

	public record FileContextProjection(Optional<PackageProjection> packageDecl, List<ImportProjection> imports,
			Optional<ModuleProjection> module) {
		public FileContextProjection {
			Objects.requireNonNull(packageDecl, "packageDecl");
			imports = List.copyOf(imports);
			Objects.requireNonNull(module, "module");
		}
	}

	public record InitializerBlockProjection(SymbolKey enclosingType, int indexInType, boolean isStatic,
			Fingerprint facet, ProvenanceRef provenance) {
		public InitializerBlockProjection {
			Objects.requireNonNull(enclosingType, "enclosingType");
			if (indexInType < 0) {
				throw new IllegalArgumentException("Initializer index cannot be negative");
			}
			Objects.requireNonNull(facet, "facet");
			Objects.requireNonNull(provenance, "provenance");
		}
	}

	public record ResidualRegion(Optional<SymbolKey> enclosingType, Optional<String> moduleName, int indexInScope,
			Fingerprint facet, ProvenanceRef provenance) {
		public ResidualRegion {
			Objects.requireNonNull(enclosingType, "enclosingType");
			Objects.requireNonNull(moduleName, "moduleName");
			if (enclosingType.isPresent() && moduleName.isPresent()) {
				throw new IllegalArgumentException("Residual region cannot belong to both a type and a module");
			}
			if (indexInScope < 0) {
				throw new IllegalArgumentException("Residual index cannot be negative");
			}
			Objects.requireNonNull(facet, "facet");
			Objects.requireNonNull(provenance, "provenance");
		}
	}

	public sealed interface ScopeElement permits ScopeElement.PackageElement, ScopeElement.ImportElement,
			ScopeElement.ModuleElement, ScopeElement.ModuleDirectiveElement, ScopeElement.EntityElement,
			ScopeElement.InitializerElement, ScopeElement.ResidualElement {

		record PackageElement() implements ScopeElement {
		}

		record ImportElement(int importIndex) implements ScopeElement {
			public ImportElement {
				if (importIndex < 0) {
					throw new IllegalArgumentException("Import index cannot be negative");
				}
			}
		}

		record ModuleElement() implements ScopeElement {
		}

		record ModuleDirectiveElement(int directiveIndex) implements ScopeElement {
			public ModuleDirectiveElement {
				if (directiveIndex < 0) {
					throw new IllegalArgumentException("Directive index cannot be negative");
				}
			}
		}

		record EntityElement(SymbolKey key) implements ScopeElement {
			public EntityElement {
				Objects.requireNonNull(key, "key");
			}
		}

		record InitializerElement(int initializerIndex, boolean isStatic) implements ScopeElement {
			public InitializerElement {
				if (initializerIndex < 0) {
					throw new IllegalArgumentException("Initializer index cannot be negative");
				}
			}
		}

		record ResidualElement(int residualIndex) implements ScopeElement {
			public ResidualElement {
				if (residualIndex < 0) {
					throw new IllegalArgumentException("Residual index cannot be negative");
				}
			}
		}
	}

	public record ScopeLayout(Optional<SymbolKey> enclosingType, List<ScopeElement> elements, Fingerprint orderFacet) {
		public ScopeLayout {
			Objects.requireNonNull(enclosingType, "enclosingType");
			elements = List.copyOf(elements);
			Objects.requireNonNull(orderFacet, "orderFacet");
		}
	}

	public enum CommentKind {
		LINE, BLOCK, JAVADOC
	}

	public record CommentEntry(CommentKind kind, int precedingStructuralTokens, String text, ProvenanceRef provenance) {
		public CommentEntry {
			Objects.requireNonNull(kind, "kind");
			if (precedingStructuralTokens < 0) {
				throw new IllegalArgumentException("Preceding structural token count cannot be negative");
			}
			Objects.requireNonNull(text, "text");
			Objects.requireNonNull(provenance, "provenance");
		}
	}

	public record RawSourceFacts(String rawSha256, Fingerprint whitespaceFingerprint, Fingerprint commentsFingerprint,
			List<CommentEntry> comments) {
		public RawSourceFacts {
			if (Objects.requireNonNull(rawSha256, "rawSha256").length() != 64) {
				throw new IllegalArgumentException("Expected a 64-character SHA-256 hex digest");
			}
			Objects.requireNonNull(whitespaceFingerprint, "whitespaceFingerprint");
			Objects.requireNonNull(commentsFingerprint, "commentsFingerprint");
			comments = List.copyOf(comments);
		}

		public boolean hasSameRawContent(RawSourceFacts other) {
			Objects.requireNonNull(other, "other");
			return rawSha256.equals(other.rawSha256);
		}

		public boolean hasSameWhitespace(RawSourceFacts other) {
			Objects.requireNonNull(other, "other");
			return whitespaceFingerprint.matchesContent(other.whitespaceFingerprint);
		}

		public boolean hasSameComments(RawSourceFacts other) {
			Objects.requireNonNull(other, "other");
			return commentsFingerprint.matchesContent(other.commentsFingerprint);
		}
	}

	public static String canonicalSymbolKey(SymbolKey key) {
		Objects.requireNonNull(key, "key");
		var builder = new StringBuilder();
		builder.append(key.kind().name()).append('|');
		builder.append(key.packageName().orElse("")).append('|');
		builder.append(String.join(".", key.ownerTypes())).append('|');
		builder.append(key.name()).append('(');
		for (int i = 0; i < key.parameterTypes().size(); i++) {
			if (i > 0) {
				builder.append(',');
			}
			var param = key.parameterTypes().get(i);
			builder.append(String.join(" ", param.tokens()));
			if (param.varargs()) {
				builder.append("...");
			}
		}
		builder.append(')');
		return builder.toString();
	}

	private static JavaStructuralRepresentation project(SourceInput input, CompilationUnit unit,
			ParsedSource parsedSource, List<String> lines) {
		var tokenTable = TokenTable.build(unit, lines);
		var entityByLocator = indexEntities(parsedSource.entities());
		var state = new ProjectionState(tokenTable, lines, parsedSource, entityByLocator);

		var topLevelPositioned = new ArrayList<PositionedElement>();
		Optional<PackageProjection> packageProjection = Optional.empty();
		if (unit.getPackageDeclaration().isPresent()) {
			var pkgNode = unit.getPackageDeclaration().orElseThrow();
			var span = tokenTable.spanOf(pkgNode, "package declaration");
			tokenTable.markCovered(span.start, span.end);
			var tokens = tokenTable.structuralTokens(span.start, span.end);
			packageProjection = Optional.of(new PackageProjection(
					pkgNode.getNameAsString(),
					parsedSource.fileContext().packageAnnotationUsages(),
					Fingerprint.compute("CONTEXT:PACKAGE", tokens),
					tokenTable.nodeProvenance(pkgNode, span, "package declaration")));
			topLevelPositioned.add(new PositionedElement(span.start, 0, new ScopeElement.PackageElement()));
		}

		var importProjections = new ArrayList<ImportProjection>();
		var astImports = unit.getImports();
		for (int i = 0; i < astImports.size(); i++) {
			var impNode = astImports.get(i);
			var span = tokenTable.spanOf(impNode, "import declaration");
			tokenTable.markCovered(span.start, span.end);
			var tokens = tokenTable.structuralTokens(span.start, span.end);
			importProjections.add(new ImportProjection(
					parsedSource.imports().get(i),
					Fingerprint.compute("CONTEXT:IMPORT", tokens),
					tokenTable.nodeProvenance(impNode, span, "import declaration")));
			topLevelPositioned.add(new PositionedElement(span.start, i, new ScopeElement.ImportElement(i)));
		}

		Optional<ModuleProjection> moduleProjection = Optional.empty();
		Optional<ScopeLayout> moduleLayout = Optional.empty();
		if (unit.getModule().isPresent()) {
			var modNode = unit.getModule().orElseThrow();
			var modContext = parsedSource.fileContext().module().orElseThrow();
			var modResult = projectModule(modNode, modContext, state);
			moduleProjection = Optional.of(modResult.projection());
			moduleLayout = Optional.of(modResult.layout());
			topLevelPositioned.add(new PositionedElement(modResult.startToken(), 0, new ScopeElement.ModuleElement()));
		}

		int orderTie = 0;
		for (var typeDecl : unit.getTypes()) {
			var typeStart = projectType(typeDecl, state);
			topLevelPositioned.add(new PositionedElement(typeStart.startToken(), orderTie++,
					new ScopeElement.EntityElement(typeStart.key())));
		}

		int topResidualScopeIndex = 0;
		for (var span : tokenTable.collectUncoveredStructuralSpans(0, tokenTable.size() - 1)) {
			tokenTable.markCovered(span.start, span.end);
			int residualGlobalIndex = state.residualRegions.size();
			var tokens = tokenTable.structuralTokens(span.start, span.end);
			state.residualRegions.add(new ResidualRegion(
					Optional.empty(),
					Optional.empty(),
					topResidualScopeIndex++,
					Fingerprint.compute("RESIDUAL:FILE", tokens),
					tokenTable.spanProvenance(span.start, span.end, "top-level residual region")));
			topLevelPositioned.add(new PositionedElement(span.start, orderTie++,
					new ScopeElement.ResidualElement(residualGlobalIndex)));
		}

		var topLevelLayout = buildScopeLayout("SCOPE_ORDER:FILE", Optional.empty(), topLevelPositioned, state);

		var orderedEntityProjections = new ArrayList<EntityProjection>(parsedSource.entities().size());
		for (var entity : parsedSource.entities()) {
			var projected = state.entityProjections.get(entity.key());
			if (projected == null) {
				throw projectionFailure("Missing structural projection for entity " + entity.key());
			}
			orderedEntityProjections.add(projected);
		}

		var wholeFileFingerprint = Fingerprint.compute("FILE:STRUCTURAL_TOKENS", tokenTable.allStructuralTokens());
		var rawFacts = buildRawFacts(input.sourceContent(), tokenTable);

		return new JavaStructuralRepresentation(
				input.filePath(),
				NORMALIZATION_VERSION,
				PARSER_VERSION,
				LANGUAGE_POLICY,
				parsedSource,
				new FileContextProjection(packageProjection, importProjections, moduleProjection),
				orderedEntityProjections,
				state.fieldGroups,
				state.initializerBlocks,
				state.residualRegions,
				topLevelLayout,
				state.typeLayouts,
				moduleLayout,
				wholeFileFingerprint,
				rawFacts);
	}

	private static ModuleResult projectModule(ModuleDeclaration modNode, ModuleContext modContext,
			ProjectionState state) {
		var tokenTable = state.tokenTable;
		var modSpan = tokenTable.spanOf(modNode, "module declaration");
		var braces = tokenTable.findTrailingBraceBody(modSpan.start, modSpan.end, "module body");
		var headerTokens = tokenTable.structuralTokens(modSpan.start, braces.openIdx - 1);
		tokenTable.markCovered(modSpan.start, braces.openIdx);
		tokenTable.markCovered(braces.closeIdx, modSpan.end);

		var directiveProjections = new ArrayList<ModuleDirectiveProjection>();
		var moduleElements = new ArrayList<PositionedElement>();
		var directives = modNode.getDirectives();
		for (int i = 0; i < directives.size(); i++) {
			var directive = directives.get(i);
			var dSpan = tokenTable.spanOf(directive, "module directive");
			tokenTable.markCovered(dSpan.start, dSpan.end);
			var tokens = tokenTable.structuralTokens(dSpan.start, dSpan.end);
			directiveProjections.add(new ModuleDirectiveProjection(
					Fingerprint.compute("CONTEXT:MODULE_DIRECTIVE", tokens),
					tokenTable.nodeProvenance(directive, dSpan, "module directive")));
			moduleElements.add(new PositionedElement(dSpan.start, i, new ScopeElement.ModuleDirectiveElement(i)));
		}

		int modResidualScopeIndex = 0;
		int tie = directives.size();
		for (var span : tokenTable.collectUncoveredStructuralSpans(braces.openIdx + 1, braces.closeIdx - 1)) {
			tokenTable.markCovered(span.start, span.end);
			int residualGlobalIndex = state.residualRegions.size();
			var tokens = tokenTable.structuralTokens(span.start, span.end);
			state.residualRegions.add(new ResidualRegion(
					Optional.empty(),
					Optional.of(modContext.name()),
					modResidualScopeIndex++,
					Fingerprint.compute("RESIDUAL:MODULE", tokens),
					tokenTable.spanProvenance(span.start, span.end, "module residual region")));
			moduleElements.add(new PositionedElement(span.start, tie++,
					new ScopeElement.ResidualElement(residualGlobalIndex)));
		}

		var projection = new ModuleProjection(
				modContext.name(),
				modContext.open(),
				modContext.annotationUsages(),
				Fingerprint.compute("CONTEXT:MODULE_HEADER", headerTokens),
				directiveProjections,
				tokenTable.nodeProvenance(modNode, modSpan, "module declaration"));
		var layout = buildScopeLayout("SCOPE_ORDER:MODULE", Optional.empty(), moduleElements, state);
		return new ModuleResult(modSpan.start, projection, layout);
	}

	private static TypeStart projectType(TypeDeclaration<?> type, ProjectionState state) {
		var tokenTable = state.tokenTable;
		Kind kind = resolveTypeKind(type);
		var typeEntity = state.lookupEntity(kind, type.getNameAsString(), type);
		var typeSpan = tokenTable.spanOf(type, "type declaration " + type.getNameAsString());
		var braces = tokenTable.findTrailingBraceBody(typeSpan.start, typeSpan.end,
				"type body for " + type.getNameAsString());

		var headerTokens = tokenTable.structuralTokens(typeSpan.start, braces.openIdx - 1);
		tokenTable.markCovered(typeSpan.start, braces.openIdx);
		tokenTable.markCovered(braces.closeIdx, typeSpan.end);

		var typeElements = new ArrayList<PositionedElement>();
		int tie = 0;

		if (type instanceof RecordDeclaration record) {
			for (var component : record.getParameters()) {
				var compEntity = state.lookupEntity(Kind.RECORD_COMPONENT, component.getNameAsString(), component);
				var compSpan = tokenTable.spanOf(component, "record component " + component.getNameAsString());
				var compTokens = tokenTable.structuralTokens(compSpan.start, compSpan.end);
				var compRef = tokenTable.nodeProvenance(component, compSpan, "record component");
				var compFacets = new EnumMap<FacetKind, Fingerprint>(FacetKind.class);
				compFacets.put(FacetKind.DECLARATION,
						Fingerprint.compute("FACET:DECLARATION:RECORD_COMPONENT", compTokens));
				var compRefs = new EnumMap<FacetKind, List<ProvenanceRef>>(FacetKind.class);
				compRefs.put(FacetKind.DECLARATION, List.of(compRef));
				state.recordEntity(new EntityProjection(compEntity, compFacets, compRefs, compRef, compRef,
						Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
				typeElements.add(new PositionedElement(compSpan.start, tie++,
						new ScopeElement.EntityElement(compEntity.key())));
			}
		}

		if (type instanceof EnumDeclaration enumDecl) {
			var entries = enumDecl.getEntries();
			Span prevConstSpan = null;
			for (int i = 0; i < entries.size(); i++) {
				var constant = entries.get(i);
				var constEntity = state.lookupEntity(Kind.ENUM_CONSTANT, constant.getNameAsString(), constant);
				var constSpan = tokenTable.spanOf(constant, "enum constant " + constant.getNameAsString());
				if (prevConstSpan != null) {
					tokenTable.markFirstDelimiterCovered(prevConstSpan.end + 1, constSpan.start - 1, ",");
				}
				projectEnumConstant(constant, constEntity, constSpan, state);
				typeElements.add(new PositionedElement(constSpan.start, tie++,
						new ScopeElement.EntityElement(constEntity.key())));
				prevConstSpan = constSpan;
			}
			if (!enumDecl.getMembers().isEmpty()) {
				int searchStart = prevConstSpan != null ? prevConstSpan.end + 1 : braces.openIdx + 1;
				int firstMemberStart = tokenTable.spanOf(enumDecl.getMembers().get(0), "enum member").start;
				tokenTable.markFirstDelimiterCovered(searchStart, firstMemberStart - 1, ";");
			}
		}

		int typeInitIndex = 0;
		int typeFieldGroupIndex = 0;
		for (var member : type.getMembers()) {
			if (member instanceof TypeDeclaration<?> nested) {
				var nestedStart = projectType(nested, state);
				typeElements.add(new PositionedElement(nestedStart.startToken(), tie++,
						new ScopeElement.EntityElement(nestedStart.key())));
			} else if (member instanceof FieldDeclaration field) {
				tie = projectFieldDeclaration(field, typeEntity.key(), typeFieldGroupIndex++,
						state, typeElements, tie);
			} else if (member instanceof MethodDeclaration method) {
				var methodEntity = state.lookupEntity(Kind.METHOD, method.getNameAsString(), method);
				var mSpan = tokenTable.spanOf(method, "method " + method.getNameAsString());
				projectMethod(method, methodEntity, mSpan, state);
				typeElements.add(new PositionedElement(mSpan.start, tie++,
						new ScopeElement.EntityElement(methodEntity.key())));
			} else if (member instanceof ConstructorDeclaration constructor) {
				var ctorEntity = state.lookupEntity(Kind.CONSTRUCTOR, constructor.getNameAsString(), constructor);
				var cSpan = tokenTable.spanOf(constructor, "constructor " + constructor.getNameAsString());
				projectConstructor(constructor, ctorEntity, cSpan, state);
				typeElements.add(new PositionedElement(cSpan.start, tie++,
						new ScopeElement.EntityElement(ctorEntity.key())));
			} else if (member instanceof CompactConstructorDeclaration compact
					&& type instanceof RecordDeclaration record) {
				var ctorEntity = state.lookupEntity(Kind.CONSTRUCTOR, compact.getNameAsString(), compact);
				var cSpan = tokenTable.spanOf(compact, "compact constructor " + compact.getNameAsString());
				projectCompactConstructor(compact, record, ctorEntity, cSpan, state);
				typeElements.add(new PositionedElement(cSpan.start, tie++,
						new ScopeElement.EntityElement(ctorEntity.key())));
			} else if (member instanceof AnnotationMemberDeclaration annotationMember) {
				var amEntity = state.lookupEntity(Kind.ANNOTATION_MEMBER, annotationMember.getNameAsString(),
						annotationMember);
				var amSpan = tokenTable.spanOf(annotationMember,
						"annotation member " + annotationMember.getNameAsString());
				projectAnnotationMember(annotationMember, amEntity, amSpan, state);
				typeElements.add(new PositionedElement(amSpan.start, tie++,
						new ScopeElement.EntityElement(amEntity.key())));
			} else if (member instanceof InitializerDeclaration initializer) {
				var iSpan = tokenTable.spanOf(initializer, "initializer block in " + type.getNameAsString());
				tokenTable.markCovered(iSpan.start, iSpan.end);
				var tokens = tokenTable.structuralTokens(iSpan.start, iSpan.end);
				int globalInitIdx = state.initializerBlocks.size();
				String domain = initializer.isStatic()
						? "CONTEXT:STATIC_INITIALIZER" : "CONTEXT:INSTANCE_INITIALIZER";
				state.initializerBlocks.add(new InitializerBlockProjection(
						typeEntity.key(),
						typeInitIndex++,
						initializer.isStatic(),
						Fingerprint.compute(domain, tokens),
						tokenTable.nodeProvenance(initializer, iSpan, "initializer block")));
				typeElements.add(new PositionedElement(iSpan.start, tie++,
						new ScopeElement.InitializerElement(globalInitIdx, initializer.isStatic())));
			} else {
				throw projectionFailure("Unsupported member in projection: " + member.getMetaModel().getTypeName());
			}
		}

		int typeResidualIndex = 0;
		for (var span : tokenTable.collectUncoveredStructuralSpans(braces.openIdx + 1, braces.closeIdx - 1)) {
			tokenTable.markCovered(span.start, span.end);
			int globalResidualIdx = state.residualRegions.size();
			var tokens = tokenTable.structuralTokens(span.start, span.end);
			state.residualRegions.add(new ResidualRegion(
					Optional.of(typeEntity.key()),
					Optional.empty(),
					typeResidualIndex++,
					Fingerprint.compute("RESIDUAL:TYPE", tokens),
					tokenTable.spanProvenance(span.start, span.end, "type residual region")));
			typeElements.add(new PositionedElement(span.start, tie++,
					new ScopeElement.ResidualElement(globalResidualIdx)));
		}

		var declRef = tokenTable.nodeProvenance(type, typeSpan, "type declaration");
		var headerRef = tokenTable.spanProvenance(typeSpan.start, braces.openIdx - 1, "type header");

		var typeDetails = (TypeDetails) typeEntity.details();
		var memberOrderTokens = typeDetails.members().stream()
				.map(JavaStructuralRepresentation::canonicalSymbolKey)
				.toList();
		var memberOrderRefs = new ArrayList<ProvenanceRef>();
		for (var memberKey : typeDetails.members()) {
			var memberProj = state.entityProjections.get(memberKey);
			if (memberProj != null) {
				memberOrderRefs.add(memberProj.declarationRef());
			}
		}
		if (memberOrderRefs.isEmpty()) {
			memberOrderRefs.add(headerRef);
		}

		var typeFacets = new EnumMap<FacetKind, Fingerprint>(FacetKind.class);
		typeFacets.put(FacetKind.DECLARATION,
				Fingerprint.compute("FACET:DECLARATION:" + kind.name(), headerTokens));
		typeFacets.put(FacetKind.MEMBER_ORDER,
				Fingerprint.compute("FACET:MEMBER_ORDER:" + kind.name(), memberOrderTokens));

		var typeFacetRefs = new EnumMap<FacetKind, List<ProvenanceRef>>(FacetKind.class);
		typeFacetRefs.put(FacetKind.DECLARATION, List.of(headerRef));
		typeFacetRefs.put(FacetKind.MEMBER_ORDER, memberOrderRefs);

		state.recordEntity(new EntityProjection(typeEntity, typeFacets, typeFacetRefs, declRef, headerRef,
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));

		var typeLayout = buildScopeLayout("SCOPE_ORDER:" + kind.name(), Optional.of(typeEntity.key()),
				typeElements, state);
		state.typeLayouts.put(typeEntity.key(), typeLayout);
		return new TypeStart(typeSpan.start, typeEntity.key());
	}

	private static void projectEnumConstant(EnumConstantDeclaration constant, SourceEntity entity, Span constSpan,
			ProjectionState state) {
		var tokenTable = state.tokenTable;
		tokenTable.markCovered(constSpan.start, constSpan.end);
		var facets = new EnumMap<FacetKind, Fingerprint>(FacetKind.class);
		var facetRefs = new EnumMap<FacetKind, List<ProvenanceRef>>(FacetKind.class);
		var declRef = tokenTable.nodeProvenance(constant, constSpan, "enum constant");

		int lastStructural = tokenTable.lastStructuralIndex(constSpan.start, constSpan.end);
		if (lastStructural >= constSpan.start && tokenTable.tokenText(lastStructural).equals("}")) {
			var braces = tokenTable.findTrailingBraceBody(constSpan.start, constSpan.end, "enum constant body");
			var headerTokens = tokenTable.structuralTokens(constSpan.start, braces.openIdx - 1);
			var bodyTokens = tokenTable.structuralTokens(braces.openIdx, braces.closeIdx);
			var headerRef = tokenTable.spanProvenance(constSpan.start, braces.openIdx - 1, "enum constant header");
			var bodyRef = tokenTable.spanProvenance(braces.openIdx, braces.closeIdx, "enum constant body");
			facets.put(FacetKind.DECLARATION, Fingerprint.compute("FACET:DECLARATION:ENUM_CONSTANT", headerTokens));
			facets.put(FacetKind.BODY, Fingerprint.compute("FACET:BODY:ENUM_CONSTANT", bodyTokens));
			facetRefs.put(FacetKind.DECLARATION, List.of(headerRef));
			facetRefs.put(FacetKind.BODY, List.of(bodyRef));
			state.recordEntity(new EntityProjection(entity, facets, facetRefs, declRef, headerRef,
					Optional.empty(), Optional.empty(), Optional.of(bodyRef), Optional.empty(), Optional.empty()));
		} else {
			var headerTokens = tokenTable.structuralTokens(constSpan.start, constSpan.end);
			facets.put(FacetKind.DECLARATION, Fingerprint.compute("FACET:DECLARATION:ENUM_CONSTANT", headerTokens));
			facetRefs.put(FacetKind.DECLARATION, List.of(declRef));
			state.recordEntity(new EntityProjection(entity, facets, facetRefs, declRef, declRef,
					Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
		}
	}

	private static int projectFieldDeclaration(FieldDeclaration field, SymbolKey enclosingType, int groupIndexInType,
			ProjectionState state, List<PositionedElement> typeElements, int tie) {
		var tokenTable = state.tokenTable;
		var fieldSpan = tokenTable.spanOf(field, "field declaration");
		tokenTable.markCovered(fieldSpan.start, fieldSpan.end);

		var variables = field.getVariables();
		if (variables.isEmpty()) {
			throw projectionFailure("Field declaration contains no variable declarators");
		}
		var firstVarSpan = tokenTable.spanOf(variables.get(0), "field variable");
		var sharedPrefixTokens = tokenTable.structuralTokens(fieldSpan.start, firstVarSpan.start - 1);
		var sharedDeclarationRef = tokenTable.nodeProvenance(field, fieldSpan, "field shared declaration");
		var sharedPrefixRef = tokenTable.spanProvenance(fieldSpan.start, firstVarSpan.start - 1,
				"field shared prefix");

		var groupFieldKeys = new ArrayList<SymbolKey>(variables.size());
		for (VariableDeclarator variable : variables) {
			var fieldEntity = state.lookupEntity(Kind.FIELD, variable.getNameAsString(), variable);
			groupFieldKeys.add(fieldEntity.key());
			var varSpan = tokenTable.spanOf(variable, "field variable " + variable.getNameAsString());
			var facets = new EnumMap<FacetKind, Fingerprint>(FacetKind.class);
			var facetRefs = new EnumMap<FacetKind, List<ProvenanceRef>>(FacetKind.class);
			var declRef = tokenTable.nodeProvenance(variable, varSpan, "field declarator");

			if (variable.getInitializer().isPresent()) {
				var initNode = variable.getInitializer().orElseThrow();
				var initSpan = tokenTable.spanOf(initNode, "field initializer " + variable.getNameAsString());
				int assignIdx = tokenTable.lastStructuralIndex(varSpan.start, initSpan.start - 1);
				if (assignIdx < varSpan.start || !tokenTable.tokenText(assignIdx).equals("=")) {
					throw projectionFailure("Missing '=' before initializer for field " + variable.getNameAsString());
				}
				var declaratorHeaderTokens = tokenTable.structuralTokens(varSpan.start, assignIdx - 1);
				var fullDeclTokens = concat(sharedPrefixTokens, declaratorHeaderTokens);
				var initTokens = tokenTable.structuralTokens(initSpan.start, initSpan.end);
				var headerRef = tokenTable.spanProvenance(varSpan.start, assignIdx - 1, "field declarator header");
				var initRef = tokenTable.nodeProvenance(initNode, initSpan, "field initializer");
				facets.put(FacetKind.DECLARATION, Fingerprint.compute("FACET:DECLARATION:FIELD", fullDeclTokens));
				facets.put(FacetKind.INITIALIZER, Fingerprint.compute("FACET:INITIALIZER:FIELD", initTokens));
				facetRefs.put(FacetKind.DECLARATION, List.of(sharedPrefixRef, headerRef));
				facetRefs.put(FacetKind.INITIALIZER, List.of(initRef));
				state.recordEntity(new EntityProjection(fieldEntity, facets, facetRefs, declRef, headerRef,
						Optional.of(sharedDeclarationRef), Optional.of(sharedPrefixRef),
						Optional.empty(), Optional.of(initRef), Optional.empty()));
			} else {
				var declaratorHeaderTokens = tokenTable.structuralTokens(varSpan.start, varSpan.end);
				var fullDeclTokens = concat(sharedPrefixTokens, declaratorHeaderTokens);
				facets.put(FacetKind.DECLARATION, Fingerprint.compute("FACET:DECLARATION:FIELD", fullDeclTokens));
				facetRefs.put(FacetKind.DECLARATION, List.of(sharedPrefixRef, declRef));
				state.recordEntity(new EntityProjection(fieldEntity, facets, facetRefs, declRef, declRef,
						Optional.of(sharedDeclarationRef), Optional.of(sharedPrefixRef),
						Optional.empty(), Optional.empty(), Optional.empty()));
			}
			typeElements.add(new PositionedElement(varSpan.start, tie++,
					new ScopeElement.EntityElement(fieldEntity.key())));
		}

		var statementTokens = tokenTable.structuralTokens(fieldSpan.start, fieldSpan.end);
		state.fieldGroups.add(new FieldDeclarationGroup(
				enclosingType,
				groupIndexInType,
				groupFieldKeys,
				Fingerprint.compute("CONTEXT:FIELD_DECLARATION_GROUP", statementTokens),
				sharedPrefixRef,
				sharedDeclarationRef));
		return tie;
	}

	private static void projectMethod(MethodDeclaration method, SourceEntity entity, Span mSpan,
			ProjectionState state) {
		var tokenTable = state.tokenTable;
		tokenTable.markCovered(mSpan.start, mSpan.end);
		var facets = new EnumMap<FacetKind, Fingerprint>(FacetKind.class);
		var facetRefs = new EnumMap<FacetKind, List<ProvenanceRef>>(FacetKind.class);
		var declRef = tokenTable.nodeProvenance(method, mSpan, "method declaration");

		if (method.getBody().isPresent()) {
			var bodyNode = method.getBody().orElseThrow();
			var bodySpan = tokenTable.spanOf(bodyNode, "method body " + method.getNameAsString());
			var headerTokens = tokenTable.structuralTokens(mSpan.start, bodySpan.start - 1);
			var bodyTokens = tokenTable.structuralTokens(bodySpan.start, bodySpan.end);
			var headerRef = tokenTable.spanProvenance(mSpan.start, bodySpan.start - 1, "method header");
			var bodyRef = tokenTable.nodeProvenance(bodyNode, bodySpan, "method body");
			facets.put(FacetKind.DECLARATION, Fingerprint.compute("FACET:DECLARATION:METHOD", headerTokens));
			facets.put(FacetKind.BODY, Fingerprint.compute("FACET:BODY:METHOD", bodyTokens));
			facetRefs.put(FacetKind.DECLARATION, List.of(headerRef));
			facetRefs.put(FacetKind.BODY, List.of(bodyRef));
			state.recordEntity(new EntityProjection(entity, facets, facetRefs, declRef, headerRef,
					Optional.empty(), Optional.empty(), Optional.of(bodyRef), Optional.empty(), Optional.empty()));
		} else {
			int headerEnd = stripTrailingSemicolon(tokenTable, mSpan.start, mSpan.end);
			var headerTokens = tokenTable.structuralTokens(mSpan.start, headerEnd);
			var headerRef = tokenTable.spanProvenance(mSpan.start, headerEnd, "method header");
			facets.put(FacetKind.DECLARATION, Fingerprint.compute("FACET:DECLARATION:METHOD", headerTokens));
			facetRefs.put(FacetKind.DECLARATION, List.of(headerRef));
			state.recordEntity(new EntityProjection(entity, facets, facetRefs, declRef, headerRef,
					Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
		}
	}

	private static void projectConstructor(ConstructorDeclaration constructor, SourceEntity entity, Span cSpan,
			ProjectionState state) {
		var tokenTable = state.tokenTable;
		tokenTable.markCovered(cSpan.start, cSpan.end);
		var bodyNode = constructor.getBody();
		var bodySpan = tokenTable.spanOf(bodyNode, "constructor body " + constructor.getNameAsString());
		var headerTokens = tokenTable.structuralTokens(cSpan.start, bodySpan.start - 1);
		var bodyTokens = tokenTable.structuralTokens(bodySpan.start, bodySpan.end);

		var declRef = tokenTable.nodeProvenance(constructor, cSpan, "constructor declaration");
		var headerRef = tokenTable.spanProvenance(cSpan.start, bodySpan.start - 1, "constructor header");
		var bodyRef = tokenTable.nodeProvenance(bodyNode, bodySpan, "constructor body");

		var facets = new EnumMap<FacetKind, Fingerprint>(FacetKind.class);
		facets.put(FacetKind.DECLARATION, Fingerprint.compute("FACET:DECLARATION:CONSTRUCTOR", headerTokens));
		facets.put(FacetKind.BODY, Fingerprint.compute("FACET:BODY:CONSTRUCTOR", bodyTokens));

		var facetRefs = new EnumMap<FacetKind, List<ProvenanceRef>>(FacetKind.class);
		facetRefs.put(FacetKind.DECLARATION, List.of(headerRef));
		facetRefs.put(FacetKind.BODY, List.of(bodyRef));

		state.recordEntity(new EntityProjection(entity, facets, facetRefs, declRef, headerRef,
				Optional.empty(), Optional.empty(), Optional.of(bodyRef), Optional.empty(), Optional.empty()));
	}

	private static void projectCompactConstructor(CompactConstructorDeclaration compact, RecordDeclaration record,
			SourceEntity entity, Span cSpan, ProjectionState state) {
		var tokenTable = state.tokenTable;
		tokenTable.markCovered(cSpan.start, cSpan.end);
		var bodyNode = compact.getBody();
		var bodySpan = tokenTable.spanOf(bodyNode, "compact constructor body " + compact.getNameAsString());

		var declRef = tokenTable.nodeProvenance(compact, cSpan, "compact constructor declaration");
		var headerRef = tokenTable.spanProvenance(cSpan.start, bodySpan.start - 1, "compact constructor header");
		var bodyRef = tokenTable.nodeProvenance(bodyNode, bodySpan, "compact constructor body");

		var headerTokens = new ArrayList<>(tokenTable.structuralTokens(cSpan.start, bodySpan.start - 1));
		headerTokens.add("<COMPACT_PARAMS>");
		var declarationRefs = new ArrayList<ProvenanceRef>();
		declarationRefs.add(headerRef);

		var recordParams = record.getParameters();
		for (int i = 0; i < recordParams.size(); i++) {
			if (i > 0) {
				headerTokens.add(",");
			}
			Parameter param = recordParams.get(i);
			var pSpan = tokenTable.spanOf(param, "record parameter " + param.getNameAsString());
			var paramTokens = tokenTable.structuralTokens(pSpan.start, pSpan.end);
			headerTokens.addAll(paramTokens);
			if (!paramTokens.isEmpty()) {
				declarationRefs.add(tokenTable.nodeProvenance(param, pSpan,
						"record component parameter " + param.getNameAsString()));
			}
		}

		var bodyTokens = tokenTable.structuralTokens(bodySpan.start, bodySpan.end);
		var facets = new EnumMap<FacetKind, Fingerprint>(FacetKind.class);
		facets.put(FacetKind.DECLARATION, Fingerprint.compute("FACET:DECLARATION:CONSTRUCTOR", headerTokens));
		facets.put(FacetKind.BODY, Fingerprint.compute("FACET:BODY:CONSTRUCTOR", bodyTokens));

		var facetRefs = new EnumMap<FacetKind, List<ProvenanceRef>>(FacetKind.class);
		facetRefs.put(FacetKind.DECLARATION, declarationRefs);
		facetRefs.put(FacetKind.BODY, List.of(bodyRef));

		state.recordEntity(new EntityProjection(entity, facets, facetRefs, declRef, headerRef,
				Optional.empty(), Optional.empty(), Optional.of(bodyRef), Optional.empty(), Optional.empty()));
	}

	private static void projectAnnotationMember(AnnotationMemberDeclaration annotationMember, SourceEntity entity,
			Span amSpan, ProjectionState state) {
		var tokenTable = state.tokenTable;
		tokenTable.markCovered(amSpan.start, amSpan.end);
		int headerEnd = stripTrailingSemicolon(tokenTable, amSpan.start, amSpan.end);
		var headerTokens = tokenTable.structuralTokens(amSpan.start, headerEnd);

		var declRef = tokenTable.nodeProvenance(annotationMember, amSpan, "annotation member declaration");
		var headerRef = tokenTable.spanProvenance(amSpan.start, headerEnd, "annotation member header");

		var facets = new EnumMap<FacetKind, Fingerprint>(FacetKind.class);
		facets.put(FacetKind.DECLARATION, Fingerprint.compute("FACET:DECLARATION:ANNOTATION_MEMBER", headerTokens));

		var facetRefs = new EnumMap<FacetKind, List<ProvenanceRef>>(FacetKind.class);
		Optional<ProvenanceRef> defaultRef = Optional.empty();
		if (annotationMember.getDefaultValue().isPresent()) {
			var defaultNode = annotationMember.getDefaultValue().orElseThrow();
			var dSpan = tokenTable.spanOf(defaultNode, "annotation member default value");
			var dRef = tokenTable.nodeProvenance(defaultNode, dSpan, "annotation member default value");
			defaultRef = Optional.of(dRef);
			int beforeDefaultEnd = findDefaultKeywordStart(tokenTable, amSpan.start, dSpan.start - 1);
			var memberSigRef = beforeDefaultEnd >= amSpan.start
					? tokenTable.spanProvenance(amSpan.start, beforeDefaultEnd, "annotation member signature")
					: headerRef;
			facetRefs.put(FacetKind.DECLARATION, List.of(memberSigRef, dRef));
		} else {
			facetRefs.put(FacetKind.DECLARATION, List.of(headerRef));
		}

		state.recordEntity(new EntityProjection(entity, facets, facetRefs, declRef, headerRef,
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), defaultRef));
	}

	private static int findDefaultKeywordStart(TokenTable tokenTable, int start, int end) {
		int defaultIdx = tokenTable.lastStructuralIndex(start, end);
		if (defaultIdx >= start && tokenTable.tokenText(defaultIdx).equals("default")) {
			return defaultIdx - 1;
		}
		return end;
	}

	private static ScopeLayout buildScopeLayout(String domainTag, Optional<SymbolKey> enclosingType,
			List<PositionedElement> positioned, ProjectionState state) {
		var sorted = new ArrayList<>(positioned);
		sorted.sort(Comparator.comparingInt(PositionedElement::startToken)
				.thenComparingInt(PositionedElement::tieBreaker));
		var elements = sorted.stream().map(PositionedElement::element).toList();
		var descriptors = new ArrayList<String>(elements.size());
		for (var element : elements) {
			if (element instanceof ScopeElement.PackageElement) {
				descriptors.add("PACKAGE");
			} else if (element instanceof ScopeElement.ImportElement imp) {
				descriptors.add("IMPORT:" + imp.importIndex());
			} else if (element instanceof ScopeElement.ModuleElement) {
				descriptors.add("MODULE");
			} else if (element instanceof ScopeElement.ModuleDirectiveElement dir) {
				descriptors.add("DIRECTIVE:" + dir.directiveIndex());
			} else if (element instanceof ScopeElement.EntityElement ent) {
				descriptors.add("ENTITY:" + canonicalSymbolKey(ent.key()));
			} else if (element instanceof ScopeElement.InitializerElement init) {
				descriptors.add(init.isStatic() ? "INIT:STATIC" : "INIT:INSTANCE");
			} else if (element instanceof ScopeElement.ResidualElement res) {
				var region = state.residualRegions.get(res.residualIndex());
				descriptors.add("RESIDUAL:" + region.facet().sha256());
			}
		}
		return new ScopeLayout(enclosingType, elements, Fingerprint.compute(domainTag, descriptors));
	}

	private static RawSourceFacts buildRawFacts(String rawContent, TokenTable tokenTable) {
		var rawSha256 = sha256Hex(rawContent.getBytes(StandardCharsets.UTF_8));
		var whitespaceFp = Fingerprint.compute("RAW:WHITESPACE", tokenTable.whitespaceDescriptors());
		var comments = tokenTable.comments();
		var commentDescriptors = new ArrayList<String>(comments.size());
		for (var comment : comments) {
			commentDescriptors.add(comment.kind().name() + "@" + comment.precedingStructuralTokens()
					+ ":" + comment.text());
		}
		var commentsFp = Fingerprint.compute("RAW:COMMENTS", commentDescriptors);
		return new RawSourceFacts(rawSha256, whitespaceFp, commentsFp, comments);
	}

	private static int stripTrailingSemicolon(TokenTable tokenTable, int start, int end) {
		int last = tokenTable.lastStructuralIndex(start, end);
		if (last >= start && tokenTable.tokenText(last).equals(";")) {
			return last - 1;
		}
		return end;
	}

	private static Kind resolveTypeKind(TypeDeclaration<?> type) {
		if (type instanceof ClassOrInterfaceDeclaration decl) {
			return decl.isInterface() ? Kind.INTERFACE : Kind.CLASS;
		}
		if (type instanceof EnumDeclaration) {
			return Kind.ENUM;
		}
		if (type instanceof RecordDeclaration) {
			return Kind.RECORD;
		}
		if (type instanceof AnnotationDeclaration) {
			return Kind.ANNOTATION;
		}
		throw projectionFailure("Unsupported type declaration: " + type.getMetaModel().getTypeName());
	}

	private static Map<EntityLocator, SourceEntity> indexEntities(List<SourceEntity> entities) {
		var map = new HashMap<EntityLocator, SourceEntity>(entities.size() * 2);
		for (var entity : entities) {
			var locator = new EntityLocator(entity.key().kind(), entity.key().name(), entity.declarationRange());
			if (map.put(locator, entity) != null) {
				throw projectionFailure("Ambiguous entity locator for " + entity.key());
			}
		}
		return map;
	}

	private static List<String> concat(List<String> first, List<String> second) {
		var combined = new ArrayList<String>(first.size() + second.size());
		combined.addAll(first);
		combined.addAll(second);
		return combined;
	}

	private static Map<FacetKind, Fingerprint> copyFacets(Map<FacetKind, Fingerprint> facets) {
		Objects.requireNonNull(facets, "facets");
		if (facets.isEmpty()) {
			return Map.of();
		}
		var copy = new EnumMap<FacetKind, Fingerprint>(FacetKind.class);
		for (var entry : facets.entrySet()) {
			copy.put(Objects.requireNonNull(entry.getKey(), "facetKind"),
					Objects.requireNonNull(entry.getValue(), "fingerprint"));
		}
		return Collections.unmodifiableMap(copy);
	}

	private static Map<FacetKind, List<ProvenanceRef>> copyFacetReferences(
			Map<FacetKind, List<ProvenanceRef>> references) {
		Objects.requireNonNull(references, "facetReferences");
		if (references.isEmpty()) {
			return Map.of();
		}
		var copy = new EnumMap<FacetKind, List<ProvenanceRef>>(FacetKind.class);
		for (var entry : references.entrySet()) {
			var kind = Objects.requireNonNull(entry.getKey(), "facetKind");
			var list = List.copyOf(Objects.requireNonNull(entry.getValue(), "provenanceList"));
			if (list.isEmpty()) {
				throw new IllegalArgumentException("Facet reference list cannot be empty for " + kind);
			}
			copy.put(kind, list);
		}
		return Collections.unmodifiableMap(copy);
	}

	private static Map<SymbolKey, ScopeLayout> copyTypeLayouts(Map<SymbolKey, ScopeLayout> layouts) {
		Objects.requireNonNull(layouts, "typeLayouts");
		if (layouts.isEmpty()) {
			return Map.of();
		}
		var copy = new LinkedHashMap<SymbolKey, ScopeLayout>(layouts.size());
		for (var entry : layouts.entrySet()) {
			copy.put(Objects.requireNonNull(entry.getKey(), "typeKey"),
					Objects.requireNonNull(entry.getValue(), "scopeLayout"));
		}
		return Collections.unmodifiableMap(copy);
	}

	private static void writeLengthPrefixedUtf8(ByteArrayOutputStream buffer, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		writeInt32(buffer, bytes.length);
		buffer.write(bytes, 0, bytes.length);
	}

	private static void writeInt32(ByteArrayOutputStream buffer, int value) {
		buffer.write((value >>> 24) & 0xFF);
		buffer.write((value >>> 16) & 0xFF);
		buffer.write((value >>> 8) & 0xFF);
		buffer.write(value & 0xFF);
	}

	private static String sha256Hex(byte[] bytes) {
		try {
			var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
		}
	}

	private static JavaSourceParser.ProjectionFailure projectionFailure(String message) {
		return new JavaSourceParser.ProjectionFailure(DiagnosticCode.EXTRACTION_FAILURE, message);
	}

	private record EntityLocator(Kind kind, String name, SourceRange declarationRange) {
	}

	private record Span(int start, int end) {
	}

	private record BraceBody(int openIdx, int closeIdx) {
	}

	private record PositionedElement(int startToken, int tieBreaker, ScopeElement element) {
	}

	private record TypeStart(int startToken, SymbolKey key) {
	}

	private record ModuleResult(int startToken, ModuleProjection projection, ScopeLayout layout) {
	}

	private static final class ProjectionState {
		final TokenTable tokenTable;
		final List<String> lines;
		final ParsedSource parsedSource;
		final Map<EntityLocator, SourceEntity> entityByLocator;
		final Map<SymbolKey, EntityProjection> entityProjections = new LinkedHashMap<>();
		final List<FieldDeclarationGroup> fieldGroups = new ArrayList<>();
		final List<InitializerBlockProjection> initializerBlocks = new ArrayList<>();
		final List<ResidualRegion> residualRegions = new ArrayList<>();
		final Map<SymbolKey, ScopeLayout> typeLayouts = new LinkedHashMap<>();

		ProjectionState(TokenTable tokenTable, List<String> lines, ParsedSource parsedSource,
				Map<EntityLocator, SourceEntity> entityByLocator) {
			this.tokenTable = tokenTable;
			this.lines = lines;
			this.parsedSource = parsedSource;
			this.entityByLocator = entityByLocator;
		}

		SourceEntity lookupEntity(Kind kind, String name, Node node) {
			var range = node.getRange().flatMap(r -> JavaDeclarationExtractor.sourceRange(r, lines))
					.orElseThrow(() -> new JavaSourceParser.ProjectionFailure(
							DiagnosticCode.SOURCE_RANGE_UNAVAILABLE,
							"Missing source range for " + kind + " " + name));
			var entity = entityByLocator.get(new EntityLocator(kind, name, range));
			if (entity == null) {
				throw projectionFailure("Unable to correlate AST node to extracted entity " + kind + " " + name);
			}
			return entity;
		}

		void recordEntity(EntityProjection projection) {
			if (entityProjections.put(projection.key(), projection) != null) {
				throw projectionFailure("Duplicate entity projection recorded for " + projection.key());
			}
		}
	}

	private static final class TokenTable {
		private final List<JavaToken> tokens;
		private final IdentityHashMap<JavaToken, Integer> indexByToken;
		private final boolean[] structural;
		private final boolean[] covered;
		private final List<CommentEntry> comments;
		private final List<String> whitespaceDescriptors;
		private final List<String> allStructuralTokens;
		private final List<String> lines;

		private TokenTable(List<JavaToken> tokens, IdentityHashMap<JavaToken, Integer> indexByToken,
				boolean[] structural, List<CommentEntry> comments, List<String> whitespaceDescriptors,
				List<String> allStructuralTokens, List<String> lines) {
			this.tokens = tokens;
			this.indexByToken = indexByToken;
			this.structural = structural;
			this.covered = new boolean[tokens.size()];
			this.comments = comments;
			this.whitespaceDescriptors = whitespaceDescriptors;
			this.allStructuralTokens = allStructuralTokens;
			this.lines = lines;
		}

		static TokenTable build(CompilationUnit unit, List<String> lines) {
			var tokens = new ArrayList<JavaToken>();
			var indexByToken = new IdentityHashMap<JavaToken, Integer>();
			if (unit.getTokenRange().isPresent()) {
				for (var token : unit.getTokenRange().orElseThrow()) {
					if (token.getKind() != JavaToken.Kind.EOF.getKind()) {
						indexByToken.put(token, tokens.size());
						tokens.add(token);
					}
				}
			}
			var structural = new boolean[tokens.size()];
			var comments = new ArrayList<CommentEntry>();
			var whitespaceDescriptors = new ArrayList<String>();
			var allStructuralTokens = new ArrayList<String>();
			int structuralCount = 0;
			int commentCount = 0;
			var pendingWhitespace = new StringBuilder();
			for (int i = 0; i < tokens.size(); i++) {
				var token = tokens.get(i);
				if (token.getCategory().isComment()) {
					if (!pendingWhitespace.isEmpty()) {
						whitespaceDescriptors.add(structuralCount + ":" + commentCount + ":" + pendingWhitespace);
						pendingWhitespace.setLength(0);
					}
					commentCount++;
					comments.add(new CommentEntry(classifyComment(token), structuralCount, token.getText(),
							singleTokenProvenance(token, lines, "comment")));
				} else if (token.getCategory().isWhitespace()) {
					pendingWhitespace.append(token.getText());
				} else {
					if (!pendingWhitespace.isEmpty()) {
						whitespaceDescriptors.add(structuralCount + ":" + commentCount + ":" + pendingWhitespace);
						pendingWhitespace.setLength(0);
					}
					structural[i] = true;
					structuralCount++;
					allStructuralTokens.add(token.getText());
				}
			}
			if (!pendingWhitespace.isEmpty()) {
				whitespaceDescriptors.add(structuralCount + ":" + commentCount + ":" + pendingWhitespace);
			}
			return new TokenTable(tokens, indexByToken, structural, comments, whitespaceDescriptors,
					allStructuralTokens, lines);
		}

		int size() {
			return tokens.size();
		}

		String tokenText(int index) {
			return tokens.get(index).getText();
		}

		List<String> allStructuralTokens() {
			return allStructuralTokens;
		}

		List<CommentEntry> comments() {
			return comments;
		}

		List<String> whitespaceDescriptors() {
			return whitespaceDescriptors;
		}

		Span spanOf(Node node, String construct) {
			var range = node.getTokenRange()
					.orElseThrow(() -> projectionFailure("Missing token range for " + construct));
			Integer start = indexByToken.get(range.getBegin());
			Integer end = indexByToken.get(range.getEnd());
			if (start == null || end == null || start > end) {
				throw projectionFailure("Unindexed token range for " + construct);
			}
			return new Span(start, end);
		}

		List<String> structuralTokens(int start, int end) {
			if (start > end || tokens.isEmpty()) {
				return List.of();
			}
			var list = new ArrayList<String>();
			int from = Math.max(0, start);
			int to = Math.min(tokens.size() - 1, end);
			for (int i = from; i <= to; i++) {
				if (structural[i]) {
					list.add(tokens.get(i).getText());
				}
			}
			return list;
		}

		int lastStructuralIndex(int start, int end) {
			int to = Math.min(tokens.size() - 1, end);
			int from = Math.max(0, start);
			for (int i = to; i >= from; i--) {
				if (structural[i]) {
					return i;
				}
			}
			return -1;
		}

		BraceBody findTrailingBraceBody(int start, int end, String construct) {
			int closeIdx = lastStructuralIndex(start, end);
			if (closeIdx < start || !tokens.get(closeIdx).getText().equals("}")) {
				throw projectionFailure("Missing closing '}' for " + construct);
			}
			int depth = 0;
			for (int i = closeIdx; i >= start; i--) {
				if (!structural[i]) {
					continue;
				}
				String text = tokens.get(i).getText();
				if (text.equals("}")) {
					depth++;
				} else if (text.equals("{") && --depth == 0) {
					return new BraceBody(i, closeIdx);
				}
			}
			throw projectionFailure("Missing matching '{' for " + construct);
		}

		void markCovered(int start, int end) {
			if (start > end || tokens.isEmpty()) {
				return;
			}
			int from = Math.max(0, start);
			int to = Math.min(tokens.size() - 1, end);
			for (int i = from; i <= to; i++) {
				covered[i] = true;
			}
		}

		void markFirstDelimiterCovered(int start, int end, String delimiter) {
			if (start > end || tokens.isEmpty()) {
				return;
			}
			int from = Math.max(0, start);
			int to = Math.min(tokens.size() - 1, end);
			for (int i = from; i <= to; i++) {
				if (structural[i] && tokens.get(i).getText().equals(delimiter)) {
					covered[i] = true;
					return;
				}
			}
		}

		List<Span> collectUncoveredStructuralSpans(int start, int end) {
			if (start > end || tokens.isEmpty()) {
				return List.of();
			}
			var spans = new ArrayList<Span>();
			int from = Math.max(0, start);
			int to = Math.min(tokens.size() - 1, end);
			int spanStart = -1;
			int spanEnd = -1;
			for (int i = from; i <= to; i++) {
				if (covered[i]) {
					if (spanStart != -1) {
						spans.add(new Span(spanStart, spanEnd));
						spanStart = -1;
					}
				} else if (structural[i]) {
					if (spanStart == -1) {
						spanStart = i;
					}
					spanEnd = i;
				}
			}
			if (spanStart != -1) {
				spans.add(new Span(spanStart, spanEnd));
			}
			return spans;
		}

		ProvenanceRef nodeProvenance(Node node, Span fallbackSpan, String construct) {
			var nodeRange = node.getRange().flatMap(range -> JavaDeclarationExtractor.sourceRange(range, lines));
			var slice = node.getTokenRange().map(Object::toString);
			if (nodeRange.isPresent() && slice.isPresent()) {
				return ProvenanceRef.of(new SourceReference(slice.orElseThrow(), nodeRange.orElseThrow()));
			}
			return ProvenanceRef.unavailable("Exact source range unavailable for " + construct);
		}

		ProvenanceRef spanProvenance(int start, int end, String construct) {
			int firstIdx = -1;
			int lastIdx = -1;
			int from = Math.max(0, start);
			int to = Math.min(tokens.size() - 1, end);
			for (int i = from; i <= to; i++) {
				if (structural[i]) {
					if (firstIdx == -1) {
						firstIdx = i;
					}
					lastIdx = i;
				}
			}
			if (firstIdx == -1) {
				return ProvenanceRef.unavailable("No structural tokens available for " + construct);
			}
			var firstToken = tokens.get(firstIdx);
			var lastToken = tokens.get(lastIdx);
			if (firstToken.getRange().isEmpty() || lastToken.getRange().isEmpty()) {
				return ProvenanceRef.unavailable("Exact token range unavailable for " + construct);
			}
			var combined = new Range(firstToken.getRange().orElseThrow().begin,
					lastToken.getRange().orElseThrow().end);
			var resolvedRange = JavaDeclarationExtractor.sourceRange(combined, lines);
			if (resolvedRange.isEmpty()) {
				return ProvenanceRef.unavailable("Out-of-bounds source range for " + construct);
			}
			String slice = new TokenRange(firstToken, lastToken).toString();
			return ProvenanceRef.of(new SourceReference(slice, resolvedRange.orElseThrow()));
		}

		private static ProvenanceRef singleTokenProvenance(JavaToken token, List<String> lines, String construct) {
			if (token.getRange().isEmpty()) {
				return ProvenanceRef.unavailable("Missing token range for " + construct);
			}
			var resolved = JavaDeclarationExtractor.sourceRange(token.getRange().orElseThrow(), lines);
			if (resolved.isEmpty()) {
				return ProvenanceRef.unavailable("Out-of-bounds token range for " + construct);
			}
			return ProvenanceRef.of(new SourceReference(token.getText(), resolved.orElseThrow()));
		}

		private static CommentKind classifyComment(JavaToken token) {
			String text = token.getText();
			if (text.startsWith("/**") && !text.equals("/**/")) {
				return CommentKind.JAVADOC;
			}
			if (text.startsWith("/*")) {
				return CommentKind.BLOCK;
			}
			return CommentKind.LINE;
		}
	}
}
