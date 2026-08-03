"""Type-specific chunking for the docs corpus.

- RFCs: split on level-2 (`##`) headers; any resulting section longer than
  ~800 tokens is further split with ~50-token overlap so retrieval never has
  to embed/compare an overlong block.
- Go code: split per top-level function, keeping each function's preceding
  comment block attached (that comment is often the most retrieval-relevant
  part, e.g. "NOTE: this is the check-then-act race...").
- Alert docs: already short and self-contained; ingested as a single chunk.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

import tiktoken

_ENCODING = tiktoken.get_encoding("cl100k_base")

MAX_SECTION_TOKENS = 800
OVERLAP_TOKENS = 50


@dataclass
class Chunk:
    text: str
    chunk_index: int


def count_tokens(text: str) -> int:
    return len(_ENCODING.encode(text))


def _split_by_tokens(text: str, max_tokens: int = MAX_SECTION_TOKENS, overlap: int = OVERLAP_TOKENS) -> list[str]:
    """Token-window split with overlap, for sections that exceed max_tokens."""
    tokens = _ENCODING.encode(text)
    if len(tokens) <= max_tokens:
        return [text]

    pieces: list[str] = []
    start = 0
    step = max_tokens - overlap
    while start < len(tokens):
        window = tokens[start : start + max_tokens]
        pieces.append(_ENCODING.decode(window))
        if start + max_tokens >= len(tokens):
            break
        start += step
    return pieces


def chunk_rfc(text: str) -> list[Chunk]:
    """Split an RFC markdown doc on `##` headers, then sub-split any
    oversized section."""
    # Keep the title/preamble (before the first "## ") as its own leading
    # section so it isn't dropped.
    header_positions = [m.start() for m in re.finditer(r"^## ", text, flags=re.MULTILINE)]
    if not header_positions:
        sections = [text]
    else:
        sections = []
        bounds = [0] + header_positions + [len(text)]
        for i in range(len(bounds) - 1):
            start, end = bounds[i], bounds[i + 1]
            if start == end:
                continue
            section = text[start:end].strip()
            if section:
                sections.append(section)

    chunks: list[Chunk] = []
    idx = 0
    for section in sections:
        for piece in _split_by_tokens(section):
            chunks.append(Chunk(text=piece, chunk_index=idx))
            idx += 1
    return chunks


_FUNC_DECL_RE = re.compile(r"^func\s", flags=re.MULTILINE)


def chunk_go(text: str) -> list[Chunk]:
    """Split Go source per top-level function, keeping each function's
    preceding `//` comment block attached to it."""
    lines = text.split("\n")
    func_line_indices = [i for i, line in enumerate(lines) if _FUNC_DECL_RE.match(line)]

    if not func_line_indices:
        # No functions (e.g. a file that's all package-level declarations) —
        # ingest the whole file as one chunk.
        return [Chunk(text=text, chunk_index=0)]

    # Attach each function's leading contiguous "//" comment block by
    # walking upward from the function line until a non-comment, non-blank
    # line is hit, but not past the previous function's body.
    boundaries: list[int] = []
    prev_func_end = 0
    for func_idx in func_line_indices:
        start = func_idx
        while start > prev_func_end and (
            lines[start - 1].strip().startswith("//") or lines[start - 1].strip() == ""
        ):
            start -= 1
        # Trim leading blank lines from the attached comment block.
        while start < func_idx and lines[start].strip() == "":
            start += 1
        boundaries.append(start)

    # Leading package/import preamble before the first function's comment
    # block, if non-trivial, becomes its own chunk.
    chunks: list[Chunk] = []
    idx = 0
    if boundaries[0] > 0:
        preamble = "\n".join(lines[: boundaries[0]]).strip()
        if preamble:
            chunks.append(Chunk(text=preamble, chunk_index=idx))
            idx += 1

    boundaries.append(len(lines))
    for i in range(len(boundaries) - 1):
        start, end = boundaries[i], boundaries[i + 1]
        body = "\n".join(lines[start:end]).strip()
        if body:
            chunks.append(Chunk(text=body, chunk_index=idx))
            idx += 1

    return chunks


def chunk_alert(text: str) -> list[Chunk]:
    """Alert docs are already a natural single retrieval unit."""
    return [Chunk(text=text.strip(), chunk_index=0)]
