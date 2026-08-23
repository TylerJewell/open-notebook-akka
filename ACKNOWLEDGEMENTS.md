# Acknowledgements

This project is a port of **[lfnovo/open-notebook](https://github.com/lfnovo/open-notebook)**.

## Licence

`lfnovo/open-notebook` is **MIT**, © 2024 Luis Novo (read from its `LICENSE` file, not assumed
from the README badge). This port is therefore MIT as well; see `LICENSE` in this repository.

## What was copied verbatim, and why

`python toolkit/copied_strings.py open-notebook` found 27 literal strings of ten or more
characters shared between the two codebases. Checked one by one:

- **Validation and error messages, copied on purpose**, so that a caller of this port's API
  sees the same wording a caller of the original does for the same mistake:
  `"Notebook name cannot be empty"` / `"Note content cannot be empty"` (the source's own
  Pydantic `field_validator` messages, `open_notebook/domain/notebook.py`), `"Notebook not
  found"` / `"Source not found"` / `"Note not found"`, `"Content is required for text type"` /
  `"URL is required for link type"` (from `api/routers/sources.py`'s `_build_content_state`),
  `"Insight type and content must be provided"` (`Source.add_insight`), and the tail half of
  the extraction-failure message, `"...inaccessible, or in an unsupported format."`
  (`open_notebook/graphs/source.py`).
- **`"Processing..."`, copied exactly.** This is the literal placeholder title the source
  assigns a source record before extraction runs (`Source.title` default in
  `api/routers/sources.py`), and R3 of `specs/SPEC-001-open-notebook.md` is a rule about that
  exact string — copying anything else would have been porting a different placeholder.
- **REST path segments** (`/notebooks`, `/notebooks/`, `deleteExclusiveSources`) are the
  ordinary vocabulary of the domain (a notebook resource, a delete flag) expressed in the
  conventional REST shape both systems already use — not copied text, independently arrived
  at the way two implementations of the same interface both end up saying `GET /users/{id}`.
- **Everything else in the list is test fixture data this port wrote itself**
  (`"The quick brown fox jumps over the lazy dog."`, `"hello world"`, `"Extracted Title"`,
  timestamps, HTTP header names, JSON field names in this port's own benchmark output) — shared
  with the source's own test suite or benchmark by coincidence of being ordinary placeholder
  text, not lifted from it.

No prompt, fixture file, schema definition, or test corpus was copied. The behaviour throughout
this port is derived from the source — that is the entire premise of a port — and is not
claimed as independent invention anywhere; `specs/SPEC-001-open-notebook.md` cites the specific
file and line range behind every rule.

## Also used

- Akka
