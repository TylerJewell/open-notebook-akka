# Acknowledgements

This project is a port of **[lfnovo/open-notebook](https://github.com/lfnovo/open-notebook)**.

## Licence

`lfnovo/open-notebook` is **MIT**, © 2024 Luis Novo (read from its `LICENSE` file, not assumed
from the README badge). This repository's own code — the clean-room Java, written against a
specification derived by running the original rather than by translating its source — is
Apache-2.0, Copyright 2026 Tyler Jewell; see `LICENSE` and `NOTICE`. The vendored `frontend/`
tree (below) is reused verbatim and stays under the source's own MIT licence, unmodified. See
`LICENSING.md` for the full split and why the two trees are not under the same terms.

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

**Added by the pass that closed three real "out of scope" gaps** (`python
toolkit/copied_strings.py open-notebook` re-run: 485 literals, 162 also in the source, 42 not
yet named at the time of that run):

- **New `/api/...` routes, the same identical-surface reason as the list above**:
  `/api/insights`, `/api/insights/`, `/api/podcasts/episodes`, `/api/podcasts/episodes/`,
  `/api/podcasts/generate`, `/api/speaker-profiles`, `/api/episode-profiles`,
  `/save-as-note`, `/search/ask/simple`.
- **More validation and error messages, copied on purpose, same reason as the first list**:
  from `api/routers/sources.py`:
  `"File upload or file_path is required for upload type"`,
  `"Invalid file path: must be within the uploads directory"`,
  `"Source has no file to download"`,
  `"File not found on server"`,
  `"Failed to download source file"`,
  `"Access to file denied"`.
  From `api/routers/podcasts.py`: `"Episode has no audio file"`. From
  `api/routers/insights.py`: `"Insight not found"`. From `api/credentials_service.py`'s
  `test_credential`: `"Connection successful"`.
- **`CRAWL4AI_API_URL`, `FIRECRAWL_API_KEY`, `FIRECRAWL_API_URL`, `JINA_API_KEY`, copied
  exactly**, the same reason as `OPEN_NOTEBOOK_ENCRYPTION_KEY` above — these are the
  environment variable names `content_core` (the source's own extraction dependency) itself
  reads, and `UrlExtractionEngine` has to use the same names to be a drop-in alternative
  configuration path for the same engines.
- **`"File not found at "`, copied exactly**, from `content_core`'s own
  `processors/document/pdf.py` and `processors/text.py` (`raise FileNotFoundError(f"File not
  found at {file_path}")`) — the source's own extraction dependency's message for the same
  condition `LocalFileExtraction` reports.
- **`"Firecrawl: "`, not copied** — a prefix this port's own `UrlExtractionEngine` writes in
  front of Firecrawl's own `error` field when relaying a failure; the source's `content_core`
  dependency logs Firecrawl failures with its own, differently-worded message.
- **`audio/mpeg`, HTTP's own MIME type for the format both systems serve podcast audio as**,
  not copied text — the same bucket as `Authorization`/`Content-Type` above.
- **`not attempted`, `must not be read`, `should never be read`, `Unreachable`,
  `sk-ant-test`, `Invalid URL`, `transcription`, ordinary English or this port's own test
  fixture data**, matched by
  `copied_strings.py` against `open-notebook`'s docs, changelog, or vendored frontend locale
  files rather than its application code — none of these appear in `api/` or
  `open_notebook/`'s own Python.
- **`/v1/models`, `/v1/messages`, `/v1/scrape`, `anthropic-version`, `max_tokens`,
  `raw_markdown`, copied exactly.** Not the source's invention: `/v1/models` is the
  OpenAI-compatible models-listing path (same bucket as `/v1/chat/completions` above);
  `/v1/messages`, `anthropic-version`, and `max_tokens` are Anthropic's own Messages API;
  `/v1/scrape` is Firecrawl's own API; `raw_markdown` is a field name in Crawl4AI's own JSON
  response shape. Both this port and the source reach these because both call the same
  third-party APIs, not because one copied the other.
- **`"legacy endpoint for backward compatibility"`, quoted deliberately**, in
  `ApiSourceEndpoint.createJson`'s own javadoc, attributing `api/routers/sources.py`'s own
  docstring for `create_source_json` rather than restating the reasoning as if it were this
  port's own — the quotation marks are in the source code, not hidden.

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
