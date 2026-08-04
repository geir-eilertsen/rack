# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status

Scaffold in place: Maven, Java 21, Spring Boot 3.4, Spring AI 1.0. Domain records (`Drawer`, `DrawerId`, `Item`, `SearchHit`) and the three ports (`ImageStore`, `PartExtractor`, `PartIndex`) exist under `family.eilertsen.rack.domain`. No adapters yet — those are the open items below.

## Build and run

```
./mvnw spring-boot:run                        # run the app
./mvnw test                                   # all tests
./mvnw -Dtest=DrawerIdTest test               # single test class
./mvnw -Dtest=DrawerIdTest#acceptsCornersOfTheGrid test   # single method
./mvnw package                                # jar in target/
```

The Maven wrapper isn't checked in yet — run `mvn -N wrapper:wrapper` once (from a machine with `mvn` on PATH) to generate it, or substitute `mvn` for `./mvnw` above.

Config: `OPENAI_API_KEY` env var feeds `spring.ai.openai.api-key`. `RACK_DATA_DIR` overrides the default `./data`.

## Purpose

Small parts-inventory system for a rack of 60 drawers (5 wide × 12 down, positions A1–E12) holding electronics components, fasteners, and connectors. Workflow: photograph drawer contents → vision-model extraction → structured JSON → searchable index. The photo is ground truth; the extracted data is an index over it.

## Architecture

Spring Boot with hexagonal (ports and adapters) architecture. Three ports, all swappable:

- `ImageStore` — persists drawer photos
- `PartExtractor` — vision-model call that turns a photo into structured items (Spring AI)
- `PartIndex` — read/write of drawer records; search

The initial `PartIndex` adapter is file-backed. If the rack ever grows past ~1,000 drawers, swap in a Postgres adapter without the domain noticing — that seam is the reason for the port.

### Storage model (why files, not a database)

~60 drawers × ~20 items = ~1,200 items total. That's a data structure, not a database.

```
data/
  A1.json
  A1/
    2026-08-04-1712.jpg
  A2.json
  A2/
    ...
```

- Load all 60 JSON files at startup into `Map<DrawerId, Drawer>`. A few MB total.
- Search is a stream filter over the in-memory collection — no index, no tuning.
- Writes: serialise one drawer to `A1.json.tmp`, then `Files.move` with `ATOMIC_MOVE`. Single writer on a single box is the entire concurrency story.
- `grep -ri "BC547" data/` is a valid diagnostic; hand-editing a mislabelled item is a text edit.
- No schema migrations — add a field, tolerate its absence on older records.

### Semantic search without a vector DB

Each item stores its embedding as a float array inside its JSON. Queries brute-force cosine similarity across ~1,200 vectors. At 1536 dimensions that's under two million multiply-adds — well under a millisecond in Java. Vector DBs are for millions of vectors, not thousands. Keyword ("BC547") and semantic ("small black thing with eight legs") matching run over the same in-memory collection.

### Optional Git history layer

Committing after each write gives history, per-drawer undo, and free multi-site sync over the existing mesh. Whether to enable this from day one is still open (see below).

### Frontend / deployment

- PWA using `getUserMedia` for camera access — no app store.
- QR code on each drawer front encodes its ID; scanning opens "what's in this drawer" and auto-tags photos on capture. This removes the biggest source of corruption: mis-filing a photo against the wrong drawer.
- Docker Compose on a NUC, behind Traefik.

## Domain notes

### Per-item fields the extractor must produce

Strict JSON. `part_number` is null when not legible. `qty_estimate` is an estimate — never trusted as exact. `confidence` drives whether the UI nudges the user to verify. `tags[]` is free-form, useful for project association.

### What the vision model reads reliably

Printed part numbers on ICs / modules / connectors, text on bags / reels / manufacturer labels, and coarse shapes ("TO-220 transistor", "M4 hex bolt", "JST connector"). It does *not* reliably read resistor colour bands, unlabelled ceramic capacitors, or exact counts of a loose pile.

**Practical rule: photograph the labels as much as the parts.**

### The failure mode to design against

Drift. A part is removed, the record isn't updated, six months later the index lies and trust collapses. Two mitigations, both load-bearing:

1. Updating a drawer must be a single tap-scan-shoot-done action. No forms.
2. Every drawer carries a `last_verified` date, surfaced in search results, so the user can see when the data was last real.

## Seeding

Shoot all 60 drawers in one sitting — white paper background, coin in frame for scale — then batch-process the lot. Getting from zero to populated in one evening is what makes the system stick.

## Open items

- File-backed `PartIndex` adapter (`JsonFilePartIndex`) — not yet written
- Filesystem `ImageStore` adapter — not yet written
- Spring AI `PartExtractor` adapter + extraction prompt — not yet written
- Web/PWA inbound adapter — not yet written
- Whether the Git history layer is in from the start or added later
- Maven wrapper (`./mvnw`) — not yet generated
