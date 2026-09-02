# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status

Working end-to-end: identify a part, file a slot from several photos at once, find/edit/move items, and maintain containers — register, rename, rescale, delete, and print QR labels (with per-container scale + printed-state tracking).

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
docker run --rm -p 8123:8123 -e ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY rack:local
```

Multi-stage build (Maven + JDK → JRE-only runtime). The app boots without an `ANTHROPIC_API_KEY` (the Anthropic autoconfig only requires it on first call, not at bean construction), but `/identify` will 500 without one.

The image packages with `-DskipTests`, so a build is not a test run — `./mvnw test` and the publish workflow are where the tests happen.

### Publishing to Docker Hub

`.github/workflows/publish.yml` runs `./mvnw test`, then builds and pushes `geireilertsen/rack` on every push to `main` and on any `v*` tag. Two repository secrets: `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` (a hub.docker.com access token, not the password).

Tags published: `latest` on main, `sha-<short>` always, and `<version>` plus `<major>.<minor>` when a `v*` tag is pushed. **`sha-` is the one to pin a host to**, because it names a commit — `latest` names whatever main happened to be that morning, and a host that only ever pulls `latest` cannot say what it is running.

`linux/amd64` only. Adding `linux/arm64` to `platforms:` is a one-line change, and costs a QEMU emulation pass, so it waits until there is an ARM host to run it on.

### Running it on another host

`compose.yaml` pulls that image; it is for a second host, not for this box — the deploy skill still builds `rack:local` here, and `container_name: rack` means a stray `docker compose up` on the build box collides with the live container rather than starting a second copy of it.

```
cp .env.example .env      # fill in ANTHROPIC_API_KEY
docker compose up -d
docker compose pull && docker compose up -d      # update
RACK_TAG=sha-abc1234 docker compose up -d        # pin, or roll back
```

**The bind mount is the whole of the state.** `./data` beside the compose file holds slot JSON, photographs, documents and archived label sheets, and is the only copy — there is no database to dump and nothing in the image to fall back on. Moving an installation is `rsync -a data/` and nothing else; backing one up is the same.

**Set `RACK_PUBLIC_BASE_URL` before printing anything.** It is what the QR on every sticker encodes, and a label printed against the wrong base is a sticker that has to be peeled off and reprinted. Everything else in `.env` has a working default.

The image listens on **8123** (`SERVER_PORT` in the Dockerfile; `./mvnw spring-boot:run` from a checkout is still 8080). `RACK_PORT` defaults to `8123` on all interfaces. Behind a reverse proxy on the same host, `RACK_PORT=127.0.0.1:8123` keeps it off the LAN — the app has no authentication of its own and never has had; the proxy in front of it is the whole of the access control.

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

**A reply asked for as JSON arrives as nearly-JSON often enough to handle in one place.** `ModelReply.json` takes the outermost object *or array* out of whatever came back — a preamble ("Looking at the photos:", "I'll go through what a Quad 606 restoration needs…"), a code fence, a sign-off. It is depth-counted and blind inside string literals, so a brace in a description does not close the value early, and it picks the opener that actually starts the value rather than the first bracket in the prose. There were two half-measures before: one undid fences but not a preamble, the other handled a preamble but only around an object — and the extractor and the expander both answer with arrays. Tightening a prompt is a guess about the next reply; taking the value out of the text is not.

### What it has cost

Every model call reports its token usage, and `JsonFileUsageLog` adds it up per model into `data/usage.json` — written the same way slot state is, to a `.tmp` and moved atomically. Per model rather than in total because the models are priced an order of magnitude apart, so the split is the part that tells you which call is doing the spending.

`GET /usage` serves the tally; `assets/usage.js` puts it at the foot of every page, and pages that make a model call refresh it afterwards so the number moves while you watch. The count is read from each response rather than assumed from config, so it stays honest when a model is overridden by env var. Nothing about the tally can break the call it is counting: an unreadable file is logged and ignored at boot, and a failed write still counts in memory.

## Purpose

Small-parts inventory system. Photograph the contents of a slot in a physical storage container, a vision model extracts what's there into structured JSON, everything is searchable. Originally a rack of 60 drawers (5×12, A1–E12) — now generic over arbitrary containers (see #17). The photo is ground truth; the extracted data is an index over it.

## Architecture

Spring Boot with hexagonal (ports and adapters) architecture. Three ports, all swappable:

- `ImageStore` — persists photographs to one flat folder for the whole rack
- `DocumentStore` — the same, for manuals on a project and datasheets on an item
- `PartExtractor` — one vision-model call that turns a batch of photos into `List<Extraction>` (Spring AI)
- `PartIndex` — read/write of slot state; search

### Domain vocabulary

- **Container** — a physical storage unit (rack of drawers, bin, shelf). Identified by lowercase `ContainerId` ("rack", "kitchen-bin").
- **Slot** — one location inside a container. Identified by URL-safe `SlotId` ("A1", "3", "top-left"). Uniqueness is *per container*: two containers can both have an "A1". **Each container says what one of its slots is called** — `slotLabel`, defaulting to "slot". "Drawer" is right for a rack of drawers and wrong for a plastic box, and only the owner knows which they have. `Slot` stays the code and URL vocabulary, which is true of all of them.

**A container with a single slot has no subdivisions at all**, and the UI stops mentioning them: no grid to pick from, no word for a part it has not got, and the title is just the container's name. Selecting it selects the one place. That is the plastic box — the box *is* the location, and asking which compartment would be asking about something that does not exist.

**And a container with several is drawn at the width its slot ids imply.** A `Container` keeps a flat list of slots — the cols and rows given at registration are spent producing that list and not kept — so `.slots-grid` was five across for everything: right for the 5×12 rack it was written for, wrong for the 2×5 cupboard, which came out as `A1 B1 A2 B2 A3 / B3 A4 B4 A5 B5`, ten drawers in the correct order and the wrong shape. Nothing new is stored, because nothing needs to be: `ContainerLayout.grid` emits row-major with one letter per column, so a list whose rows are all the same width *is* that wide, and `assets/slots.js` reads it back off the ids. Anything with no shape to read falls back to five — a linear `1..11`, a free-form `top-left`, a ragged list — and so does a one-wide reading, since `linear(4, "Box")` gives `Box1..Box4`, a numbered run rather than a column of four. Derived in the page rather than served, the way a slot's frames are.

**Containers are listed by the name on the front.** `ContainerRegistry.all()` sorts on `Container.BY_NAME`, so the hub's jump, put.html's picker, both move panels, containers.html and `/ask`'s inventory listing all read the same order without each sorting for itself. Registration order is a fact about the week a container was added rather than about the shelf, and it left "Vaskerom" between two plastic boxes. Case is ignored, a run of digits compares as a number so "Plastboks 2" comes before "Plastboks 10", and ties break on the id — which is unique, so the order is total and the same after every restart.
- **Project** — a job of work. Holds what it needs (`ProjectPart`, each with a status from `in_stock` through `to_buy`, `ordered`, `arrived` to `used`), how to do it (`ProjectStep`, tickable, annotatable), its hazards, and a log. The only thing in the app that records stock *leaving*.
- **Item** — one identified thing inside a slot (a transistor, a bag of screws, ...). Comes from vision extraction.
- **Document** — a file kept with something: a service manual on a project, a datasheet on an item. Held by its owner, stored flat in `data/documents/`, swept when nothing names it.
- **Photograph** — a picture of an item. **Items own photographs; slots do not.** `Item.sourcePhoto` is the frame it was read from and `Item.seenIn` is every frame showing it; a slot has no photo list of its own. The containment runs one way — container holds slots, slot holds items, item holds photographs — and everything else is derived from it.
- Containers are defined in `application.yml` under `rack.containers` and materialised at boot by `ContainerRegistry` (see `application/RackConfiguration.java`). Layout kinds: `grid` (cols × rows → A1..) and `linear` (N → 1..N with optional prefix).

### Package layout (enforced)

```
family.eilertsen.rack
├── domain
│   ├── model      # records: Container, ContainerId, Slot, SlotId, Item, Extraction, SearchHit, ContainerLayout,
│   │               #          Project, ProjectId, ProjectPart, ProjectStep, ProjectNote, Document
│   └── port       # interfaces: ImageStore, PartExtractor, PartIndex, ProjectStore
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
  photos/                # every photograph, for the whole rack
    2026-08-04-1712.jpg
  documents/                    # service manuals, schematics — flat, like photos
    Quad-405-2-606-707-service-manual.pdf
  projects/
    quad-606-restoration.json   # one job of work: parts, steps, cautions, log, documents
  <container>/
    <slot>.json          # slot state (items, last_verified, printed_at)
    labels/
      2026-08-04-1712.pdf   # archived label sheet from each print run
```

**Photographs are not filed under the drawer they were taken of.** A frame is a picture of some things that happened to be in a drawer at a moment, and putting it under that drawer encodes an ownership that stops being true twice over: 18 frames in this rack are referenced by more than one item, one of them by 22, and an item that moves takes its references with it. While the file lived under the slot, a moved item's references resolved against the *new* drawer's directory and answered 404 — so `MoveItem` had to strip an item of its photographs to avoid showing a broken one. Now they simply follow it.

**Items own photographs. Slots do not.** A slot contains items; an item is a physical thing; a photograph is a picture of an item. `Slot.photos` is gone — the slot's frames are *derived* from its items (`Slot.frames()`, a plain method rather than a record component, so it is never written to disk).

Having the reference in two places is what caused most of the bugs this design has had: a drawer emptied of items still counted as occupied and could not be deleted; a photograph could be listed by a slot and rendered by nobody; a moved item left its picture behind; three frames in this rack were listed by a slot and named by no item; and deciding whether a file was still in use meant consulting both lists. Each of those stops being a bug that needs fixing and becomes a state that cannot be written down. Delete an item and its frames are unreferenced; move it and they go with it; empty a drawer and it has no frames.

The old key is inert rather than migrated, per the no-migrations rule: `fail-on-unknown-properties` is off (now stated in `application.yml` rather than inherited, because a load that fails on it is every drawer at once), and each slot file drops its dead `photos` key the first time that slot is rewritten. `JsonFilePartIndexTest` pins both halves — a file carrying the old key still loads, and a file written now carries no photo list of its own.

`GET /photos/{filename}` serves them flat, and the filename is validated as a bare name: one folder for the whole rack means a `../` would walk out of the data directory rather than merely into the next drawer.

**Deleting a frame is a question about the whole rack, not one slot.** `PartIndex.photosInUse()` is the union of what every item names, and every path that can orphan a file asks it before removing anything — the frames one drawer is finished with may be the only picture another drawer's item has.

- `JsonFilePartIndex` walks `data/<container>/*.json` at startup into `Map<ContainerId, Map<SlotId, Slot>>`.
- Writes: serialise to `<slot>.json.tmp`, then `Files.move` with `ATOMIC_MOVE`. Single writer on a single box.
- `grep -ri "BC547" data/` is a valid diagnostic; hand-editing a mislabelled item is a text edit.
- No schema migrations — add a field, tolerate its absence (`spring.jackson.deserialization.fail-on-unknown-properties` off implicitly via Spring Boot defaults; nullable fields on records get null).
- Container-scoped IDs mean `data/rack/A1.json` and `data/bin/A1.json` are two different slots and don't collide.

### Search: keyword first, widened only when it fails

The problem search has to solve is that **you don't know what the extractor called it**. Ask for "isolating tape" and the drawer says "electrical tape"; substring matching finds nothing and the rack looks empty.

Two passes, both over the same in-memory collection:

1. **Keyword** (`JsonFilePartIndex.searchByKeyword`) — the query is split on whitespace and scored word by word, so "black electrical tape" finds "Electrical tape / one black roll" even though no field contains that phrase. Per term: part number or `name` +3, `description` +2, category or a tag +1, plus +3 if the words appear in the order typed. Splitting on whitespace only keeps `TO-220` one term.

   **The Q&A is searched too, below the item's own words.** A question is the only text on an item written by the person who owns it — "is this a good selection for an audio amp" is how you know that capacitor as the one you picked for the amp, and it was the one thing about it search could not see. Question +2, the same as a description; answer +0.5, because an answer is model prose at 946 characters against a question's 29, and a word buried somewhere in it is thin evidence — the heat sink compound's answer says "internet" and "sources" without being either. Scored once per field however many exchanges an item has, the way tags are, so curiosity about one item cannot outrank the identical item nobody asked about.

   **2 + 0.5 is deliberately under the 3.0 that counts as convincing**, so an item found only through its Q&A is a hit but never the kind that stops the query being widened. "16GB" finds the NUC because its answer lists the maximum, and still looks for related words. The Q&A does not feed `vocabulary()`: that list is short labels for the expander to bridge to, and a thousand characters of prose per item would swamp it.

   **Every word has to match.** Resistors come on tape reels, so "isolating tape" scored 22 of them on "tape" alone while "isolating" — the word doing the work — matched nothing. An item that misses any term is not a hit, however well it scores on the rest. That leaves the query with nothing, which is both the honest answer and what lets the expansion see it failed.
2. **Expansion** (`QueryExpander` → `SpringAiQueryExpander`) — one small model call turns the query into related terms, then each is searched and merged in at 0.6 weight, deduped per item, keeping the best score. Literal hits therefore stay on top.

**Every expanded term has to bring a word the query didn't have.** The model's output varies between calls, and one run answered "isolating tape" with `tape` — which is not a widening. The literal pass already searched that word *and required "isolating" alongside it*; searching it alone drops the word that made the query specific, and a single generic word has no second word left for the all-words rule to bite on. That one term put all twenty-two tape-reel resistors back. `SpringAiQueryExpander.clean` drops any term built only from words the query already had, in code rather than trusting the prompt.

**The vocabulary is sorted, and the cap is a guard rather than a budget.** `vocabulary()` used to iterate `ConcurrentHashMap.values()` — hash order, arbitrary and different between restarts — and the expander sent only the first 400. This rack reached 671 words, so a quarter of them never left the building and *which* quarter changed per boot. The result was the headline case working as a coin flip: "isolating tape" bridged to the electrical tape, or returned nothing, depending on the restart. It is sorted by container then slot now, deduplicated ignoring case (an item named "Electrical tape" and tagged "electrical tape" spent two places on one word), and `MAX_VOCABULARY` is 1500 — the whole list is about a quarter of a cent per call at haiku rates, and the call only happens when the literal search already failed. Going over the cap now logs a warning, because silently sending part of the rack is how this broke.

**Expansion is grounded, gated, and cached.** The prompt is handed `PartIndex.vocabulary()` — every distinct item name, category and tag — so the model bridges to words this rack actually uses instead of guessing generic synonyms. `FindItems` only calls it when the keyword pass turned up nothing *convincing* — best score below 3, the weight of a name or part-number match — so queries that already work ("BC547") cost nothing, and results are cached per normalised query.

**The gate is the best score, not the number of rows, because a row count lies.** Searching "sugekopp for lodding" on a rack with no desoldering pump matches six items on the word "for" alone, every one scoring 1.2; a count-based gate reads that as a search that worked and skips the expansion the query most needed. Frequency filtering doesn't help either at this scale — in a 79-item rack "for" appears in 7% of items and "tape" in 29%, so any threshold that drops the noise drops the word you meant.

**Two requests, not one.** `GET /search?q=` is the literal pass and fires on every keystroke; `&smart=true` follows 400ms after typing settles and is the only one that can reach the model. Typing stays instant and the widened set replaces the results when it finds more — `find.html` then names the terms it also searched for, so a surprising hit is explainable rather than magic. When the literal pass found nothing the page says "No exact match — looking for related items…" rather than flashing "No matches" and contradicting itself a second later.

The expansion model is separate from the vision model (`rack.search.expansion-model`, default `claude-haiku-4-5`): synonyms are a small fast job sitting in front of a search box. If the call fails for any reason — no API key, rate limit, non-JSON reply — the expander logs and returns nothing, and search degrades to the keyword pass rather than erroring.

**Vector search was considered and dropped** (#29, closed). `PartIndex.searchBySimilarity` and `Item.embedding` are gone rather than implemented. Three reasons, in order of weight:

1. **The corpus fits.** The whole index is ~7,400 tokens — see *Asking about a project*. Retrieval solves a corpus that does not fit in the window; this one fits a hundred times over.
2. **Retrieval's failure mode is the one this app cannot have.** "What am I missing" answered over a retriever that drops an item tells you to buy something you already own, invisibly. Cosine similarity also has no equivalent of the all-words rule, so it would reinstate exactly the noise "the resistors shouldn't show up for isolating tape" was about, and an embedding hit is unexplainable where the expander can name the terms it also searched.
3. **The expander is already the semantic layer, and better grounded.** It is handed `vocabulary()` — the words *this rack* uses — where embeddings only know a general semantic space and have no idea the drawer says "electrical tape".

A fourth, practical: Anthropic serves no embeddings endpoint, so it would mean a second provider for a 152-item index, plus an embedding that must be recomputed on every edit — a staleness invariant, which is drift, which is the thing the whole design exists to prevent.

Old slot files still carry a dead `"embedding": null` on each item. Inert, per the no-migrations rule, and `JsonFilePartIndexTest` pins that it loads.

### Optional Git history layer

Committing after each write gives history, per-drawer undo, and free multi-site sync over the existing mesh. Whether to enable this from day one is still open (see below).

### HTTP surface

- `/` → index page (hub)
- `/identify.html`, `POST /identify` → identify a part from a photo, no persistence
- `/projects.html`, `/project.html`, `GET|POST /projects`, `GET|PATCH|DELETE /projects/{id}`, `PATCH /projects/{id}/steps/{n}`, `PATCH /projects/{id}/parts/{n}`, `POST /projects/{id}/notes`, `GET|POST /projects/{id}/settle`, `POST|PATCH|DELETE /projects/{id}/documents`, `POST /projects/{id}/adopt` → a job of work, tracked and settled against stock
- `/ask.html`, `POST /ask` → one question about the whole rack (project checklist); the entire index goes in the prompt. `POST /plan` turns the gaps into per-supplier shopping lists plus steps for the job
- `/find.html`, `GET /search?q=` → search; `&smart=true` widens a query that came up short (see Search above). `POST /search/photo` searches by photo instead of by typing. All three return `{query, expanded_terms, hits}`
- `/put.html`, `GET /c`, `GET /c/{container}`, `GET /c/{container}/{slot}`, `POST /c/{container}/{slot}/photo` → drawer-scoped photo capture and slot state. The photo endpoint (and `POST /suggest`) take **repeated `photo` parts** — one part is just a batch of one.
- `/containers.html`, `POST /c` (register), `PATCH /c/{container}` (name + label scale), `DELETE /c/{container}` → maintain containers; also hosts registration and the label flow below
- `GET /labels/{container}` (preview), `POST /labels/{container}` (mark + archive), `GET /labels/{container}/status` → QR label sheets
- Static pages resize phone photos to ~1600px client-side before upload; keeps below the 20MB per-file multipart cap (`max-request-size` is 100MB, since a batch is one request) and shrinks the vision call.

### Where you are is in the address

Every page used to read its state once out of a query string and never mention it again. Pick a different drawer and the address still named the one you arrived at: Back left the page instead of going back a drawer, a reload put you somewhere else, and a search was a thing you could not send anybody. The state was real and only the address was wrong. `assets/route.js` puts each page's own state in the hash and makes the page follow it — `put.html#rack/A1`, `find.html#q=BC547`, `project.html#quad-606`, `containers.html#rack/labels`, and the container opened on the hub.

**The hash, not the path**, because there is no router behind these pages — they are static files. The fragment is the one part of a URL the server never sees, so a deep link costs no route and no rewrite rule.

**Typing replaces; committing pushes.** Twelve keystrokes in the search box are one search, not twelve places you have been, so the debounced search rewrites the entry it is already on. Pressing Enter, tapping a drawer or opening a panel adds one. Otherwise Back becomes a way of deleting a query one letter at a time.

**A page writes the address as a result of something it has already drawn**, so the `hashchange` its own write causes has nothing left to do — `route.js` remembers the hash it wrote and swallows exactly that event, where a Back button's is followed. put.html follows the whole state rather than patching it: an address naming no drawer clears the selection, and an undivided container's one place is taken as chosen, so the page and the address cannot end up saying different things.

**The query form still works, and always will.** Every drawer in this house carries a printed QR encoding `put.html?c=&s=`, and a sticker cannot be reissued because the code changed — so a page reads a query when it finds one, rewrites it into the hash and drops it from the address. `LabelSheet` goes on printing that form for the same reason: a sticker printed next year reading the same as one printed last year is worth more than a tidy canonical URL. Links made *inside* the app go through `rackRoute.slotHref` and `projectHref`, so one place knows what a drawer link looks like, and `readSlotHref` reads either form — which is what makes the query a real alias rather than a special case at one entry point.

### Labels

Physical paper is always Avery L7160 (A4 21-up, 63.5×38.1mm). Each container declares a `labelScale` in config; content (QR + text) is drawn to `scale × 30mm` QR and `scale × 40pt` font, anchored to the top-left of each L7160 slot so trimming smaller labels for smaller drawers is easy. The QR encodes `<public-base>/put.html?c={container}&s={slot}` — scanning from a phone camera opens the capture page pre-scoped to that slot.

`Slot.printedAt` records when a label was archived via `POST /labels/{container}`. Preview (GET) doesn't touch this. Default scope on both endpoints is `unprinted` — pass `?scope=all` to include already-printed slots (`printed` is used internally to reconstruct sheet position).

**Several labels can share one physical sticker.** `LabelSheet.pack` shelf-packs consecutive labels into an L7160 slot, filling across before dropping to the next row, so a 0.4-scale container puts *four* labels on one sticker as a 2×2 grid to be trimmed apart; at 1.0 nothing packs. Mixed scales pack fine because each label is measured on its own — a full-scale label won't squeeze in beside a small one.

Width is estimated, not measured: the slot id is budgeted at 0.75em per character (Helvetica-Bold caps peak at 0.722em), so the estimate errs wide, and erring wide costs a column rather than causing an overlap.

**A container with one slot is labelled with its name.** Its sticker used to read "1" — the name of a compartment it has not got, printed on the box that *is* the location. `LabelSheet.text` gives an undivided container the name on the front ("Garasje box 1"), and a divided one the slot id, which is what you are looking for once you are standing at the right drawer. Same rule the rest of the UI already follows for a box with no subdivisions.

**And the type wraps, because a name is not an id.** "Kjellerbod box 1" on one line beside a full-size QR sets at 6pt; broken after the first word it sets at 10. `LabelSheet.layout` steps the size down until a wrapping fits both the room beside the QR and the QR's own height, then solves the size exactly for that wrapping — the step decides where the lines break, the arithmetic decides how big the type is, so a label never sits half a point under what it had room for. A single line still lands where a single line always sat: the block is centred on the QR's middle by cap height rather than em box.

**The type gives way when the id will not fit beside its QR.** At scale 1.0 a 30mm QR and 40pt type leave room for two characters on a 63.5mm sticker: "E12" wanted 67.8mm and "Box1" 78.3mm, and both were drawn anyway and ran off the edge — this rack's A10 through E12 are all three characters. The QR keeps its size, because its module size is already the floor on how small a label can go, and the font shrinks to whatever room is left (`Box1` sets at 26pt rather than 40pt and lands 2mm inside the edge). Longer slot ids therefore fit fewer per row — at scale 0.4 a 3-character id like `E12` still gets 2 columns, but the ceiling for 2 columns is scale 0.47 for 3-character ids versus 0.56 for 2-character ones.

**QR module size is the real limit on how small a scale can go.** A slot URL encodes to a 41-module symbol including its quiet zone, so a QR drawn at *S* mm has *S*/41 mm modules and phone cameras want roughly 0.33mm or more. Scale 0.4 gives 12mm → 0.29mm, which is under that; 0.46 gives 13.8mm → 0.34mm and still packs 4-up.

The consequence: **printed labels and consumed stickers are different numbers**, so sheet offsets are counted in stickers.

**Where the next run starts is recorded, not recalculated.** Each print writes a `LabelRun` to `data/label-runs.json` — which container, where on the sheet it began, how many stickers it took — and the next offset comes from the last one. It used to be re-derived by re-packing whatever was currently marked printed, which made a physical fact a function of present state: consolidating one container's two duplicate slots, both already printed, rewound the count by a sticker and aimed the next run at a position already used. A sticker that has been peeled off does not un-peel because a slot was edited. An empty ledger falls back to the old reckoning, so runs made before it existed are not read as an untouched sheet.

**And the sheet can be set by hand.** A part-used sheet is a physical thing the app cannot see, so the labels panel takes a start position, defaulting to the ledger's answer. Count the stickers already gone, top-left to bottom-right, and say which is next.

**Sheet position is global, printing is per container.** A sheet of paper is a shared physical resource, so `resolveOffset` counts stickers consumed across *every* container: print 8 labels for a 0.4-scale container (4 stickers) and the next container's run starts at position 5, whichever container that is. There is deliberately no "print everything" run — not every container gets labels, so printing stays a per-container action. Packing only ever combines labels within one container's run, so a part-filled sticker is never continued by the next container; sharing happens at sticker granularity, not inside one.

### Asking about a project

`POST /ask` (`AskAboutRack`) answers a question about the whole rack rather than one item: *"I am restoring a Quad 606 amplifier. Do I have all the parts?"* It returns a checklist — what the job needs, which drawer each part is in, and what is missing — and `ask.html` renders it with the missing things first.

**The whole index goes into the prompt, and that is the design.** All 152 items with every field come to ~7,400 tokens: two cents at Sonnet rates, and a rounding error against a 1M window. Retrieval exists to solve a corpus that does not fit, and this one fits a hundred times over, so there is no chunking, no top-k and no similarity threshold to tune.

**It is also the only version of this that can be trusted.** The question is *what am I missing*, and a retrieval step that drops one item answers it by telling you to buy something you already own — a false negative invisible in the answer. Handing over everything cannot fail that way. This is the direct argument against vector search here (#29): the failure mode retrieval introduces is exactly the failure mode this question cannot tolerate. If the rack ever outgrows the window, the honest fix is to say so, not to quietly start sampling.

**The model brings what a job needs; the rack brings what is in the drawers.** Those are different kinds of knowledge and the prompt separates them — component values and quantities for a Quad 606 recap are the model's to know, and only the index can say what is on the shelf. So every claim of possession must cite a container and slot, and `AskAboutRack.verify` drops any citation naming an item that drawer does not hold, matching case- and whitespace-insensitively because the model echoes a label rather than a key. A `have` whose every citation was invented is rewritten to `missing` — the one failure this cannot have is sending someone to a drawer for a part that was never in it.

Each line carries how long ago its drawer was last checked, so an answer leaning on a stale reading can say so. Drift is the failure the whole app is built against, and an answer that hides it is worse than no answer.

### The shopping run, and how to do the job

`POST /plan` (`PlanPurchases`) takes what the checklist could not find and returns one list per supplier plus numbered steps for the work. Separate from `/ask` because finding out you already have everything is a complete answer and should not pay for a plan nobody wanted; `ask.html` only offers the button when something is actually missing.

**Which supplier is read off the rack, not guessed.** Descriptions carry where things came from — `Farnell 876-7670` on the Panasonic capacitor, `Clas Ohlson 32-7965` on a mains splitter, Biltema on the crimp terminals — so the inventory itself says where this person shops and for what kind of part. Same move search makes with `vocabulary()`. A hardcoded supplier list would be stale within the year and wrong for whoever runs this next. `rack.shopping.region` (default `Norway`) only says who can deliver here.

**No prices, no totals, no stock claims, no invented order codes.** A model knows none of them, and a confident wrong price is worse than no price — you would carry it to the till instead of looking it up. An order code is the one field that gets pasted straight into a supplier's search box, so `PlanPurchases.vouched` keeps one only when some item's own text already carries it, which makes it a fact about a thing on the shelf. Under four characters is dropped regardless: a listing of 152 items contains every short string, so a match would vouch for anything. Everything else is a search term.

**Citations are resolved, not demanded.** Every listing line begins `lab/10 | …`, so a model asked for a container and a slot separately sometimes hands back the token it read: `{"container": "lab/10", "slot": "lab/10"}`. The first real plan lost **62 of about 100** tool references that way — every one a real item in a real drawer, rejected over punctuation. `AskAboutRack.locate` now tries the pair as given, then either field split on its slash, and repairs the citation into the form the drawer links need. The standard is unchanged: whichever reading is tried, that drawer must hold that item. Fixing it took kept references from 43 to 58, all 58 verifiable against `data/`.

**It takes about two minutes.** ~8k output tokens of supplier lists and twenty steps, at ~5–10¢. A deliberate button press with a status line, not something that fires as you type.

### A project is a thing in the app, not a page of advice

`/projects.html` and `/project.html`, `data/projects/<id>.json`, `Project` in the domain. Asking what a job needs is one moment; a restoration is weeks. Parts arrive on different days from different suppliers, steps get done out of order, and after a fortnight the question is *where was I* — which the plan cannot answer, because it never knew.

Built from what is already on screen. `ask.html` offers **Keep this as a project** after the checklist and **Start this project** after the plan; the parts come from the plan's own supplier lines and the `have` rows of the checklist, never matched between the two by name — they describe the same parts in different words, and guessing which line is which would put the wrong order code beside the wrong part. Nothing is regenerated: a plan you have annotated is worth more than a fresh one.

**It closes the last hole in the drift argument.** Every other mitigation watches the same direction — a photo puts things in, a resync corrects what a camera can see. But stock mostly leaves the rack by being *used*, and a camera was never going to catch that: eight of ten emitter resistors go into an amplifier and the drawer still says ten, correctly recorded, verified last week, wrong. A project is the only thing that knows, so `SettleProject` is where settling up belongs — previewed then applied, the way `ResyncSlot` is, because it takes things away.

**Settling up does not stamp `lastVerified`.** That date means somebody looked, and this is arithmetic on a number that was an estimate off a photograph. Writing today's date on a deduction would turn the app's one honest signal about staleness into a decoration. A row that reaches zero stays at zero rather than being deleted: "none left" is a thing to go and check, and removing the row would take its photographs with it. What settling *cannot* do is listed as `problems` rather than skipped quietly — an item that has since been renamed, a row with no recorded count, a part recorded in two drawers at once.

**Status moves on its own only where the answer is not a matter of opinion.** The last outstanding part arriving ends the shopping. Ticking a step does not: the first steps of a job are reading the manual and photographing the inside, both done while waiting for the post — a real run ticked step one with nineteen parts unordered and the project promptly called itself "building". And ticking the *last* step does not finish a project, because putting the lid back on and knowing it works are different things.

**The parts list is grouped by supplier, because that is what it is for.** Eighteen of the Quad 606's lines come from Farnell, and a card each — repeating "Farnell (element14) — Norwegian account" eighteen times, with a 250-character caveat underneath in full — printed one fact eighteen times and lost the parts among it. One card per supplier now, hairline rows inside, the supplier named once with a Copy button beside it, and each caveat behind a `why / caveats` disclosure: worth keeping, not worth reading twenty times. Groups still owing something come first.

**Having no supplier is not the same as being on the shelf.** The assorted-capacitors line came off the checklist with no supplier and was then marked *ordered*, and it sat under a heading reading "Already in the rack" — the group name contradicting the row beneath it. Rows with no supplier now split by status: `in_stock` and `used` are *Already in the rack*, everything else is *Still to source*.

**The help lives on the thing it helps with**, and only there. Asking what a job needs used to be its own card on the hub; it is help with a project, so having both meant two doors to the same room with the front one leading to a project you had not made yet. The hub now offers Projects, and `ask.html` is reached from the projects page as *"Or start from a question"* — the other way in, for a job easier to describe than to name. A project page runs the same two calls the ask page does — `/ask` for the checklist, `/plan` for the shopping and the steps — and `POST /projects/{id}/adopt` folds the result into *this* project. Before that, a project named by hand started empty and the only way to get parts and steps was to begin somewhere else and be handed a different project.

**Adoption appends and never rewrites.** By the second asking some parts are ordered, some steps are ticked and one carries a note about a lifted pad; a fresh plan knows none of it and could not reproduce it. So nothing replaces a part, a step, a status or a note. Whether a proposed line duplicates one already there is the user's call, made on screen with both visible — the panel unticks likely duplicates (normalised part text, the one field a second reading is likely to phrase the same way) and the server appends exactly what it is given. Cautions are the single exception, deduplicated because they are plain strings with no state to lose.

`assets/advice.js` and `assets/advice.css` hold the asking and the drawing; the two pages keep only their own last step — the ask page starts a project, a project page folds it in. Extracted rather than copied, because the two would drift the way the 1568px resize helper once did. `ask.html` went from 440 lines to 194.

**An item can hold files too, and owns them the way it owns its photographs.** `POST /c/{container}/{slot}/items/{index}/documents` keeps a datasheet on one item; `Item.documents` is where the reference lives, so a move takes it along, a merge unions both rows' files, a resync keeps them (a camera has nothing to say about a PDF), and deleting the item leaves the file unreferenced. `PartIndex.documentsInUse()` is the item half of the union `KeepDocuments.inUse()` asks — a project finishing says nothing about the datasheet still sitting on the chip in B7.

Worth having because the part number on a chip is three millimetres wide and the pinout is not printed on it: the datasheet is the difference between a drawer that says what is in it and one that says what you can do with it. Attaching one does **not** stamp `lastVerified` — downloading a PDF at a desk is not looking in the drawer, and spending the app's one honest staleness signal on a click would be the same mistake settling up avoids.

`assets/itemdocs.js` renders and wires the block, shared by `put.html` and `find.html`. A block replaces itself from the slot the server returns, because the two pages redraw differently — put.html rebuilds its list from stored state, find.html patches rows in place — and a shared piece insisting on either would be wrong on one of them.

**A document is a stored file or a link, never both.** `Document.url` is the other half: `POST /projects/{id}/links` and `POST /c/{c}/{s}/items/{i}/links` note an address instead of keeping bytes. Removal takes a `?ref=` query parameter for both — the filename for a file, the address for a link — because a URL has slashes in it and a path variable cannot carry them.

**A link is a bookmark, not a copy.** Nothing is fetched: rack cannot reach the web, so a link is the address of a page somebody else keeps, and when it goes it goes. Worth having for the manufacturer's page that is always the newest revision, worth knowing about before it is the only record of a part — so the UI badges a link as one rather than letting it look like a file.

**Only `http` and `https`.** These are rendered straight into an `href`, and a `javascript:` address in one runs when it is clicked; escaping the text does not help, because the browser reads the scheme rather than the markup. The check is in `Document`'s constructor, where every path that stores a link has to pass, not in the controller. Links also get `rel="noopener noreferrer"` — where a click came from is nobody's business but the person clicking.

**`DomainErrors` gives the domain's exceptions their proper statuses.** The older endpoints each catch and translate by hand, which is fine until an endpoint is added and does not — typing `javascript:alert(1)` answered *500 Internal Server Error*, the app claiming it had broken when it had worked exactly as designed and refused a bad address. A wrong status is a lie about whose fault it is. `NoSuchElementException` → 404, `IllegalArgumentException`/`IndexOutOfBoundsException` → 400, `IllegalStateException` → 409. Explicit `ResponseStatusException`s are thrown rather than translated, so nothing already deliberate changed.

**Documents are stored, not linked.** A service manual, a schematic, a photograph of the board before it was stripped: `POST /projects/{id}/documents` keeps the file in `data/documents/` and `GET /documents/{filename}` serves it inline, because a manual is for reading at the bench rather than downloading again every time you check a resistor value. Step one of the Quad 606 plan is *download the service manual*; the point of keeping it is that step one never happens twice.

**rack does not offer links to documents, because it cannot check one.** There is no web access anywhere in the app, and the model knows it — asked where to buy compound it answered "I can't browse the internet, but here are reliable sources". URLs from something that cannot open one are guesses, and being sent where the manual is not is worse than not being sent. So the page offers *searches* built from the project's name (a search box always exists) and the finding stays the user's errand. Same rule as `vouched` order codes and `keepReal` citations.

Documents live flat in one folder with one owner, exactly like photographs: `KeepDocuments.inUse()` is the union of what every project names, `ForgetUnusedDocuments` sweeps at boot, deleting a project takes its documents unless another project holds them. The stored name keeps the uploaded one, cleaned to `[A-Za-z0-9._-]` — "Quad-405-2-606-707-service-manual.pdf" is worth recognising in a listing six months later where a timestamp is not — and a collision gets a `_2` suffix, since two revisions of one manual are two documents. `max-file-size` is 60MB: a scanned manual runs to tens of megabytes and a rejected upload is worse than a generous cap on a box nobody else can reach.

**Normalise both sides of a path guard.** `FilesystemDocumentStore` checked `resolved.startsWith(dir)` with `dir` built by `toAbsolutePath()` and `resolved` by `normalize()`. `rack.data-dir` is `./data`, so the `.` survived on one side and not the other, and the guard rejected *every* legal filename in the container while passing every test — `@TempDir` hands out a path with no `.` in it. The test now builds a store on `…/./sub/../nested` deliberately.

**Every change writes to the log.** That is most of the reason to store a project at all: "when did the transistors arrive" and "why did I skip step nine" are the questions, and neither is answerable from current state. A part's status says where it is; the log says how it got there. `ProjectNote.by` separates what the app did from what the user wrote.

### Resyncing a drawer

`AddPhotoToSlot` appends, which is right when you are putting something in a drawer and wrong when you are checking one — shoot a drawer you already filed and you get two of everything. `ResyncSlot` is the other operation: **this is what is in here now.**

It is a diff, in two halves. `POST /c/{container}/{slot}/resync/preview` extracts the batch, lines it up against what is recorded and writes nothing — not even the photos, so a preview you abandon leaves no frames for the real run to clean up. `POST /c/{container}/{slot}/resync` takes back the decisions and is the only half that touches disk. What you confirm is a removal, so it is worth the round trip.

**Replacing the slot outright would be worse than the drift it fixes.** A corrected part number, a hand-written name, the answers stored under Ask AI, the frames an item was seen in — none of it can be redone by a camera. So a kept item keeps everything except its quantity and its frames.

**Matching is one-to-one, greedy, best pair first.** Where both readings carry a part number that is the whole answer: equal is the same part, different is a different part, and no wording overlap may argue otherwise — this drawer holds 100K, 82K, 68K and 15K resistors, and BC547 beside BC557, whose names overlap more than enough to pair. Only where a part number is missing does wording decide it, at half the shorter label's words in common.

**An item kept despite not being in the photos points at no frame.** Overruling a "gone" verdict says the thing is in the drawer, not that it is in these photos; its old frames are about to be deleted, so pinning it to a new one that does not show it would put a lie where the evidence used to be. It shows no strip, which is the honest rendering of an item nobody has a current picture of; if that leaves the batch's frames unclaimed they are swept too. An added item is the one case that may fall back to the first frame, because it demonstrably came out of the batch.

Gone items are removed rather than zeroed, and the old photos are deleted — after the index write, so a crash leaves an orphan file rather than slot state naming a photo that no longer exists.

### Maintaining containers

`/containers.html` lists every container with its slot/item/label counts and is the single place to register, print labels, rename, rescale, or delete one. There is no separate register page.

- **Name and label scale are editable; the slot layout is not.** Reshaping a container would orphan slots that hold items, so `UpdateContainer` only ever rewrites those two fields.
- **Delete refuses while any slot holds items** (`409`, naming the occupied slots in layout order). An item is the claim that something physically exists in there, and hiding that claim is what this refuses to do. A photograph is not such a claim, and can no longer be one on its own: it hangs off an item, so emptying the items empties the pictures with them. A printed label is not content either. `DeleteContainer` checks `PartIndex.all(container)` rather than the current layout, so an item parked in an off-layout slot still blocks it, and the UI disables the button rather than offering a delete the server will refuse.
- Deleting drops the registration only — `data/<container>/` is left on disk, so re-registering the same id picks its slot state (and `printedAt`) back up.
- `server.error.include-message: always` is set so those refusal messages actually reach the browser.

### Deployment

- Frontend: PWA (`getUserMedia` for camera).
- Fronted by Vaier, a home-network reverse proxy that terminates TLS and forwards to the container on the box. The hostname and the address behind it are in the deploy skill under `.claude/`, which is not in the repository — this file is public and an inventory of somebody's garage is not improved by advertising where it lives. It challenges unauthenticated requests, so a **401 from the public URL is the proxy working, not the app failing** — verify against `localhost:8080` and treat the public check as proof only that the proxy still reaches the box.
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

### Starting an add on the tap that starts it

The **Add an item** card on the hub is a `<label>` around a file input, not a link: tapping it opens the camera on that tap, where going to the page first and tapping a camera there costs one tap more. A file cannot cross a navigation, so `assets/photos.js` resizes the batch, hands it over in `sessionStorage` and `put.html` picks it up into the pending strip exactly as if it had been shot there. The stash is taken once, so a reload cannot re-add photographs already filed; if the hand-off fails the page still opens, just empty.

That file is also where `resize()` now lives — the same 1568px helper had been copied into two pages.

### Filing a slot as a batch of photos

That rule needs more than one frame per slot, so `put.html` collects photos into a pending strip — each tap of the camera appends a thumbnail, `×` drops one, and one **File N photos** action sends them all. Nothing is uploaded until then.

**The whole batch goes into a single vision call.** That is what stops a bag shot from the front and its label shot from the side becoming two index entries: the model merges frames into one item and takes the part number off whichever frame it was legible on. It still returns *several* items when the slot holds several different things — merging is per physical item, not per batch.

Each extraction carries an `image_index` back, which `AddPhotoToSlot` maps to the stored filename so `Item.sourcePhoto` points at the frame that actually shows that item. A missing or out-of-range index falls back to the first photo (the model omits it often enough to be worth pinning).

**A frame the extraction attributed nothing to is not kept.** Items own photographs, so such a frame has nothing to hang off — and the model was looking straight at it when it found nothing, which makes dropping it a reading of the picture rather than a guess about it. This reverses an earlier call to keep it as *evidence the extraction missed something*; in practice that produced pictures no page rendered and a container that refused to be deleted, so the frame outlived only itself. The count comes back as `discarded` and `put.html` says so in the status line, because a frame going quiet is the one case where the user needs to know to shoot it again.

**A drawer's own frames are shown whenever it has any**, worked out in the page from the items it already has. Worth showing next to the per-item strips because it is the only place a single photograph can be dropped, and because it makes plain when two items were read from the same shot.

**A photograph nothing points at is deleted.** `ForgetUnusedPhotos` sweeps at boot and every path that can orphan one cleans up after itself, so the rule holds by construction rather than by each of them remembering. "Nothing" means no item names it — one question with one place to ask it.

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
