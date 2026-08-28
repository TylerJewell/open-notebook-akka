# open-notebook-akka

A self-hosted research assistant: notebooks holding sources and notes, an AI provider you
configure once (credentials, models, and a server-wide default per purpose), transformations
that turn a source into insight text, chat and ask over a notebook's own material, search over
embedded chunks, and podcast generation — outline, transcript, and audio — from what a notebook
holds.

A port of [lfnovo/open-notebook](https://github.com/lfnovo/open-notebook) onto **Akka**, built
with **Akka Specify**. This is a complete port, not a slice of one capability: every capability
the original has, this port has, less native mobile apps and third-party chat-platform
integrations — the original has neither. AI provider connectivity (including a real Anthropic
translation, not just OpenAI-compatible ones), every URL-extraction engine that is a plain HTTP
call underneath, and file-based sources are all implemented for real; a narrower, precisely
named remainder — DOCX/PPTX/XLSX (a Java parser library this build environment's Maven cannot
resolve), OCR/scanned-PDF extraction and local browser-automation crawling (ML/browser runtimes
this port does not embed), and audio/video transcription (the same) — is checked and named
rather than assumed. See
[`specs/SPEC-001-open-notebook.md`](../open-notebook-port/specs/SPEC-001-open-notebook.md) §1
for the exact boundary and why.

---

## Where it came from

lfnovo/open-notebook is a self-hosted, privacy-focused research assistant — an alternative to
Google's NotebookLM that lets a person collect sources, generate notes from them, and organize
both inside notebooks. It was ported to derive a specification precise enough to regenerate a
system on a different stack — the port is the vehicle, the specification is the deliverable.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness) under
`open-notebook-port/`.

---

## lfnovo/open-notebook → this port

📁 318 source files, 54,067 lines (`open_notebook/`, `api/`, `commands/`, `frontend/src`,
`frontend/public`; `node_modules` and the `.next` build output excluded as generated) →
**81 Java files, 6,511 production lines** (30 files, 2,381 lines of tests, reported apart)<br>
⚡ 0.676 seconds → **0.130 seconds**, submitting a text source and waiting for it to settle (5.2× faster)<br>
🎯 46 of 47 same-answer checks agree, across sixteen workloads run against both systems live —
the four original source-ingestion workloads plus the twelve SPEC-001 §6–§12 added (auth,
credentials, models, transformations, chat, ask, search, podcasts, settings); the one
disagreement is a mock-provider fidelity boundary, not a behavioral difference — see
[`bench/REPORT.md`](../open-notebook-port/bench/REPORT.md) §1b. A later pass's file-source,
insight, and credential-discovery additions were spot-checked live against the running
original rather than folded into these sixteen formal workloads yet — see `bench/REPORT.md`
§5 for exactly what that pass checked and found, including one real bug it fixed.<br>
🖥️ 4 processes (API, worker, SurrealDB, frontend) → **1 process**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](../open-notebook-port/bench/REPORT.md).

---

## What it took to build

Built across more than one session — the original slice, then a pass that expanded it into a
complete port (SPEC-001 §6–§12), then a pass that closed the RENDERING.md R3 gap (the
vendored, repointed frontend), then a pass that re-ran step e's same-answers-first and size
measurements against the complete-port build rather than the slice they had described since
the expansion, then a pass that independently re-verified every mechanical gate and closed a
`.vendored`/`copied_strings.py` disclosure gap the frontend vendoring had left open, then a
pass that renamed the one JUnit test class whose name fell outside Surefire's default include
pattern so the ordinary `mvn test` lifecycle actually runs it, then this pass, which closed
three real gaps SPEC-001 §1 had previously folded into "out of scope" without checking them by
running anything (multipart's absence confirmed by decompiling the SDK jar rather than
assumed; file-based sources, three real URL-extraction engines, a native Anthropic protocol,
and real credential connectivity checks implemented in their place), fixed one real bug this
pass's own live comparison against the original found (a file source's title defaulting to its
own filename, not staying blank), and added the global insight-id, `sources/json`,
`sources/{id}/download`, and `search/ask/simple` routes SPEC-001's own conformance table had
not yet covered (`bench/REPORT.md`).

⏱️ **109.8 hours** wall-clock across every session `toolkit/tokens.py` has indexed so far,
**5.3** of them active — this pass's own session is still open and is not yet in that
figure; refresh it again once `toolkit/tokens.py --port open-notebook` can see it<br>
✍️ **2,018,909** tokens written by the model across every indexed session so far<br>
🙋 **0** questions to a human<br>
🧪 **93** backend tests (59 unit + 34 integration), **140** frontend tests (23 files, unmodified
by this port beyond the two files RENDERING.md R4 sanctioned changing)

```bash
python toolkit/tokens.py --port open-notebook    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](../port-log).

---

## What it does

From the specification:

- **A source exists, in a `NEW` state, linked to every notebook named for it, before extraction
  ever runs.** Submitting a source never waits on the network; what comes back is a record you
  can already see, even if extraction later fails.
- **A caller-supplied title survives extraction; a placeholder title does not.** Submit a
  source with your own title and it stays exactly as written. Leave it blank and, once
  extraction produces one (a fetched page's own title, or a file source's own filename), that
  becomes the source's title.
- **A failed extraction changes only the source's status and error message.** The title, the
  extracted text, and which notebooks the source belongs to are exactly what they were before
  the attempt — nothing is deleted, nothing is guessed at.
- **A source can also be a file already placed inside the server's own uploads directory**, with
  the same location guard the original enforces; its text (plain, or a PDF's own text layer) is
  read and titled by its own filename when nothing else supplies one, checked against the real
  original rather than assumed.
- **A URL is fetched through whichever extraction engine is configured** — a plain fetch, or a
  real call to Jina Reader, Firecrawl, or a self-hosted Crawl4AI server, the three of the
  original's four alternatives that are a plain HTTP call underneath.
- **Deleting a notebook always deletes every note it holds, and classifies each source as
  exclusive or shared by which notebooks it is linked to right now.** An exclusive source can be
  deleted along with the notebook; a shared one is only unlinked, and survives in whatever other
  notebook still holds it.
- **A preview of a notebook's deletion reports exactly the counts the deletion itself will
  produce**, without changing anything — asked before committing, and correct.
- **Deleting a notebook also deletes every chat session linked to it**, the same
  unconditional rule as notes.
- **A credential's API key is encrypted before it is ever written to storage, and no endpoint
  returns it** — only whether one is set.
- **Running a transformation calls the source's configured (or default) model for real** and
  records the reply as a new insight — the same insight a source's own record-keeping already
  tracked, now actually produced by a language model instead of supplied by the caller.
- **Chat and ask assemble a notebook's sources, notes, and insights into one context block**,
  tagged with citation-style ids (`[source:id]`, `[note:id]`), and hand it to the configured
  model alongside the conversation so far.
- **Vectorizing a source or note chunks its text, embeds each chunk, and makes it rankable by a
  later search query** — cosine similarity over every stored chunk, highest first.
- **Generating a podcast episode produces an outline, then a transcript from the outline, then
  audio from the transcript**, recording each artifact as it completes and ending `COMPLETED`
  with all three present, or `FAILED` with why.
- **Set `OPEN_NOTEBOOK_PASSWORD` and every endpoint requires `Authorization: Bearer
  <password>`; leave it unset and the gate is off entirely** — checked in constant time either
  way.

See [`specs/SPEC-001-open-notebook.md`](../open-notebook-port/specs/SPEC-001-open-notebook.md)
§§6–12 for the full rule list behind each of these, and what evidence backs each rule.

Generated documentation lives at [`docs/index.html`](docs/index.html) — open it in a
browser for the entity diagram, the interaction path, and the component reference.

---

## Design decisions

**The extraction fetch runs in a workflow, not inside the entity that owns the source's state.**
A fetch to another server can be slow or fail in ways a state transition should not have to
know about, so it happens as its own step and only reports back what it found.

**Every timestamp a rule needs is handed in, never read from the system clock inside the code
that uses it.** That is what lets every rule about a source's lifecycle be tested against a
fixed moment in time, rather than against whatever the clock happens to read that day.

**A notebook's own list of sources and notes lives on the notebook, not in a separate index.**
Deleting a notebook needs to know everything it holds, and keeping that list on the notebook
itself answers that with one read instead of a search across every source and note that exists.

**A failed attempt to delete one source does not stop the rest of a notebook's cleanup.** The
original works the same way — one stuck source is a reason to skip it, not a reason to leave
every other source and note in the notebook untouched.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/open-notebook-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:9155/ui/notebooks/{a notebook id you created}.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9155**.

### Try it

```bash
NB=$(curl -s -X POST localhost:9155/notebooks -H 'content-type: application/json' \
  -d '{"name":"My Notebook","description":"a notebook"}' | jq -r .notebookId)

curl -s -X POST localhost:9155/sources -H 'content-type: application/json' \
  -d "{\"type\":\"text\",\"content\":\"The quick brown fox.\",\"notebooks\":[\"$NB\"]}"

curl -s localhost:9155/notebooks/$NB/delete-preview
```

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `akka.javasdk.dev-mode.http-port` | `9155` | set in `src/main/resources/application.conf` |
| `OPEN_NOTEBOOK_ENCRYPTION_KEY` | *(required)* | any string; a credential's API key cannot be stored without it |
| `OPEN_NOTEBOOK_PASSWORD` | unset (auth disabled) | set it to require `Authorization: Bearer <password>` on every endpoint |

No model provider account is required to build or test this port — every AI-provisioning test
runs against `probes/mock_provider.py` (an OpenAI-compatible fixed-response server) in the
specifications repository. To use it against a real provider, create a `Credential` with that
provider's `baseUrl` and API key, then a `ModelRecord` linked to it (`POST /credentials`,
`POST /models`) — see SPEC-001 §6.

---

## Where it differs from lfnovo/open-notebook

Everything not listed here behaves the same way on purpose, including the parts that look like
mistakes.

- **Three of the original's four alternative URL-extraction engines are real; the fourth's local
  mode is not, and Docling's OCR/vision path is not.** Jina Reader, Firecrawl, and a
  remote/self-hosted Crawl4AI server are each a real HTTP call (`UrlExtractionEngine`), selected
  the same way the original selects them. Crawl4AI's own local mode is browser automation (a
  bundled Chromium) with no remote counterpart when unconfigured, and Docling's OCR/vision/table
  models have no remote mode in the original at all — both are an ML/browser runtime this port
  does not embed, reported as exactly that when selected rather than silently downgraded to the
  plain fetch. See SPEC-001 §1.
- **DOCX/PPTX/XLSX file sources are not readable; plain text and a PDF's own text layer are.**
  Apache POI would read them; this build environment's Maven cannot resolve it (confirmed by
  actually running `mvn dependency:get` for it, which timed out with no artifact retrieved).
  Rejected by name at ingestion time rather than silently mis-read as text. See SPEC-001 §1.
- **Every AI provider is called as one OpenAI-compatible HTTP shape, except Anthropic, which is
  translated to its own native Messages API.** The original normalizes 22 providers through
  Esperanto's `AIFactory`; this port normalizes most of them one level lower, at the HTTP
  request/response shape, via `Credential.baseUrl` — the same mechanism the original already
  uses for Ollama and LM Studio — and translates Anthropic's genuinely different wire protocol
  directly, proving the design extends past the default shape rather than being hard-walled to
  it. See SPEC-001 §6 D-7.
- **A credential's `test`/`discover` always call the credential's own configured base URL; the
  original special-cases them per provider name.** The original hardcodes a test model per
  provider for `test` (a failed call can still classify as success) and only its
  `openai_compatible`/`anthropic_compatible` pseudo-providers honor a custom base URL for
  `discover` — a credential named `openai` with a custom base URL is discovered against the
  real api.openai.com there. This port's `baseUrl` always determines where a call goes, for
  every provider name, checked by driving the real original with a mock provider at a custom
  `base_url` and getting an empty catalog back where this port would not. See SPEC-001 §6 D-7.
- **Search ranks with a linear cosine scan over every stored chunk, not an index.** The original
  uses SurrealDB's indexed `vector_search`/`text_search`. Correct at this port's data volumes,
  not at the original's indexed scale. See SPEC-001 §9 D-9.
- **A podcast episode has one narrator voice and one text-to-speech call over the full
  transcript, not per-segment multi-speaker synthesis.** The full outline → transcript → audio
  pipeline is real; per-speaker segment assignment is a narrower rebuild of the audio-synthesis
  step. See SPEC-001 §10 D-10.
- **Ask hands the model the notebook's full assembled context, not a multi-query
  search-then-answer step.** This is what the original's own chat already does whenever a caller
  does not narrow the context; wiring ask's answer step to search's ranked results (§9) instead
  of full context is the concrete next increment. See SPEC-001 §8 D-8.
- **The password gate is a per-endpoint-method check, not one middleware.** This SDK version's
  HTTP endpoint base class has no request-level filter hook; `AuthGuard.check(requestContext())`
  is called explicitly as the first statement of every state-changing or state-reading method
  instead. Same rule, checked at a different point in the request path. See SPEC-001 §11 D-12.
- **Languages are drawn from the JVM's own locale database, not CLDR via `babel`/`pycountry`.**
  Same capability (a BCP-47 code plus a display name), a different locale source, so exact code
  coverage and some display-name wording differ. See SPEC-001 §12.
- **A source's own delete cascade fails soft, matching the original.** If deleting one exclusive
  source fails partway through a notebook's cascade, both systems continue with the rest of the
  notebook's sources and notes rather than aborting the whole delete — checked directly rather
  than assumed, since it is the kind of asymmetry a rebuild easily flattens by accident.
- **The original's own frontend is vendored and repointed at this port (RENDERING.md R3).**
  `frontend/` is `lfnovo/open-notebook`'s own Next.js app, unmodified in components, styling,
  routes and assets. A full snake_case-speaking `/api/*` adapter layer (`Api*Endpoint` classes,
  alongside the original camelCase bare-path endpoints, which are untouched and still tested)
  gives it the routes and field names it expects; `frontend/src/lib/api/sources.ts`'s source
  creation is the one sanctioned data-layer change beyond field renaming — a JSON body instead
  of `multipart/form-data`, since this SDK version's HTTP endpoints have no multipart-parsing
  hook, confirmed by decompiling the SDK jar rather than assumed (SPEC-001 §1). A file already
  placed inside the uploads directory — the original's own `file_path` field, real here too —
  still works from the browser; only a freshly-picked local file, which needs the raw multipart
  body, does not. `useSourceStatus` subscribes to a real
  SSE endpoint (`GET /api/sources/{id}/status/stream`, backed by `SourceEntity`'s own
  `NotificationPublisher`) instead of polling every 2 seconds, satisfying RENDERING.md R1 for
  the one polling call site SPEC-001's own rules govern. See `specs/RENDER-001-open-notebook.md`
  in the port folder for the full R1/R3/R5 compliance record, including what was checked and
  what a probe found only partially reproducible.
- **Podcast outline and transcript text is stored as the model returned it, not validated
  against a schema.** The original parses each step's reply as JSON against a Pydantic model —
  a segment's `size` must be one of `short`/`medium`/`long`, and a transcript line's `speaker`
  must name one of the episode's actually configured speakers — and fails the whole generation,
  with no partial artifact kept, if the model's reply doesn't comply. This port's `AiClient`
  returns the model's raw text and stores it directly; a differently-shaped or off-roster reply
  from a real provider would be accepted here where the original would reject it. Both sides
  were driven against the same fixed-response test double
  (`open-notebook-port/probes/mock_provider.py`) and the difference is real, not a benchmark
  artifact — the original's validation genuinely goes further than this port's. See
  `bench/REPORT.md` §1b for the run that found it.
- **A source's sync-processing path is not ported.** The original offers a synchronous
  alternative to its async ingestion path (`async_processing: false`); in this environment that
  path recursed through the original's own request-handling and never completed (see
  question-log.md row 3). This port implements only the async path, which is what the
  original's own documentation recommends for anything beyond trivial use.

---

## Licence

lfnovo/open-notebook is MIT, © 2024 Luis Novo. This port is a derived work; see
`ACKNOWLEDGEMENTS.md`.
