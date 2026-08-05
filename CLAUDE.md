# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status

Live at https://rack.apalveien5.eilertsen.family/. Working end-to-end: identify a part, file a slot from several photos at once, find/edit/move items, and maintain containers — register, rename, rescale, delete, and print QR labels (with per-container scale + printed-state tracking).

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

## Model choice

Three calls, three answers — all under `rack.ai` in `application.yml`, each overridable by env var. A single shared model makes the cheapest acceptable choice the ceiling for the most important call, so each names its own.

| Call | Model | Why |
|---|---|---|
| `SpringAiPartExtractor` (vision) | `claude-sonnet-4-6` | The index is only as true as this call |
| `AskAboutItem` | `claude-sonnet-4-6` | Accuracy, not cost — you ask by hand, so volume is tiny |
| `SpringAiQueryExpander` | `claude-haiku-4-5` | A synonym lookup against a word list we supply |

**Extraction is where cheap was measured and rejected.** Asked to read this rack's own tool drawer, `claude-haiku-4-5` returned the desoldering braid's part number as `D21/1129` where it is `1.26/14329` — at 0.95 confidence — misread the brand, found four items where there are five, and invented a soldering iron. `part_number` is meant to be null when it isn't legible; a confidently wrong one is exactly the drift the design exists to prevent, and it costs about a cent a photo to avoid. Both Sonnets read the number correctly.

**`claude-sonnet-5` does not run on this stack.** Spring AI 1.0.0 sends a `temperature` on every request and Sonnet 5 rejects non-default sampling parameters outright — `HTTP 400: temperature is deprecated for this model`. Leaving `spring.ai.anthropic.chat.options.temperature` blank does not help; the option class fills its own default back in. It needs a Spring AI upgrade, not a config change.

It would not pay for itself anyway. Measured on the same photo, Sonnet 5 reads the part number correctly but costs ~18% more input tokens at the *same* pixel size — 605 vs 475 for the prompt text (its tokenizer) and 1800 vs 1570 for the image (it tokenizes the same pixels more densely, not merely allowing more of them). It also runs adaptive thinking by default, which adds output tokens and returns a `thinking` block ahead of the JSON.

Photos are resized client-side to **1568px**, the longest edge the vision model keeps — larger is downsampled on arrival, so sending more is upload time for nothing. That is also why the resize does not rescue Sonnet 5: it recovers 122 of the 352-token image gap and none of the tokenizer's.

Claude has native vision, so the multipart-photo flow uses the same `ChatClient` API as any other model.

### What it has cost

Every model call reports its token usage, and `JsonFileUsageLog` adds it up per model into `data/usage.json` — written the same way slot state is, to a `.tmp` and moved atomically. Per model rather than in total because the models are priced an order of magnitude apart, so the split is the part that tells you which call is doing the spending.

`GET /usage` serves the tally; `assets/usage.js` puts it at the foot of every page, and pages that make a model call refresh it afterwards so the number moves while you watch. The count is read from each response rather than assumed from config, so it stays honest when a model is overridden by env var. Nothing about the tally can break the call it is counting: an unreadable file is logged and ignored at boot, and a failed write still counts in memory.

## Purpose

Small-parts inventory system. Photograph the contents of a slot in a physical storage container, a vision model extracts what's there into structured JSON, everything is searchable. Originally a rack of 60 drawers (5×12, A1–E12) — now generic over arbitrary containers (see #17). The photo is ground truth; the extracted data is an index over it.

## Architecture

Spring Boot with hexagonal (ports and adapters) architecture. Three ports, all swappable:

- `ImageStore` — persists slot photos to disk
- `PartExtractor` — one vision-model call that turns a batch of photos into `List<Extraction>` (Spring AI)
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
│   ├── model      # records: Container, ContainerId, Slot, SlotId, Item, Extraction, SearchHit, ContainerLayout
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

### Search: keyword first, widened only when it fails

The problem search has to solve is that **you don't know what the extractor called it**. Ask for "isolating tape" and the drawer says "electrical tape"; substring matching finds nothing and the rack looks empty.

Two passes, both over the same in-memory collection:

1. **Keyword** (`JsonFilePartIndex.searchByKeyword`) — the query is split on whitespace and scored word by word, so "black electrical tape" finds "Electrical tape / one black roll" even though no field contains that phrase. Per term: part number or `name` +3, `description` +2, category or a tag +1, plus +3 if the words appear in the order typed. Splitting on whitespace only keeps `TO-220` one term.

   **Every word has to match.** Resistors come on tape reels, so "isolating tape" scored 22 of them on "tape" alone while "isolating" — the word doing the work — matched nothing. An item that misses any term is not a hit, however well it scores on the rest. That leaves the query with nothing, which is both the honest answer and what lets the expansion see it failed.
2. **Expansion** (`QueryExpander` → `SpringAiQueryExpander`) — one small model call turns the query into related terms, then each is searched and merged in at 0.6 weight, deduped per item, keeping the best score. Literal hits therefore stay on top.

**Every expanded term has to bring a word the query didn't have.** The model's output varies between calls, and one run answered "isolating tape" with `tape` — which is not a widening. The literal pass already searched that word *and required "isolating" alongside it*; searching it alone drops the word that made the query specific, and a single generic word has no second word left for the all-words rule to bite on. That one term put all twenty-two tape-reel resistors back. `SpringAiQueryExpander.clean` drops any term built only from words the query already had, in code rather than trusting the prompt.

**Expansion is grounded, gated, and cached.** The prompt is handed `PartIndex.vocabulary()` — every distinct item name, category and tag — so the model bridges to words this rack actually uses instead of guessing generic synonyms. `FindItems` only calls it when the keyword pass turned up nothing *convincing* — best score below 3, the weight of a name or part-number match — so queries that already work ("BC547") cost nothing, and results are cached per normalised query.

**The gate is the best score, not the number of rows, because a row count lies.** Searching "sugekopp for lodding" on a rack with no desoldering pump matches six items on the word "for" alone, every one scoring 1.2; a count-based gate reads that as a search that worked and skips the expansion the query most needed. Frequency filtering doesn't help either at this scale — in a 79-item rack "for" appears in 7% of items and "tape" in 29%, so any threshold that drops the noise drops the word you meant.

**Two requests, not one.** `GET /search?q=` is the literal pass and fires on every keystroke; `&smart=true` follows 400ms after typing settles and is the only one that can reach the model. Typing stays instant and the widened set replaces the results when it finds more — `find.html` then names the terms it also searched for, so a surprising hit is explainable rather than magic. When the literal pass found nothing the page says "No exact match — looking for related items…" rather than flashing "No matches" and contradicting itself a second later.

The expansion model is separate from the vision model (`rack.search.expansion-model`, default `claude-haiku-4-5`): synonyms are a small fast job sitting in front of a search box. If the call fails for any reason — no API key, rate limit, non-JSON reply — the expander logs and returns nothing, and search degrades to the keyword pass rather than erroring.

`PartIndex.searchBySimilarity` and `Item.embedding` are unimplemented leftovers from a planned vector search — nothing populates or reads them (see #29).

### Optional Git history layer

Committing after each write gives history, per-drawer undo, and free multi-site sync over the existing mesh. Whether to enable this from day one is still open (see below).

### HTTP surface

- `/` → index page (hub)
- `/identify.html`, `POST /identify` → identify a part from a photo, no persistence
- `/find.html`, `GET /search?q=` → search; `&smart=true` widens a query that came up short (see Search above). `POST /search/photo` searches by photo instead of by typing. All three return `{query, expanded_terms, hits}`
- `/put.html`, `GET /c`, `GET /c/{container}`, `GET /c/{container}/{slot}`, `POST /c/{container}/{slot}/photo` → drawer-scoped photo capture and slot state. The photo endpoint (and `POST /suggest`) take **repeated `photo` parts** — one part is just a batch of one.
- `/containers.html`, `POST /c` (register), `PATCH /c/{container}` (name + label scale), `DELETE /c/{container}` → maintain containers; also hosts registration and the label flow below
- `GET /labels/{container}` (preview), `POST /labels/{container}` (mark + archive), `GET /labels/{container}/status` → QR label sheets
- Static pages resize phone photos to ~1600px client-side before upload; keeps below the 20MB per-file multipart cap (`max-request-size` is 100MB, since a batch is one request) and shrinks the vision call.

### Labels

Physical paper is always Avery L7160 (A4 21-up, 63.5×38.1mm). Each container declares a `labelScale` in config; content (QR + text) is drawn to `scale × 30mm` QR and `scale × 40pt` font, anchored to the top-left of each L7160 slot so trimming smaller labels for smaller drawers is easy. The QR encodes `<public-base>/put.html?c={container}&s={slot}` — scanning from a phone camera opens the capture page pre-scoped to that slot.

`Slot.printedAt` records when a label was archived via `POST /labels/{container}`. Preview (GET) doesn't touch this. Default scope on both endpoints is `unprinted` — pass `?scope=all` to include already-printed slots (`printed` is used internally to reconstruct sheet position).

**Several labels can share one physical sticker.** `LabelSheet.pack` shelf-packs consecutive labels into an L7160 slot, filling across before dropping to the next row, so a 0.4-scale container puts *four* labels on one sticker as a 2×2 grid to be trimmed apart; at 1.0 nothing packs. Mixed scales pack fine because each label is measured on its own — a full-scale label won't squeeze in beside a small one.

Width is estimated, not measured: the slot id is budgeted at 0.75em per character (Helvetica-Bold caps peak at 0.722em), so the estimate errs wide, and erring wide costs a column rather than causing an overlap. Longer slot ids therefore fit fewer per row — at scale 0.4 a 3-character id like `E12` still gets 2 columns, but the ceiling for 2 columns is scale 0.47 for 3-character ids versus 0.56 for 2-character ones.

**QR module size is the real limit on how small a scale can go.** A slot URL encodes to a 41-module symbol including its quiet zone, so a QR drawn at *S* mm has *S*/41 mm modules and phone cameras want roughly 0.33mm or more. Scale 0.4 gives 12mm → 0.29mm, which is under that; 0.46 gives 13.8mm → 0.34mm and still packs 4-up.

The consequence: **printed labels and consumed stickers are different numbers**, so sheet offsets are counted in stickers. `LabelSheet.positionCount` re-packs the already-printed labels to work out how far into the current sheet earlier runs reached.

**Sheet position is global, printing is per container.** A sheet of paper is a shared physical resource, so `resolveOffset` counts stickers consumed across *every* container: print 8 labels for a 0.4-scale container (4 stickers) and the next container's run starts at position 5, whichever container that is. There is deliberately no "print everything" run — not every container gets labels, so printing stays a per-container action. Packing only ever combines labels within one container's run, so a part-filled sticker is never continued by the next container; sharing happens at sticker granularity, not inside one.

### Resyncing a drawer

`AddPhotoToSlot` appends, which is right when you are putting something in a drawer and wrong when you are checking one — shoot a drawer you already filed and you get two of everything. `ResyncSlot` is the other operation: **this is what is in here now.**

It is a diff, in two halves. `POST /c/{container}/{slot}/resync/preview` extracts the batch, lines it up against what is recorded and writes nothing — not even the photos, so a preview you abandon leaves no frames for the real run to clean up. `POST /c/{container}/{slot}/resync` takes back the decisions and is the only half that touches disk. What you confirm is a removal, so it is worth the round trip.

**Replacing the slot outright would be worse than the drift it fixes.** A corrected part number, a hand-written name, the answers stored under Ask AI, the frames an item was seen in — none of it can be redone by a camera. So a kept item keeps everything except its quantity and its frames.

**Matching is one-to-one, greedy, best pair first.** Where both readings carry a part number that is the whole answer: equal is the same part, different is a different part, and no wording overlap may argue otherwise — this drawer holds 100K, 82K, 68K and 15K resistors, and BC547 beside BC557, whose names overlap more than enough to pair. Only where a part number is missing does wording decide it, at half the shorter label's words in common.

**An item kept despite not being in the photos points at no frame.** Overruling a "gone" verdict says the thing is in the drawer, not that it is in these photos; its old frames are about to be deleted, so pinning it to a new one that does not show it would put a lie where the evidence used to be. It falls back to the slot's strip instead. An added item is the one case that may fall back to the first frame, because it demonstrably came out of the batch.

Gone items are removed rather than zeroed, and the old photos are deleted — after the index write, so a crash leaves an orphan file rather than slot state naming a photo that no longer exists.

### Maintaining containers

`/containers.html` lists every container with its slot/item/label counts and is the single place to register, print labels, rename, rescale, or delete one. There is no separate register page.

- **Name and label scale are editable; the slot layout is not.** Reshaping a container would orphan slots that hold items, so `UpdateContainer` only ever rewrites those two fields.
- **Delete refuses while any slot holds items *or photos*** (`409`, naming the occupied slots in layout order). A photo counts as content even when nothing was extracted from it — the photo is ground truth and the items are only an index over it, so deleting would orphan a file that still means something. A printed label is not content. `DeleteContainer` checks `PartIndex.all(container)` rather than the current layout, so an item parked in an off-layout slot still blocks it, and the UI disables the button rather than offering a delete the server will refuse.
- Deleting drops the registration only — `data/<container>/` is left on disk, so re-registering the same id picks its slot state (and `printedAt`) back up.
- `server.error.include-message: always` is set so those refusal messages actually reach the browser.

### Deployment

- Frontend: PWA (`getUserMedia` for camera).
- Fronted by Vaier, a home-network reverse proxy that terminates TLS at `https://rack.apalveien5.eilertsen.family/` and forwards to the container on the box at `192.168.3.132:8080`. It challenges unauthenticated requests, so a **401 from the public URL is the proxy working, not the app failing** — verify against `localhost:8080` and treat the public check as proof only that the proxy still reaches the box.
- Run with `-v /home/geir/rack/data:/app/data` so slot JSON, photos, and printed label sheets survive restarts.

## Domain notes

### Per-item fields the extractor must produce

Strict JSON. `part_number` is null when not legible. `qty_estimate` is an estimate — never trusted as exact. `confidence` drives whether the UI nudges the user to verify. `tags[]` is free-form, useful for project association.

**`name` is the short label, `description` the long one.** A list is scanned by its titles, so `name` is capped at about four words ("BC547 transistor") while `description` carries packaging, markings, and whatever distinguishes this from the similar part in the same drawer. Keyword search scores a `name` hit as high as a `part_number` hit (+3) and a `description` hit lower (+2).

Items catalogued before the split have no `name` at all — no migration, per the storage model. Both list pages fall back to a clipped `description` for the title (`itemName()` in `put.html` / `find.html`) and show the full text underneath only when it was clipped, so old rows read sensibly and fix themselves the first time one is edited.

### What the vision model reads reliably

Printed part numbers on ICs / modules / connectors, text on bags / reels / manufacturer labels, and coarse shapes ("TO-220 transistor", "M4 hex bolt", "JST connector"). It does *not* reliably read resistor colour bands, unlabelled ceramic capacitors, or exact counts of a loose pile.

**Practical rule: photograph the labels as much as the parts.**

### Searching by photo

`POST /search/photo` (`FindByPhoto`) is the typed search's answer to the case it serves worst: **you cannot type a name you do not know.** Hold the part up to the camera instead, and the vision model's reading of it becomes the query.

It is the filing pipeline read the other way round — each extracted item goes through the same `FindItems.forPhotographed` lookup that `SuggestSlot` uses, so a photo and a good guess at the name find the same drawer. The response is the same `{query, expanded_terms, hits}` shape as `GET /search`, so `find.html` renders both with the same code and the results stay editable, movable and askable.

One request, not two: the server already widened per item, and there is nothing typed to debounce. The reading is left in the search box afterwards, so a wrong guess is edited rather than re-shot.

### Suggesting where a photographed part belongs

`POST /suggest` (`SuggestSlot`) is the search behind `put.html`: photograph a part, and it looks the extracted items up in the index to suggest the slot they belong in. It runs through `FindItems`, so it inherits both search behaviours — and it has to, because filing has a sharper version of the same problem. Photograph a roll of tape, have the extractor call it "Insulating tape", and a suggestion that misses the drawer already holding "Electrical tape" doesn't just fail to help: it files the roll a second time and *creates* the drift the whole design is against.

**The name widens; the part number and tags stay literal.** The name is the short label the expander is built for. A part number is already precise, and tags are the extractor's own synonyms — widening those would pay for breadth twice. That caps a batch at one model call per extracted item, and only for items whose name found nothing.

The name is searched at all now — before, only the part number and tags were, so the best label on the item was the one thing the lookup ignored.

**A name or part number anchors a slot; a tag only corroborates one.** A tag is a single generic word, so "every word must match" can't discipline it: photographing a roll of tape scored the resistor drawer 66 on the tag `tape` alone, because twenty-two resistors come on tape reels. A tag can raise a slot the name already found and can't put one in the list by itself, so an item with neither name nor part number — barely an identification — suggests nothing rather than guessing from its tags.

### Filing a slot as a batch of photos

That rule needs more than one frame per slot, so `put.html` collects photos into a pending strip — each tap of the camera appends a thumbnail, `×` drops one, and one **File N photos** action sends them all. Nothing is uploaded until then.

**The whole batch goes into a single vision call.** That is what stops a bag shot from the front and its label shot from the side becoming two index entries: the model merges frames into one item and takes the part number off whichever frame it was legible on. It still returns *several* items when the slot holds several different things — merging is per physical item, not per batch.

Each extraction carries an `image_index` back, which `AddPhotoToSlot` maps to the stored filename so `Item.sourcePhoto` points at the frame that actually shows that item. A missing or out-of-range index falls back to the first photo (the model omits it often enough to be worth pinning).

Every frame is kept in `Slot.photos` whether or not an item references it — the photo is ground truth, and an unreferenced frame is exactly the evidence that the extraction missed something.

**Expanding an item shows its frames**, the one it was read from first and badged. `Item.sourcePhoto` records which frame the model quoted, not the only frame the thing appears in: a part shot from two angles with its label on a third yields one item naming one photo. Across this rack 31 of 58 frames are named by no item, and almost none of them are spare — they are the label shots and second angles that were merged into items. Showing the slot's whole strip is what makes them reachable.

**So the extractor is asked which frames show each item, not just the best one.** `image_indexes` replaces `image_index`: the model is already merging a part's front, side and label into one entry, so it knows which frames it merged, and asking costs a handful of output tokens. Given three views of one drawer it put the Wiha screwdriver in two of them and the pliers in one — which is the answer "photos of this item" needs and that a single index cannot give.

`Item.seenIn` holds them, `sourcePhoto` stays the first so the thumbnail is unchanged, and `Extraction` drops repeats and invented indexes on the way in. Items filed before this have no `seenIn` and fall back to the slot's frames — the heading says *this item* or *this slot* accordingly, because claiming to know which frames show an old item would be a small lie. A moved item keeps neither: its frames stay in the slot it came from.

`FilesystemImageStore` names photos to the second, so a batch collides. Names are suffixed `…-12_1.jpg` on collision, with `_` rather than `-` because `_` sorts *after* `.` — so a listing keeps capture order.

### The failure mode to design against

Drift. A part is removed, the record isn't updated, six months later the index lies and trust collapses. Two mitigations, both load-bearing:

1. Updating a slot must be a single tap-scan-shoot-done action. No forms.
2. Every slot carries a `last_verified` date, surfaced in search results, so the user can see when the data was last real.

## Open items

Tracked as GitHub issues: https://github.com/geir-eilertsen/rack/issues

When new scope surfaces — a feature, adapter, or open question — file a `gh issue` rather than adding it here.
