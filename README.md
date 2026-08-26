# open-notebook-akka

A self-hosted research assistant: notebooks holding sources and notes, an AI provider you
configure once (credentials, models, and a server-wide default per purpose), transformations
that turn a source into insight text, chat and ask over a notebook's own material, search over
embedded chunks, and podcast generation — outline, transcript, and audio — from what a notebook
holds.

A port of [lfnovo/open-notebook](https://github.com/lfnovo/open-notebook) onto **Akka**, built
with **Akka Specify**. This is a complete port, not a slice of one capability: every capability
the original has, this port has, less native mobile apps and third-party chat-platform
integrations (the original has neither) and the heavy, gigabyte-scale extraction engines
(Docling, Crawl4AI, Firecrawl, Jina) this environment cannot provision — see
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

📁 372 Python files (whole project) → **58 Java files, 4,136 lines**<br>
⚡ 0.676 seconds → **0.130 seconds**, submitting a text source and waiting for it to settle (5.2× faster)<br>
🎯 17 of 17 same-answer checks agree, on the original five workloads run against both systems
live — the eleven capabilities added in this pass (§6–§12) are verified by their own
integration tests against a live Akka runtime, not yet by a source-vs-port same-answer bench;
see "Where it differs," below<br>
🖥️ 4 processes (API, worker, SurrealDB, frontend) → **1 process**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](../open-notebook-port/bench/REPORT.md).

---

## What it took to build

Built across more than one session — the original slice, then this pass, which expanded it into
a complete port (SPEC-001 §6–§12).

⏱️ **79.7 hours** wall-clock across every session so far, **2.5** of them active<br>
✍️ **853,104** tokens written by the model across every session so far, this pass included —
refresh with the command below for the final figure once this pass's session log settles<br>
🙋 **0** questions to a human<br>
🧪 **58** tests

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
  extraction produces one (a fetched page's own title), that becomes the source's title.
- **A failed extraction changes only the source's status and error message.** The title, the
  extracted text, and which notebooks the source belongs to are exactly what they were before
  the attempt — nothing is deleted, nothing is guessed at.
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

**3. Open** http://localhost:9072/ui/notebooks/{a notebook id you created}.

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

The service starts on **port 9072**.

### Try it

```bash
NB=$(curl -s -X POST localhost:9072/notebooks -H 'content-type: application/json' \
  -d '{"name":"My Notebook","description":"a notebook"}' | jq -r .notebookId)

curl -s -X POST localhost:9072/sources -H 'content-type: application/json' \
  -d "{\"type\":\"text\",\"content\":\"The quick brown fox.\",\"notebooks\":[\"$NB\"]}"

curl -s localhost:9072/notebooks/$NB/delete-preview
```

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `akka.javasdk.dev-mode.http-port` | `9072` | set in `src/main/resources/application.conf` |
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

- **Extraction is fetch-and-strip only.** The original chooses among several extraction engines
  (Docling for OCR and formulas, Crawl4AI, Firecrawl, Jina) with a plain HTTP fetch as its
  fallback. This port implements only the fallback path — a URL is fetched and reduced to its
  page title and visible text, and a submitted document arrives as plain text already. No
  engine choice is exposed, because none of the others is provisionable in this environment
  (SPEC-001 §1).
- **Every AI provider is called as one OpenAI-compatible HTTP shape, not through 18+ SDKs.** The
  original normalizes providers through Esperanto's `AIFactory`; this port normalizes them one
  level lower, at the HTTP request/response shape, via `Credential.baseUrl` — the same mechanism
  the original already uses for Ollama and LM Studio. See SPEC-001 §6 D-7.
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
- **The original's own frontend is not yet repointed at this port — the largest gap this pass
  did not close.** `/ui/notebooks/{id}` is a minimal server-rendered proof page (from the
  original narrower slice), not the original's Next.js app. The original's API client expects
  snake_case JSON field names and its own route shapes (`GET /api/notebooks` returning
  `source_count`/`note_count`, for instance); this port's endpoints return camelCase
  (`sourceCount`/`noteCount`) at different paths. Reusing the frontend for real — RENDERING.md
  R3 — means adapting that data layer (`frontend/src/lib/api/`), not rebuilding a UI; it is real,
  substantial, remaining work, named here rather than left for someone to discover by running
  the frontend against this port and watching every request fail to parse.
- **A source's sync-processing path is not ported.** The original offers a synchronous
  alternative to its async ingestion path (`async_processing: false`); in this environment that
  path recursed through the original's own request-handling and never completed (see
  question-log.md row 3). This port implements only the async path, which is what the
  original's own documentation recommends for anything beyond trivial use.

---

## Licence

lfnovo/open-notebook is MIT, © 2024 Luis Novo. This port is a derived work; see
`ACKNOWLEDGEMENTS.md`.
