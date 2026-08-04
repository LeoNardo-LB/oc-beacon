# ChatDensity Typography & Spacing Token System

**Date:** 2026-06-26
**Status:** Design Approved
**Scope:** Markdown rendering typography, code block engine, spacing tokens, settings consolidation

---

## Problem

1. **H4 > H3 heading inversion**: h3 maps to titleSmall (14sp), h4 maps to bodyLarge (16sp) — h4 renders larger than h3.
2. **Scattered magic numbers**: Font sizes, spacing, and padding hardcoded across 6+ files.
3. **Uncontrollable code block padding**: OcCodeBlock's Box has no explicit padding; `markdownPadding.codeBlock` doesn't apply because we override the component.
4. **Limited syntax highlighting**: dev.snipme:highlights supports ~20 languages with basic color accuracy.
5. **Fragmented settings**: Separate "font size" (3 options) and "compact messages" (toggle) control overlapping concerns.

## Solution

### 1. ChatDensity Token System

Single source of truth for all chat typography, spacing, and bubble dimensions. Two presets driven by one enum.

```kotlin
enum class ChatDensity { Normal, Compact }

data class ChatTypographyTokens(body, code, h1-h6)
data class ChatSpacingTokens(block, listItemBottom, listIndent, tableCell, codeBlock, blockQuoteHorizontal)
data class ChatBubbleTokens(paddingH, paddingV, itemSpacing)

val LocalChatDensity = compositionLocalOf { ChatDensity.Normal }
```

**Heading +N rule** (dynamic, scales with body font size):

| Level | Font rule | Line height rule | Weight | Opacity |
|-------|-----------|-----------------|--------|---------|
| h1 | body+4 | LH+4 | Black | full |
| h2 | body+3 | LH+3 | Bold | full |
| h3 | body+2 | LH+2 | SemiBold | full |
| h4 | body+1 | LH+1 | SemiBold | full |
| h5 | body+0 | LH+0 | SemiBold | full |
| h6 | body+0 | LH+0 | Medium | x0.80 (HIGH) |

**Two presets:**

| Token | Normal (14sp body) | Compact (13sp body) |
|-------|--------------------|--------------------|
| body | 14sp / 22sp | 13sp / 18sp |
| code = table | 13sp / 20sp | 12sp / 18sp |
| h1 | 18sp / 26sp | 17sp / 22sp |
| h2 | 17sp / 25sp | 16sp / 21sp |
| h3 | 16sp / 24sp | 15sp / 20sp |
| h4 | 15sp / 23sp | 14sp / 19sp |
| h5 | 14sp / 22sp | 13sp / 18sp |
| h6 | 14sp / 22sp | 13sp / 18sp |
| block | 4dp | 2dp |
| listItemBottom | 2dp | 2dp |
| listIndent | 16dp | 12dp |
| tableCell = codeBlock | 6dp | 4dp |
| blockQuoteHorizontal | 16dp | 12dp |
| bubble paddingH | 16dp | 10dp |
| bubble paddingV | 14dp | 8dp |
| bubble itemSpacing | 10dp | 4dp |

### 2. compose-highlight Integration

Replace dev.snipme:highlights + hand-rolled OcCodeBlock with `dev.hossain:compose-highlight:0.31.0`.

- **Highlight theme:** GitHub Dark / GitHub Light
- **Background color:** Material `colorScheme.surfaceContainer` (not GitHub theme background)
- **Code wrapping:** Horizontal scroll only (remove wordWrap setting)
- **Language label + copy button:** Use built-in (CodeBlockStyle defaults)
- **HighlightThemeProvider:** Wraps AppTheme content; manages single shared hidden WebView
- **Toast on copy:** Chinese "已复制" (via onCopyClick callback)

**OcCodeBlock rewrite:**
- Extract code text + language from AST node (reuse existing logic)
- Delegate to `SyntaxHighlightedCode(code, language, style=CodeBlockStyle(...))`
- CodeBlockStyle reads from ChatDensity tokens (padding, textStyle)
- BackgroundColor from Material colorScheme (with AMOLED/user-message variants)

### 3. H1 Bottom Divider

Via custom `heading1` component (no markdown preprocessing hack):
```kotlin
heading1 = { model ->
    Column {
        MarkdownHeader(model.content, model.node, style = typography.h1)
        HorizontalDivider(thickness = 1.dp, color = outlineVariant.copy(alpha = FAINT))
    }
}
```

### 4. Settings Consolidation

Merge "font size" (small/medium/large) + "compact messages" (on/off) into single "对话字体" radio:
- **正常 (Normal):** 14sp body, loose spacing
- **小 (Compact):** 13sp body, tight spacing

Migration: medium→Normal, small→Compact, large→Normal; compact toggle on→Compact.

### 5. CompositionLocal Cleanup

Remove: `LocalChatFontSize`, `LocalCodeWordWrap`, `LocalCompactMessages`
Add: `LocalChatDensity` (provides ChatDensity enum)

Components read `LocalChatDensity.current` then derive values via preset lookup.

---

## Files

### New
- `ui/theme/ChatDensity.kt` — Token definitions + presets + CompositionLocal
- `src/main/assets/highlightjs/github-dark.css` — Highlight.js GitHub Dark theme
- `src/main/assets/highlightjs/github-light.css` — Highlight.js GitHub Light theme

### Modified
| File | Change |
|------|--------|
| `app/build.gradle.kts` | +compose-highlight:0.31.0, -dev.snipme:highlights |
| `ui/theme/Theme.kt` | Wrap content in HighlightThemeProvider |
| `markdown/MarkdownContent.kt` | Headings from token, table=code size, spacing from token, h1 divider, remove wordWrap |
| `markdown/OcCodeBlock.kt` | Rewrite with SyntaxHighlightedCode + CodeBlockStyle from token |
| `markdown/MarkdownTable.kt` | Cell padding from token |
| `components/MessageCard.kt` | Bubble padding from token |
| `components/AssistantTurnBubble.kt` | compact → density |
| `chat/util/ChatCompositionLocals.kt` | 3 Locals → 1 LocalChatDensity |
| `chat/ChatScreen.kt` | Provides + messageSpacing from density |
| `chat/util/ChatModifiers.kt` | Remove wordWrap code |
| `chat/ChatViewModel.kt` | Replace chatFontSize/compactMessages flows with chatDensity |
| `screens/settings/*` | Merge to single "对话字体" option |

## Non-Goals
- Word wrap setting (removed — horizontal scroll only)
- Large font option (removed — only Normal/Compact)
- Custom Highlight.js theme editing
- Line numbers in code blocks (available via API but not enabled by default)

## Risks
- compose-highlight is new (v0.31.0, released 2026-04). HighlightThemeProvider creates a single shared WebView (~200ms warm-up on first code block). Performance is good for 10+ code blocks per screen.
- WebView unavailable on some custom ROMs — compose-highlight shows plaintext placeholder via onError.
- Migration must handle existing user settings gracefully.
