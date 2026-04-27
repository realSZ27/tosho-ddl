# 🧠 What this class is doing

You are implementing:

```kotlin
class KayoAnimeSource : DownloadSource
```

It must convert a **messy anime download page** into structured:

```kotlin
Release
```

for a system backed by:

* search()
* getRecent()
* download(identifier)

Where:

* `identifier` = download URL (important simplification)
* mirrors are handled via `LinkGroup` elsewhere

---

# 🧱 Your existing model constraints

### DownloadSource

```kotlin
suspend fun search(query: String, season: Int?, episode: Int?): List<Release>
suspend fun getRecent(): List<Release>
suspend fun download(identifier: String)
```

### Release

```kotlin
data class Release(
    val title: String,
    val source: AppConfig.Source,
    val webpageLink: String?,
    val identifier: String,
    val pubDate: Instant,
    val fileSize: Long?
)
```

### LinkGroup (important)

```kotlin
data class LinkGroup(
    val host: String,
    val links: List<String>
)
```

👉 Key design constraint:

* You MUST preserve duplicate download links (mirrors)
* You must NOT dedupe them at this layer

---

# 🧠 Core system design

Each scraped page is processed like this:

```text
HTML page
 → parseGlobalMeta(page title)
 → parseButtonMeta(each download button)
 → merge global + button metadata
 → expand (multi-season buttons)
 → group logically (ReleaseKey)
 → convert to Release
```

---

# 🧩 STEP 1 — Global metadata (page title parsing)

Example title:

```text
Skip and Loafer (Skip to Loafer) (Season 1) 1080p Eng Sub HEVC
```

### Function you need:

```kotlin
fun parseGlobalMeta(title: String): GlobalMeta
```

### Extract:

* Base title → `"Skip and Loafer (Skip to Loafer)"`
* Seasons → `[1]` (if present)
* Quality → `1080p`
* Source → `BluRay` etc (if present)
* Extras → `Eng Sub`, alt titles, etc.

⚠️ Important rule:

* DO NOT split title at first "("
* Parentheses can contain **alt titles**, not just metadata

---

# 🧩 STEP 2 — Button parsing

Each dropdown entry like:

```text
Season 1 [BluRay]
Season 1-2
1080p
Movie [720p]
Execution
```

### Function:

```kotlin
fun parseButtonMeta(label: String): ButtonMeta
```

Extract:

* seasons (if present)
* quality (1080p / 720p / etc.)
* source (BluRay / WEB-DL / etc.)
* extras (everything else)

---

# 🧩 STEP 3 — Season normalization (critical)

You must handle all variants:

```text
Season 1-2
Season 1 + 2
Season 1+2
Season 1 + Season 2
Season 1 & 2
Season 1 & Season 2
```

### Strategy:

Normalize first:

```kotlin
& → +
remove spaces around +
Season → S
```

Then parse:

* `S1-2` → range expansion
* `S1+S2` → list

---

# 🧩 STEP 4 — Merge logic (button overrides page)

This is the most important rule in your system:

```text
button metadata > global metadata
```

### Function:

```kotlin
fun mergeMeta(button: ButtonMeta, global: GlobalMeta): List<ParsedEntry>
```

Rules:

* If button has season info → use it
* If not:

  * AND global has exactly 1 season → assume that season
  * ELSE → skip (ambiguous)

---

# 🧩 STEP 5 — Multi-season expansion

If button resolves to:

```text
Season 1-3
```

You MUST expand into:

```text
S01
S02
S03
```

Each becomes a separate `ParsedEntry`.

---

# 🧩 STEP 6 — Mirrors (VERY important for your LinkGroup design)

You explicitly clarified:

> duplicates are mirrors, not duplicates

So:

### DO NOT dedupe by label or URL

Instead:

* Keep every link
* Group them later into:

```kotlin
LinkGroup(host, links)
```

### Meaning:

* Same logical release → multiple links
* They are preserved, not removed

---

# 🧩 STEP 7 — Grouping into releases

Create a grouping key:

```kotlin
data class ReleaseKey(
    val title: String,
    val season: Int?,
    val quality: String?,
    val source: String?,
    val extras: List<String>
)
```

Then:

```kotlin
entries.groupBy { toKey(it) }
```

Each group becomes:

* 1 `Release`
* N mirror links (used elsewhere as `LinkGroup`)

---

# 🧩 STEP 8 — Final Release creation

Inside `search()` you eventually do:

```kotlin
Release(
    title = buildTitle(key),
    source = AppConfig.Source.KayoAnime,
    webpageLink = pageUrl,
    identifier = downloadUrl, // IMPORTANT: direct link only
    pubDate = Instant.now(),
    fileSize = null
)
```

---

# 🧩 STEP 9 — Title generation (Sonarr-friendly)

Format:

```text
Series.Name.S01.1080p.BluRay.EngSub
```

Rules:

* spaces → `.`
* season → `S01`
* append:

  * quality
  * source
  * extras (normalized)

---

# ⚠️ Critical edge cases your code must handle

### 1. “1080p only” buttons

* If single-season page → assign S01
* If multi-season → skip or treat as ambiguous

---

### 2. Multi-season buttons

Must expand into multiple releases (S01, S02, ...)

---

### 3. Duplicate buttons (mirrors)

* Keep all links
* Group later into `LinkGroup`
* Do NOT remove duplicates

---

### 4. Weird labels (“Execution”, “OVA”)

* Treat as `extras`
* Never try to infer structure unless explicit

---

### 5. Alt titles in parentheses

Example:

```text
Skip and Loafer (Skip to Loafer)
```

* Keep as part of title OR extras
* Do not discard

---

# 🧠 Mental model of your implementation

Inside `KayoAnimeSource`:

### You are building:

> A deterministic normalization layer that converts:
>
> **unstructured anime listing pages**
> → into structured Sonarr-compatible releases
> while preserving mirror links for fallback download reliability

---

# 🧩 What your file will eventually look like conceptually

```kotlin
search()
 ├── fetch HTML
 ├── parseGlobalMeta()
 ├── for each button:
 │     ├── parseButtonMeta()
 │     ├── mergeMeta()
 │     ├── expand seasons
 │
 ├── group by ReleaseKey
 ├── convert to Release
 └── return list
```