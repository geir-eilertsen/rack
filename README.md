# rack

A small-parts inventory you build by taking photographs.

Open a drawer, photograph what's in it, and a vision model writes down what it sees. Everything becomes searchable — by part number, by a half-remembered description, or by holding the part up to the camera when you can't name it at all.

Live at <https://rack.apalveien5.eilertsen.family/> (behind a home-network reverse proxy).

## The problem it solves

Sixty drawers of components, and the only index is your memory of which one holds the 100K resistors. Cataloguing them by hand is the kind of chore that gets abandoned three drawers in, so this asks for a photograph instead of a form.

The photo is ground truth. The extracted data is an index over it — when the two disagree, the photo wins, and it's still there to check.

## What it does

- **File a drawer from several photos at once.** The part from the front, the same part from the side, its label on a third frame — one vision call merges them into one item, so a bag photographed twice isn't two entries. Photograph the labels as much as the parts; printed text is what the model reads reliably.
- **Search that bridges your words to the drawer's.** Ask for "isolating tape" and get the electrical tape. Every word of a query has to match, and a query that finds nothing is widened into wording the rack actually uses — grounded in this rack's own vocabulary rather than generic synonyms.
- **Search by photograph.** For the part in your hand that you can't name, which is exactly the case typing serves worst.
- **Take stock in two taps.** `−` on a search result drops the count and re-dates the drawer, no form.
- **QR labels.** Scan a drawer with a phone camera to jump straight to that slot. Small labels share a sticker, four to an Avery L7160 slot, ready to trim.
- **A running cost.** Every model call is tallied per model at the foot of every page.

## The failure mode it's designed against

Drift. A part is taken, the record isn't updated, six months later the index lies and you stop trusting it — at which point the whole thing is worse than useless, because you check it *and* the drawer.

Two mitigations, both load-bearing:

1. Updating a drawer is tap, scan, shoot, done. No forms.
2. Every slot carries a `last_verified` date, surfaced in search results, so you can see when the data was last real.

## Running it

```sh
./mvnw spring-boot:run                  # http://localhost:8080
./mvnw test
```

```sh
docker build -t rack:local .
docker run --rm -p 8080:8080 \
  -v "$PWD/data:/app/data" \
  -e ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" \
  rack:local
```

`ANTHROPIC_API_KEY` is the only required setting. `RACK_DATA_DIR` moves the data directory; `RACK_PUBLIC_BASE_URL` is what the QR codes point at. The app boots without an API key — it just can't read a photo until it has one.

**The `-v` mount is load-bearing.** Slot state, photos and archived label sheets live in `data/` and are the only copy.

## How it's built

Spring Boot, Java 21, hexagonal — three ports, all swappable: `ImageStore`, `PartExtractor`, `PartIndex`. An ArchUnit test enforces the layering, keeps the domain free of any framework import, and fails the build on a package cycle.

**No database.** Sixty slots of twenty items is about 1,200 records — a data structure, not a database. State is one JSON file per slot, written to a `.tmp` and moved atomically. `grep -ri "BC547" data/` is a valid diagnostic, and fixing a mislabelled part is a text edit. There are no schema migrations: fields get added and their absence tolerated, so a record written before a field existed still reads.

**Different models for different jobs.** Reading photographs is worth paying for — asked to read a tool drawer, a cheaper model returned a part number that wasn't there, at high confidence, which is precisely the drift the design exists to prevent. Expanding a search query is a synonym lookup and runs on the cheapest model available. Each is a separate setting.

`CLAUDE.md` carries the reasoning behind these decisions in full, including the ones that were measured and rejected.

## Status

Working end-to-end. Open items are tracked as [GitHub issues](https://github.com/geir-eilertsen/rack/issues).
