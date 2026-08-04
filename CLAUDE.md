# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status

Live at https://rack.apalveien5.eilertsen.family/. Working end-to-end: identify a part, add a photo to a slot, find/edit/move items, and maintain containers — register, rename, rescale, delete, and print QR labels (with per-container scale + printed-state tracking).

## Build and run

```
./mvnw spring-boot:run                        # run the app
./mvnw test                                   # all tests
./mvnw -Dtest=SlotIdTest test                 # single test class
./mvnw -Dtest=SlotIdTest#acceptsCommonForms test   # single method
./mvnw package                                # jar in target/
```

Config: `ANTHROPIC_API_KEY` env var feeds `spring.ai.anthropic.api-key`. `RACK_DATA_DIR` overrides the default `./data`.

## Docker

```
docker build -t rack:local .
docker run --rm -p 8080:8080 -e ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY rack:local
```

Multi-stage build (Maven + JDK → JRE-only runtime). The app boots without an `ANTHROPIC_API_KEY` (the Anthropic autoconfig only requires it on first call, not at bean construction), but `/identify` will 500 without one.

Chat model is `claude-sonnet-4-6` (see `spring.ai.anthropic.chat.options.model` in `application.yml`). Claude has native vision, so the multipart-photo flow uses the same `ChatClient` API as any other model.

## Purpose

Small-parts inventory system. Photograph the contents of a slot in a physical storage container, a vision model extracts what's there into structured JSON, everything is searchable. Originally a rack of 60 drawers (5×12, A1–E12) — now generic over arbitrary containers (see #17). The photo is ground truth; the extracted data is an index over it.

## Architecture

Spring Boot with hexagonal (ports and adapters) architecture. Three ports, all swappable:

- `ImageStore` — persists slot photos to disk
- `PartExtractor` — vision-model call that turns a photo into `List<Item>` (Spring AI)
- `PartIndex` — read/write of slot state; search

### Domain vocabulary

- **Container** — a physical storage unit (rack of drawers, bin, shelf). Identified by lowercase `ContainerId` ("rack", "kitchen-bin").
- **Slot** — one location inside a container. Identified by URL-safe `SlotId` ("A1", "3", "top-left"). Uniqueness is *per container*: two containers can both have an "A1".
- **Item** — one identified thing inside a slot (a transistor, a bag of screws, ...). Comes from vision extraction.
- Containers are defined in `application.yml` under `rack.containers` and materialised at boot by `ContainerRegistry` (see `application/RackConfiguration.java`). Layout kinds: `grid` (cols × rows → A1..) and `linear` (N → 1..N with optional prefix).

### Package layout (enforced)

```
family.eilertsen.rack
├── domain
│   ├── model      # records: Container, ContainerId, Slot, SlotId, Item, SearchHit, ContainerLayout
│   └── port       # interfaces: ImageStore, PartExtractor, PartIndex
├── application    # AddPhotoToSlot service, ContainerRegistry, RackProperties/Configuration
└── adapter
    ├── in.web     # ContainerController, IdentifyController, LabelSheetController, HelloController
    └── out
        ├── filesystem   # FilesystemImageStore
        ├── json         # JsonFilePartIndex + custom serializers for ContainerId/SlotId
        └── springai     # SpringAiPartExtractor (Claude vision)
```

`HexagonalArchitectureTest` (ArchUnit) enforces:

1. **Onion layering** — domain doesn't depend on application or adapters; application doesn't depend on adapters; adapters don't depend on each other.
2. **Domain is framework-free** — no Spring, JPA, Servlet, or Jackson imports under `..domain..`. Adapters own all framework contact.
3. **Ports are interfaces** — anything under `..domain.port..` must be an interface.
4. **No package cycles** across top-level slices.

Adapters go under `adapter.in.<name>` or `adapter.out.<name>` — those subpaths are what the onion rule recognises.

### Storage model (why files, not a database)

Order of magnitude: ~60 slots × ~20 items = ~1,200 items per container. That's a data structure, not a database.

```
data/
  <container>/
    <slot>.json          # slot state (items, photos list, last_verified, printed_at)
    <slot>/              # photos for this slot
      2026-08-04-1712.jpg
    labels/
      2026-08-04-1712.pdf   # archived label sheet from each print run
```

- `JsonFilePartIndex` walks `data/<container>/*.json` at startup into `Map<ContainerId, Map<SlotId, Slot>>`.
- Writes: serialise to `<slot>.json.tmp`, then `Files.move` with `ATOMIC_MOVE`. Single writer on a single box.
- `grep -ri "BC547" data/` is a valid diagnostic; hand-editing a mislabelled item is a text edit.
- No schema migrations — add a field, tolerate its absence (`spring.jackson.deserialization.fail-on-unknown-properties` off implicitly via Spring Boot defaults; nullable fields on records get null).
- Container-scoped IDs mean `data/rack/A1.json` and `data/bin/A1.json` are two different slots and don't collide.

### Semantic search without a vector DB

Each item stores its embedding as a float array inside its JSON. Queries brute-force cosine similarity across ~1,200 vectors. At 1536 dimensions that's under two million multiply-adds — well under a millisecond in Java. Vector DBs are for millions of vectors, not thousands. Keyword ("BC547") and semantic ("small black thing with eight legs") matching run over the same in-memory collection.

### Optional Git history layer

Committing after each write gives history, per-drawer undo, and free multi-site sync over the existing mesh. Whether to enable this from day one is still open (see below).

### HTTP surface

- `/` → index page (hub)
- `/identify.html`, `POST /identify` → identify a part from a photo, no persistence
- `/put.html`, `GET /c`, `GET /c/{container}`, `GET /c/{container}/{slot}`, `POST /c/{container}/{slot}/photo` → drawer-scoped photo capture and slot state
- `/containers.html`, `POST /c` (register), `PATCH /c/{container}` (name + label scale), `DELETE /c/{container}` → maintain containers; also hosts registration and the label flow below
- `GET /labels/{container}` (preview), `POST /labels/{container}` (mark + archive), `GET /labels/{container}/status` → QR label sheets
- Static pages resize phone photos to ~1600px client-side before upload; keeps below the 20MB multipart cap and shrinks the vision call.

### Labels

Physical paper is always Avery L7160 (A4 21-up, 63.5×38.1mm). Each container declares a `labelScale` in config; content (QR + text) is drawn to `scale × 30mm` QR and `scale × 40pt` font, anchored to the top-left of each L7160 slot so trimming smaller labels for smaller drawers is easy. The QR encodes `<public-base>/put.html?c={container}&s={slot}` — scanning from a phone camera opens the capture page pre-scoped to that slot.

`Slot.printedAt` records when a label was archived via `POST /labels/{container}`. Preview (GET) doesn't touch this. Default scope on both endpoints is `unprinted` — pass `?scope=all` to include already-printed slots (`printed` is used internally to reconstruct sheet position).

**Several labels can share one physical sticker.** `LabelSheet.pack` shelf-packs consecutive labels into an L7160 slot, filling across before dropping to the next row, so a 0.4-scale container puts *four* labels on one sticker as a 2×2 grid to be trimmed apart; at 1.0 nothing packs. Mixed scales pack fine because each label is measured on its own — a full-scale label won't squeeze in beside a small one.

Width is estimated, not measured: the slot id is budgeted at 0.75em per character (Helvetica-Bold caps peak at 0.722em), so the estimate errs wide, and erring wide costs a column rather than causing an overlap. Longer slot ids therefore fit fewer per row — at scale 0.4 a 3-character id like `E12` still gets 2 columns, but the ceiling for 2 columns is scale 0.47 for 3-character ids versus 0.56 for 2-character ones.

**QR module size is the real limit on how small a scale can go.** A slot URL encodes to a 41-module symbol including its quiet zone, so a QR drawn at *S* mm has *S*/41 mm modules and phone cameras want roughly 0.33mm or more. Scale 0.4 gives 12mm → 0.29mm, which is under that; 0.46 gives 13.8mm → 0.34mm and still packs 4-up.

The consequence: **printed labels and consumed stickers are different numbers**, so sheet offsets are counted in stickers. `LabelSheet.positionCount` re-packs the already-printed labels to work out how far into the current sheet earlier runs reached.

**Sheet position is global, printing is per container.** A sheet of paper is a shared physical resource, so `resolveOffset` counts stickers consumed across *every* container: print 8 labels for a 0.4-scale container (4 stickers) and the next container's run starts at position 5, whichever container that is. There is deliberately no "print everything" run — not every container gets labels, so printing stays a per-container action. Packing only ever combines labels within one container's run, so a part-filled sticker is never continued by the next container; sharing happens at sticker granularity, not inside one.

### Maintaining containers

`/containers.html` lists every container with its slot/item/label counts and is the single place to register, print labels, rename, rescale, or delete one. There is no separate register page.

- **Name and label scale are editable; the slot layout is not.** Reshaping a container would orphan slots that hold items, so `UpdateContainer` only ever rewrites those two fields.
- **Delete refuses while any slot holds items *or photos*** (`409`, naming the occupied slots in layout order). A photo counts as content even when nothing was extracted from it — the photo is ground truth and the items are only an index over it, so deleting would orphan a file that still means something. A printed label is not content. `DeleteContainer` checks `PartIndex.all(container)` rather than the current layout, so an item parked in an off-layout slot still blocks it, and the UI disables the button rather than offering a delete the server will refuse.
- Deleting drops the registration only — `data/<container>/` is left on disk, so re-registering the same id picks its slot state (and `printedAt`) back up.
- `server.error.include-message: always` is set so those refusal messages actually reach the browser.

### Deployment

- Frontend: PWA (`getUserMedia` for camera).
- Fronted by a home-network reverse proxy that terminates TLS at `https://rack.apalveien5.eilertsen.family/` and forwards to the container on the box at `192.168.3.132:8080`.
- Run with `-v /home/geir/rack/data:/app/data` so slot JSON, photos, and printed label sheets survive restarts.

## Domain notes

### Per-item fields the extractor must produce

Strict JSON. `part_number` is null when not legible. `qty_estimate` is an estimate — never trusted as exact. `confidence` drives whether the UI nudges the user to verify. `tags[]` is free-form, useful for project association.

### What the vision model reads reliably

Printed part numbers on ICs / modules / connectors, text on bags / reels / manufacturer labels, and coarse shapes ("TO-220 transistor", "M4 hex bolt", "JST connector"). It does *not* reliably read resistor colour bands, unlabelled ceramic capacitors, or exact counts of a loose pile.

**Practical rule: photograph the labels as much as the parts.**

### The failure mode to design against

Drift. A part is removed, the record isn't updated, six months later the index lies and trust collapses. Two mitigations, both load-bearing:

1. Updating a slot must be a single tap-scan-shoot-done action. No forms.
2. Every slot carries a `last_verified` date, surfaced in search results, so the user can see when the data was last real.

## Open items

Tracked as GitHub issues: https://github.com/geir-eilertsen/rack/issues

When new scope surfaces — a feature, adapter, or open question — file a `gh issue` rather than adding it here.
