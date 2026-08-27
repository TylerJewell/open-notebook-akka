# Acknowledgements

This project is a port of **[lfnovo/open-notebook](https://github.com/lfnovo/open-notebook)**.

## Licence

`lfnovo/open-notebook` is **MIT**, © 2024 Luis Novo (read from its `LICENSE` file, not assumed
from the README badge). This port is therefore MIT as well; see `LICENSE` in this repository.

## What was copied verbatim, and why

`python toolkit/copied_strings.py open-notebook` found 373 literal strings of ten or more
characters in this port, 120 of them also present in the source. Checked one by one — the
count grew from an earlier 27 when the port expanded from a source-ingestion slice to the
whole system (every capability now has its own endpoint, entity, and error strings):

- **Validation and error messages, copied on purpose**, so that a caller of this port's API
  sees the same wording a caller of the original does for the same mistake. The source's own
  Pydantic `field_validator` messages (`open_notebook/domain/notebook.py`):
  `"Notebook name cannot be empty"`, `"Note content cannot be empty"`. Not-found messages,
  one per resource: `"Notebook not found"`, `"Source not found"`, `"Note not found"`,
  `"Credential not found"`, `"Model not found"`, `"Transformation not found"`,
  `"Episode not found"`. From `api/routers/sources.py`'s `_build_content_state`:
  `"Content is required for text type"`, `"URL is required for link type"`. From
  `Source.add_insight`: `"Insight type and content must be provided"`. Other field checks:
  `"Query cannot be empty"`, `"Title cannot be empty"`, `"Model name cannot be empty"`,
  `"Notebook ID must be provided"`. Auth failures (`api/auth.py`): `"Invalid password"`,
  `"Missing authorization header"`, `"Invalid authorization header format"`. Model lookup
  failures (`open_notebook/domain/models.py`): `"Model with ID "`, `"Invalid model type: "`,
  `"No model configured"`. The tail half of the extraction-failure message,
  `"...inaccessible, or in an unsupported format."` (`open_notebook/graphs/source.py`).
  Confirmation strings: `"Notebook deleted"`, `"Credential deleted"`,
  `"Podcast generation started"`.
- **`"Processing..."`, copied exactly.** This is the literal placeholder title the source
  assigns a source record before extraction runs (`Source.title` default in
  `api/routers/sources.py`), and R3 of `specs/SPEC-001-open-notebook.md` is a rule about that
  exact string — copying anything else would have been porting a different placeholder.
- **Every `/api/...` route this port's `Api*Endpoint` classes expose is the source's own
  path**, byte for byte, base and `/{id}` form alike: `/api/notebooks`, `/api/notebooks/`,
  `/api/notes`, `/api/notes/`, `/api/sources`, `/api/sources/`, `/api/credentials`,
  `/api/credentials/`, `/api/models`, `/api/models/`, `/api/transformations`,
  `/api/transformations/`, `/api/chat/sessions`, `/api/chat/sessions/`, `/chat/sessions/`,
  `/credentials/`, `/transformations/`, `/episode-profiles`, `/episode-profiles/`,
  `/speaker-profiles`, `/speaker-profiles/`, `/podcasts/episodes`, `/podcasts/episodes/`,
  `/podcasts/generate`, `/search/ask`, `/providers`, `/languages`, `/capabilities`,
  `/auth/status`, `/auto-assign`, `/by-provider/{provider}`, `/count/{provider}`,
  `/discover/{provider}`, `/sync/{provider}`, `/env-status`, `/migrate-from-env`,
  `/migrate-from-provider-config`, `/recently-viewed`, `/default-prompt`,
  `/embeddings/rebuild`, `/delete-preview`, plus the earlier `/notebooks`, `/notebooks/`. This
  whole surface exists *to be identical* — it is what lets the vendored frontend (see below)
  talk to this port without knowing it is not talking to the original, which is the entire
  point of the adapter layer described in the vendored-frontend section.
- **The same `Api*Endpoint` responses use the source's own snake_case JSON field names**,
  not this codebase's usual camelCase, for the same reason: `notebook_id`,
  `default_chat_model`, `default_embedding_model`, `delete_exclusive_sources`,
  `speech_to_text`, `text_to_speech`, `ai_message`, `context_indicators`. A field the vendored
  frontend reads by name has to be spelled the way the frontend spells it.
- **`OPEN_NOTEBOOK_ENCRYPTION_KEY` and `OPEN_NOTEBOOK_PASSWORD`, copied exactly**, because they
  are the environment variable names the source itself reads for encryption-at-rest and the
  shared-password gate (SPEC-001 §6, §11) — a deployment setting this port's own
  `README.md` documents has to use the source's names to mean the same thing.
- **`/v1/chat/completions`, `/v1/embeddings`, `/v1/audio/speech`, copied exactly.** These are
  the OpenAI API's own paths, not the source's invention; both systems reach them because
  `AiClient` (SPEC-001 §6 D-7) and the source's Esperanto library both call the same
  OpenAI-compatible third-party shape.
- **`OpenRouter`, `Together AI`, `openrouter`** are the real names of AI provider services
  both systems integrate with — not copied text, the same way two address books both spell a
  city's name the same way.
- **REST path segments and kebab-case type identifiers** (`/notebooks/`, `deleteExclusiveSources`,
  `default-prompt`, `episode-profile`, `speaker-profile`, `podcast-generation`,
  `transformation`) are the ordinary vocabulary of the domain (a notebook resource, a delete
  flag, an entity's own name in kebab case) expressed the conventional way both systems
  already use — not copied text, independently arrived at the way two implementations of the
  same interface both end up saying `GET /users/{id}`.
- **`Authorization` and `Content-Type`** are HTTP's own header names, not the source's
  vocabulary.
- **`NEVER return API key values from any endpoint`** is a comment in this port's own
  `CredentialEndpoint.java` stating the same invariant the source's credential router
  enforces (an API key is written, never read back) — the sentence describes a shared rule,
  not copied source.
- **HTML's own boilerplate**, not the source's wording — the fixed opening and closing tags
  every HTML document uses: `<!DOCTYPE html><html><head><meta charset="utf-8">` and
  `</title></head><body>` (`NotebookPageEndpoint`'s minimal page shell), and
  `<html><body></body></html>` (an empty-document test fixture in `ExtractionTest`).
- **`" failed: HTTP "` and `" segments."`**, fragments of this port's own error-message and
  log-message sentences in `AiClient`/`PodcastGenerationWorkflow` (`"…request failed: HTTP
  " + status`, `"…split into " + n + " segments."`) — punctuation-and-preposition fragments
  ordinary enough that the source's own strings contain them too, not sentences copied whole.
- **`"transcript"`**, the ordinary English word for what a podcast episode's script is,
  used as a field/step name in `PodcastGenerationWorkflow` the same way the source uses it.
- **Everything else in the list is test fixture or benchmark data this port wrote itself**
  (`2026-01-01T00:00:00Z`, `"Hello there"`, `"Summarize this"`, `"A one-line summary"`,
  `"Bearer secret"`, `"machine learning"`, `"note content"`, `"unreachable"`,
  `"url-source"`, `"all_seconds"`, `"recently viewed"`) — shared with the source's own test
  suite or benchmark by coincidence of being ordinary placeholder text, not lifted from it.

No prompt, fixture file, schema definition, or test corpus was copied. The behaviour throughout
this port is derived from the source — that is the entire premise of a port — and is not
claimed as independent invention anywhere; `specs/SPEC-001-open-notebook.md` cites the specific
file and line range behind every rule.

## The vendored frontend

`frontend/` is a verbatim copy of `lfnovo/open-notebook`'s own `frontend/` directory
(RENDERING.md R3 — the interface that already exists is the one the port ships). Every
component, style, route, and static asset is unchanged from the source. Two files were edited,
both purely at the data layer (RENDERING.md R4):

- `frontend/src/lib/api/sources.ts` — source creation sends a JSON body instead of
  `multipart/form-data` (this SDK version's HTTP endpoints have no multipart-parsing hook; see
  the README's "Where it differs").
- `frontend/src/lib/hooks/use-sources.ts` — `useSourceStatus` subscribes to this port's SSE
  status stream instead of polling every 2 seconds (RENDERING.md R1).

`frontend/package.json`, every locale file, every component, and the full `src/app` route tree
are copied unmodified. See `open-notebook-port/specs/RENDER-001-open-notebook.md` for the full
R1/R3/R5 compliance record.

## Also used

- Akka
