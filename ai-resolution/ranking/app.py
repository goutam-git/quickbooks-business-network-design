"""
ai-resolution/ranking — the ONLY AI/ML-touching component in this repo.

Per docs/architecture.md (mirroring design doc Section 12): deterministic
matching in service/identity establishes identity directly when possible.
This service is called ONLY to re-rank/score the bounded top-K candidates
that the deterministic resolver already retrieved — it never retrieves
candidates itself (see ../candidate-retrieval/README.md) and it never
gets to unilaterally decide MATCH; the caller (Java service) still applies
the MATCH / CONFIRM_REQUIRED / NO_MATCH thresholds.

Two interchangeable ranking backends behind the same HTTP contract, so a
live demo works with or without an API key:

  1. EMBEDDING  — used automatically if OPENAI_API_KEY is set. Calls
     OpenAI's embeddings endpoint for the query and each candidate name,
     scores by cosine similarity. Real semantic signal (e.g. catches
     "ABC Consulting" vs "ABC Consultancy Services").
  2. NGRAM_FALLBACK — used automatically otherwise. A dependency-free,
     fully local character-trigram cosine-similarity model. No network
     calls, no API key, deterministic, instant. It's a genuine (if
     modest) semantic-adjacent signal, not a stub -- but it is still
     lexical, not semantic: it will sometimes rank a near-identical
     misspelling ("ABC Consulting" vs "ABD Consulting") above the
     actually-correct, differently-worded match ("ABC Consultancy
     Services LLP"). That specific failure is intentionally left in
     the ../evaluation/evaluate.py test set rather than tuned away --
     it's the honest "here's exactly where real embeddings would help"
     moment for a live demo, not a hidden gap.

POST /rank
  { "query": "ABC Consulting", "candidates": [{"id": "...", "name": "..."}] }
  -> { "backend": "EMBEDDING" | "NGRAM_FALLBACK", "model": "...",
       "results": [{"id": "...", "score": 0.0-1.0}, ...] }   # sorted desc
"""
import math
import os
import re
from collections import Counter
from typing import List

import requests
from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="ai-resolution/ranking", version="1.0.0")

OPENAI_API_KEY = os.environ.get("OPENAI_API_KEY", "").strip()
OPENAI_EMBEDDING_MODEL = os.environ.get("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
OPENAI_EMBEDDINGS_URL = "https://api.openai.com/v1/embeddings"


class Candidate(BaseModel):
    id: str
    name: str


class RankRequest(BaseModel):
    query: str
    candidates: List[Candidate]


class RankedResult(BaseModel):
    id: str
    score: float


class RankResponse(BaseModel):
    backend: str
    model: str
    results: List[RankedResult]


# ---------------------------------------------------------------------
# Backend 1: OpenAI embeddings (used only when OPENAI_API_KEY is set)
# ---------------------------------------------------------------------

def _openai_embed(texts: List[str]) -> List[List[float]]:
    resp = requests.post(
        OPENAI_EMBEDDINGS_URL,
        headers={"Authorization": f"Bearer {OPENAI_API_KEY}", "Content-Type": "application/json"},
        json={"model": OPENAI_EMBEDDING_MODEL, "input": texts},
        timeout=10,
    )
    resp.raise_for_status()
    data = resp.json()["data"]
    # OpenAI preserves input order.
    return [row["embedding"] for row in data]


def _cosine(a: List[float], b: List[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


def rank_with_embeddings(query: str, candidates: List[Candidate]) -> RankResponse:
    texts = [query] + [c.name for c in candidates]
    vectors = _openai_embed(texts)
    query_vec, candidate_vecs = vectors[0], vectors[1:]
    scored = [
        RankedResult(id=c.id, score=round(max(0.0, min(1.0, _cosine(query_vec, v))), 4))
        for c, v in zip(candidates, candidate_vecs)
    ]
    scored.sort(key=lambda r: r.score, reverse=True)
    return RankResponse(backend="EMBEDDING", model=OPENAI_EMBEDDING_MODEL, results=scored)


# ---------------------------------------------------------------------
# Backend 2: local character-trigram cosine similarity (no network, no key)
# ---------------------------------------------------------------------

_STOPWORDS = {"pvt", "ltd", "llc", "inc", "corp", "co", "limited", "private", "company", "and", "the"}


def _normalize(name: str) -> str:
    s = name.lower().strip()
    s = re.sub(r"[^a-z0-9 ]", " ", s)
    tokens = [t for t in s.split() if t not in _STOPWORDS]
    return " ".join(tokens)


def _trigrams(text: str) -> Counter:
    padded = f"  {text}  "
    return Counter(padded[i:i + 3] for i in range(len(padded) - 2))


def _cosine_counter(a: Counter, b: Counter) -> float:
    common = set(a) & set(b)
    dot = sum(a[k] * b[k] for k in common)
    na = math.sqrt(sum(v * v for v in a.values()))
    nb = math.sqrt(sum(v * v for v in b.values()))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


def rank_with_ngram_fallback(query: str, candidates: List[Candidate]) -> RankResponse:
    query_grams = _trigrams(_normalize(query))
    scored = []
    for c in candidates:
        cand_grams = _trigrams(_normalize(c.name))
        score = round(max(0.0, min(1.0, _cosine_counter(query_grams, cand_grams))), 4)
        scored.append(RankedResult(id=c.id, score=score))
    scored.sort(key=lambda r: r.score, reverse=True)
    return RankResponse(backend="NGRAM_FALLBACK", model="char-trigram-cosine-v1", results=scored)


# ---------------------------------------------------------------------

@app.get("/health")
def health():
    return {"status": "UP", "backend": "EMBEDDING" if OPENAI_API_KEY else "NGRAM_FALLBACK"}


@app.post("/rank", response_model=RankResponse)
def rank(request: RankRequest):
    if not request.candidates:
        backend = "EMBEDDING" if OPENAI_API_KEY else "NGRAM_FALLBACK"
        model = OPENAI_EMBEDDING_MODEL if OPENAI_API_KEY else "char-trigram-cosine-v1"
        return RankResponse(backend=backend, model=model, results=[])

    if OPENAI_API_KEY:
        try:
            return rank_with_embeddings(request.query, request.candidates)
        except Exception:
            # Graceful degrade: an AI-enhancement outage must never block
            # resolution (Section 12 failure mode) -- fall back locally.
            return rank_with_ngram_fallback(request.query, request.candidates)

    return rank_with_ngram_fallback(request.query, request.candidates)
