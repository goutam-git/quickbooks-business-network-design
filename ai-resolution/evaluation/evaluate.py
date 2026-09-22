"""
ai-resolution/evaluation/evaluate.py

A small, honest evaluation harness -- not a benchmark suite. It checks
that the ranking service (either backend) puts the correct candidate
first for a hand-labeled set of tricky name pairs, and reports simple
accuracy. Intended to be run against a live `ranking` service (local
fallback backend by default; point OPENAI_API_KEY at the service to
evaluate the embedding backend instead).

Known result with the local NGRAM_FALLBACK backend: 4 of 5 cases correct.
This is a 5-example evaluation harness, not a statistically meaningful
benchmark -- treat it as "one representative failure case, deliberately
kept" rather than an accuracy percentage. The
"ABC Consulting" case fails on purpose -- it's a near-identical
misspelling ("ABD Consulting") beating the real, differently-worded
match ("ABC Consultancy Services LLP") on lexical similarity, which is
exactly the class of error semantic embeddings exist to fix. Run this
same script again with OPENAI_API_KEY set on the ranking service (see
repo root README) to see whether the EMBEDDING backend closes that gap
-- that comparison IS the demo.

Usage:
    python evaluate.py [--url http://localhost:8090/rank]
"""
import argparse
import sys

import requests

# Each case: a query name, the CORRECT candidate id, and a small
# distractor pool. Cases are chosen to probe things naive substring/
# edit-distance matching typically gets wrong but semantic embeddings
# (or at least a decent n-gram model) should get closer on.
CASES = [
    {
        "query": "ABC Consulting",
        "candidates": [
            {"id": "correct", "name": "ABC Consultancy Services LLP"},
            {"id": "distractor1", "name": "XYZ Consulting Group"},
            {"id": "distractor2", "name": "ABD Consulting"},
        ],
        "expected": "correct",
    },
    {
        "query": "Bharat Traders",
        "candidates": [
            {"id": "correct", "name": "Bharat Trader"},
            {"id": "distractor1", "name": "Bharat Textiles"},
            {"id": "distractor2", "name": "Global Traders"},
        ],
        "expected": "correct",
    },
    {
        "query": "Everest Hardware Pvt Ltd",
        "candidates": [
            {"id": "correct", "name": "Everest Hardware"},
            {"id": "distractor1", "name": "Everest Logistics"},
            {"id": "distractor2", "name": "Himalaya Hardware"},
        ],
        "expected": "correct",
    },
    {
        "query": "Zenith Freight Solutions",
        "candidates": [
            {"id": "correct", "name": "Zenith Freight Solutions Pvt Ltd"},
            {"id": "distractor1", "name": "Apex Freight Solutions"},
            {"id": "distractor2", "name": "Zenith Financial Solutions"},
        ],
        "expected": "correct",
    },
    {
        "query": "Delta Logistics",
        "candidates": [
            {"id": "correct", "name": "Delta Logistics Ltd"},
            {"id": "distractor1", "name": "Delta Textiles"},
            {"id": "distractor2", "name": "Omega Logistics"},
        ],
        "expected": "correct",
    },
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8090/rank")
    args = parser.parse_args()

    correct = 0
    for case in CASES:
        resp = requests.post(args.url, json={"query": case["query"], "candidates": case["candidates"]}, timeout=10)
        resp.raise_for_status()
        result = resp.json()
        top = result["results"][0]
        hit = top["id"] == case["expected"]
        correct += hit
        status = "PASS" if hit else "FAIL"
        print(f"[{status}] backend={result['backend']:15s} query={case['query']!r:35s} "
              f"top={top['id']} (score={top['score']})")

    total = len(CASES)
    print(f"\n{correct}/{total} correct ({100 * correct / total:.0f}%)")
    sys.exit(0 if correct == total else 1)


if __name__ == "__main__":
    main()
