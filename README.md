# open-notebook-akka

Turns a submitted document or URL into searchable notebook content, tracks whether that turned
it into text or into a named failure, and answers what happens to everything a notebook holds
when the notebook itself is deleted.

A port of [lfnovo/open-notebook](https://github.com/lfnovo/open-notebook) onto **Akka**, built
with **Akka Specify**.

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

📉 1,124 Python lines (behavioural slice) → **1,110 Java lines**<br>
📁 372 Python files (whole project) → **18 files**<br>
⚡ 0.676 seconds → **0.130 seconds**, submitting a text source and waiting for it to settle (5.2× faster)<br>
🎯 17 of 17 same-answer checks agree, across four workloads run against both systems live<br>
🖥️ 4 processes (API, worker, SurrealDB, frontend) → **1 process**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](../open-notebook-port/bench/REPORT.md).

---

## What it took to build

⏱️ **1.7 hours** from the first command to the published repository, **1.2** of them active<br>
💬 **623** exchanges with the model<br>
✍️ **354,396** tokens written by the model, **200,960,080** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **38** tests

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

Everything this port needs, beyond a port number:

| Variable | Default | Notes |
|---|---|---|
| `akka.javasdk.dev-mode.http-port` | `9072` | set in `src/main/resources/application.conf` |

No model provider key is required — this slice does not call a language model (see
"Where it differs", below).

---

## Where it differs from lfnovo/open-notebook

Everything not listed here behaves the same way on purpose, including the parts that look like
mistakes.

- **Extraction is fetch-and-strip only.** The original chooses among several extraction engines
  (Docling for OCR and formulas, Crawl4AI, Firecrawl, Jina) with a plain HTTP fetch as its
  fallback. This port implements only the fallback path — a URL is fetched and reduced to its
  page title and visible text, and a submitted document arrives as plain text already. No
  engine choice is exposed, because none of the others is implemented.
- **No embeddings, and no full-text or vector search.** The original embeds a source's or
  note's content for search once it is saved. This port has no embedding model and no search
  index; a source's or note's content is stored and returned, never indexed.
- **No transformations, and no AI-authored insight text.** The original runs a language model
  over a source to produce insight text, which can then become a note. This port accepts
  insight text as already produced and ports only the record-keeping around it — attaching it
  to a source, and turning it into a note — not the model call that would generate it.
- **No chat sessions.** The original links chat sessions to a notebook and deletes them when the
  notebook is deleted. This port never creates a chat session, so that step of a notebook's
  delete cascade is not present — not because chat is unimportant, but because it is a different
  capability from source ingestion, note generation, and a notebook's own state.
- **A source's own delete cascade fails soft, matching the original.** If deleting one exclusive
  source fails partway through a notebook's cascade, both systems continue with the rest of the
  notebook's sources and notes rather than aborting the whole delete — checked directly rather
  than assumed, since it is the kind of asymmetry a rebuild easily flattens by accident.
- **A source or note's screen is a plain, unstyled page, not the original's designed interface.**
  A person watching the original's frontend sees a source's title and processing status
  rendered in its own design system; this port renders the same facts — title, status — in
  plain HTML at `/ui/notebooks/{id}`, proving the capability is reachable outside a test without
  reproducing the original's visual design. See `gui/manifest.json` in the specifications
  repository.
- **A source's sync-processing path is not ported.** The original offers a synchronous
  alternative to its async ingestion path (`async_processing: false`); in this environment that
  path recursed through the original's own request-handling and never completed (see
  question-log.md row 3). This port implements only the async path, which is what the
  original's own documentation recommends for anything beyond trivial use.

---

## Licence

lfnovo/open-notebook is MIT, © 2024 Luis Novo. This port is a derived work; see
`ACKNOWLEDGEMENTS.md`.
