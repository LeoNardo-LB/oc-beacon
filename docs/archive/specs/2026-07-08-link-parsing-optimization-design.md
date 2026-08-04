# Link Parsing Optimization Design

**Date**: 2026-07-08
**Status**: Approved (pending spec review)
**Author**: Brainstorming session

## Problem Statement

Two bugs share a single root cause — `LinkClassifier.isLikelyFilePath()` uses a permissive regex (`.\w{1,10}$`) that matches any word-like suffix as a "file extension":

1. **Package name false positive**: `com.example.foo` is detected as a file path because `.foo` matches the regex. Users tap it expecting nothing, but the file viewer opens and errors.

2. **Limited scope**: File path detection only works inside `paragraph` component's `CODE_SPAN` nodes. Tables, headings, and list items with inline code like `` `config.yaml` `` are not clickable. Furthermore, `SimpleMarkdownTable` doesn't support even standard `[text](url)` markdown links.

## Solution: Whitelist + Unified Annotator Extension (Method B)

### Approach

Replace the permissive regex with a **whitelist of known file extensions + special filenames**. Extract the paragraph component's inline link-detection logic (~60 lines) into a **reusable utility** that all Markdown components can call. No original Markdown text is modified — all detection happens at the rendering layer.

---

## Design Details

### 1. LinkClassifier Whitelist Refactor

**File**: `domain/model/LinkClassifier.kt`

Replace `isLikelyFilePath()` implementation:

```kotlin
object LinkClassifier {
    // ~100+ common programming/config/doc extensions
    val FILE_EXTENSIONS: Set<String> = setOf(
        // Languages
        "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "mjs", "cjs",
        "go", "rs", "c", "cpp", "cc", "cxx", "h", "hpp", "hxx", "cs", "rb",
        "php", "swift", "m", "mm", "scala", "clj", "cljs", "ex", "exs",
        "erl", "hs", "lua", "pl", "pm", "r", "dart", "vue", "svelte",
        // JVM/Build
        "gradle", "groovy", "xml", "properties", "toml", "sbt", "mill",
        // Web/Config
        "html", "htm", "css", "scss", "sass", "less", "json", "json5",
        "yaml", "yml", "ini", "cfg", "conf", "env",
        // Docs
        "md", "mdx", "rst", "txt", "adoc", "tex", "pdf",
        // Data
        "csv", "tsv", "sql", "db", "sqlite",
        // Shell
        "sh", "bash", "zsh", "fish", "bat", "ps1", "cmd",
        // Other
        "dockerfile", "gitignore", "gitattributes", "editorconfig",
        "lock", "log", "diff", "patch",
    )

    // Extensionless special filenames
    val SPECIAL_FILENAMES: Set<String> = setOf(
        "Makefile", "makefile", "GNUmakefile",
        "Dockerfile", "Containerfile",
        "LICENSE", "LICENSE.md", "LICENSE.txt",
        "README", "CHANGELOG", "AUTHORS", "CONTRIBUTING",
        ".gitignore", ".gitattributes", ".editorconfig",
        ".env", ".env.local", ".env.production",
        ".npmrc", ".nvmrc", ".ruby-version",
        "Jenkinsfile", "Vagrantfile", "Gemfile", "Rakefile",
        "WORKSPACE", "BUILD", "BUILD.bazel",
    )

    private val fileExtensionRegex = Regex("\\.([A-Za-z0-9]+)$")

    fun isLikelyFilePath(text: String): Boolean {
        // Path separator → always treat as path (package names use '.', not '/')
        if (text.contains('/') || text.contains('\\')) return true
        // Check extension whitelist
        val extMatch = fileExtensionRegex.find(text)
        if (extMatch != null) {
            return extMatch.groupValues[1].lowercase() in FILE_EXTENSIONS
        }
        // Check special filename whitelist (case-insensitive for non-dotted names)
        return text in SPECIAL_FILENAMES || text.lowercase() in SPECIAL_FILENAMES
    }
}
```

**Behavior changes**:

| Input | Old | New | Reason |
|-------|-----|-----|--------|
| `com.example.foo` | true | **false** | `.foo` not in whitelist |
| `org.springframework.boot` | true | **false** | `.boot` not in whitelist |
| `1.0.0-beta` | true | **false** | `.0` not in whitelist |
| `Foo.kt` | true | true | `.kt` in whitelist |
| `Makefile` | false | **true** | In SPECIAL_FILENAMES |
| `src/Foo.kt` | true | true | Has path separator |
| `unknown.xyz` | true | **false** | `.xyz` not in whitelist |
| `path/to/unknown` | true | true | Has path separator |

**`classify()` function**: Unchanged — it handles post-click classification, not detection.

---

### 2. Unified Clickable Markdown Utility

**New file**: `ui/screens/chat/markdown/ClickableMarkdown.kt`

Extract the paragraph component's link-detection logic into reusable APIs:

```kotlin
internal data class ClickableMarkdownResult(
    val annotatedString: AnnotatedString,
    val items: List<ClickableItem>,
)

internal sealed interface ClickableItem {
    val text: String
    data class Link(override val text: String, val url: String) : ClickableItem
    data class CodePath(override val text: String) : ClickableItem
}

/**
 * Builds an AnnotatedString with clickable file paths overlaid on top of
 * standard markdown link/link-destination rendering.
 *
 * 1. buildMarkdownAnnotatedString → base text with standard links
 * 2. Walk AST for CODE_SPAN nodes → filter via LinkClassifier.isLikelyFilePath
 * 3. Overlay underline + linkColor on matched code paths
 */
internal fun buildClickableMarkdown(
    content: String,
    node: ASTNode,
    style: TextStyle,
    annotatorSettings: AnnotatorSettings,
    linkColor: Color,
): ClickableMarkdownResult

/**
 * Registers tap-gesture handling for clickable items in the AnnotatedString.
 * Uses TextLayoutResult to map tap position → item → uriHandler.openUri().
 */
internal fun Modifier.clickableMarkdown(
    result: ClickableMarkdownResult,
    layoutResultProvider: () -> TextLayoutResult?,
    uriHandler: UriHandler,
): Modifier
```

**Migration**: `extractClickableItems()` and `ClickableItem` sealed interface move from `MarkdownContent.kt` to this file.

---

### 3. Component Coverage

Override these `markdownComponents()` entries:

#### 3a. `text` (NEW override — universal text renderer)

The `text` component is the inline text renderer used by list items, block quotes, and other block elements. Overriding it provides file-path detection across all components that delegate to it.

```kotlin
markdownComponents(
    text = { model ->
        val result = buildClickableMarkdown(
            model.content, model.node, model.typography.text, annotator, linkColor
        )
        var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
        MarkdownBasicText(
            text = result.annotatedString,
            style = model.typography.text,
            onTextLayout = { layoutResult = it },
            modifier = Modifier.clickableMarkdown(result, { layoutResult }, uriHandler),
        )
    },
    // ...
)
```

**Verification needed during implementation**: Add temporary logging to confirm list items actually route through `text`. If they don't, override `orderedList`/`unorderedList` as well.

#### 3b. `paragraph` (refactored — reduce ~40 lines)

Replace the current inline logic with a call to the shared utility:

```kotlin
paragraph = { model ->
    val result = buildClickableMarkdown(
        model.content, model.node, model.typography.text, annotator, linkColor
    )
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    MarkdownBasicText(
        text = result.annotatedString,
        style = model.typography.text,
        onTextLayout = { layoutResult = it },
        modifier = Modifier.clickableMarkdown(result, { layoutResult }, uriHandler),
    )
},
```

#### 3c. `heading1` (NEW override)

```kotlin
heading1 = { model ->
    val headingText = model.node.getUnescapedTextInNode(model.content)
        .dropWhile { it == '#' }.trim()
    val result = buildClickableMarkdown(
        model.content, model.node, model.typography.h1, annotator, linkColor
    )
    Column {
        MarkdownBasicText(
            text = result.annotatedString,
            style = typography.h1,
            modifier = Modifier.clickableMarkdown(result, { layoutResult }, uriHandler),
        )
        HorizontalDivider(...)
    }
},
```

#### 3d. `table` (refactored — add link support to cells)

In `SimpleMarkdownTable.kt`, each cell currently renders via:
```kotlin
MarkdownBasicText(
    text = content.buildMarkdownAnnotatedString(textNode = cell, style = cellStyle, annotatorSettings = annotator),
    style = cellStyle,
)
```

Change to:
```kotlin
val result = buildClickableMarkdown(content, cell, cellStyle, annotator, linkColor)
var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
MarkdownBasicText(
    text = result.annotatedString,
    style = cellStyle,
    onTextLayout = { layoutResult = it },
    modifier = Modifier.clickableMarkdown(result, { layoutResult }, uriHandler),
)
```

Requires passing `uriHandler` and `linkColor` into `SimpleMarkdownTable`.

---

### 4. Data Flow

```
AI generates markdown with inline code
  → MarkdownContent parses via IntelliJ Markdown AST
    → markdownComponents.text / paragraph / heading1 / table renderer
      → buildClickableMarkdown():
          1. buildMarkdownAnnotatedString (standard links)
          2. Walk AST → CODE_SPAN nodes
          3. LinkClassifier.isLikelyFilePath() with whitelist
          4. Overlay underline style on matched paths
      → MarkdownBasicText renders
      → User taps
      → clickableMarkdown() modifier
          → TextLayoutResult maps tap → ClickableItem
          → uriHandler.openUri(path)
          → LinkUriHandler resolves path → FileViewerOverlay
```

---

## Testing Strategy

### Unit Tests

**`LinkClassifierTest.kt`** — whitelist behavior:
- Programming extensions (`.kt`, `.py`, `.js`, etc.) → true
- Config extensions (`.yaml`, `.toml`, `.xml`, etc.) → true
- Special filenames (`Makefile`, `Dockerfile`, `.gitignore`) → true
- Package names (`com.example.foo`, `org.springframework.boot`) → **false**
- Version strings (`1.0.0-beta`, `v2.0.0`) → **false**
- Unknown extensions (`.xyz`, `.unknown`) → **false**
- Paths with separator (`src/Foo.kt`, `path/to/unknown`) → true

**`ClickableMarkdownTest.kt`** — extraction logic:
- AST with CODE_SPAN file path → CodePath item extracted
- AST with CODE_SPAN non-path (e.g., `printf`) → no item
- AST with INLINE_LINK → Link item extracted
- Mixed content → all items extracted in order

### UI Verification (manual / emulator)

1. `` `com.example.foo` `` in paragraph → no underline, not clickable
2. `` `src/Foo.kt` `` in paragraph → underline, tap → FileViewer opens
3. `` `config.yaml` `` in table cell → underline, tap → FileViewer opens
4. `` `Main.kt` `` in list item → underline, tap → FileViewer opens (verify `text` coverage)
5. `[docs](https://example.com)` in table → standard link clickable
6. `` `Makefile` `` → underline, tap → FileViewer opens

---

## Out of Scope

- Plain text file path detection (no backticks) — high false-positive risk, deferred
- Dynamic extension list configuration — YAGNI for now
- `LinkUriHandler` / `isLikelyDirectory` changes — separate concern
- File existence pre-check before showing underline — deferred (current async check on click is sufficient)

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| `text` component not used by list items | File paths in lists not clickable | Log during implementation; override `orderedList`/`unorderedList` if needed |
| Whitelist too narrow | Some valid file extensions missed | Start with comprehensive list (~100+); easy to extend |
| Table cell tap conflicts with horizontal scroll | Broken scroll gesture | `detectTapGestures` doesn't consume drag events — verify |
| Performance: AST walk per recomposition | UI jank | `remember(model.content, model.node)` caches extraction (already done) |
