#!/usr/bin/env python3
"""
bibliography-corpus.py — build a source corpus from the works a cohort *cites*.

A cohort names its own likely sources: the papers in its reference lists are the
papers its students actually read, and read is where copied text comes from. That
makes the bibliography a better source corpus than anything a topic search
assembles — a topic search returns the documents most likely to be *about* the
same subject, which is precisely the property that produces false positives when
matches are semantic. Here the candidate set is small, targeted, and free.

It is also the only web-facing step that keeps student work inside the building:
what leaves is a DOI, never a submission.

Input is the HTML reports of a previous run (which carry each submission's
extracted text, so no PDF library is needed here). Output is one plain-text file
per resolved work, ready for `build-database.sh`.

Usage:
  scripts/bibliography-corpus.py <reports-dir> <output-dir>

Options (environment variables):
  OPENALEX_MAILTO=<email>   put in OpenAlex's "polite pool"; strongly recommended,
                            and required by their terms for sustained use.
  KEEP_TITLES=1             prepend each work's title to its abstract. OFF by
                            default, and think before turning it on: a student's
                            reference-list entry *is* the title, so a corpus that
                            contains titles matches every correctly formatted
                            bibliography at 100% and reads like mass plagiarism.

Example:
  scripts/bibliography-corpus.py 2025_6-results/PN1001/891199/reports ./cited-corpus
  scripts/build-database.sh ./cited-corpus ./cited-index
  scripts/run-plagiarism.sh <submissions> ./cited-index <results>   # MINIMUM_WORD_OVERLAP=0.4
"""
import html
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

OPENALEX = "https://api.openalex.org/works"
BATCH = 50  # OpenAlex accepts up to 50 OR'd values in one filter
PAUSE_SECONDS = 0.5
# Shorter than this and the "abstract" is a stub -- a copyright line or a
# truncation -- with too little text to align a sentence against.
MINIMUM_ABSTRACT_WORDS = 40

DOI = re.compile(r"\b10\.\d{4,9}/[-._;()/:A-Za-z0-9]+", re.IGNORECASE)


def documents_in(directory):
    """The text of each submission, taken from a previous run's HTML reports (or from .txt files)."""
    texts = []
    for name in sorted(os.listdir(directory)):
        path = os.path.join(directory, name)
        if name.endswith(".txt"):
            texts.append(open(path, encoding="utf-8", errors="replace").read())
        elif name.endswith(".html"):
            page = open(path, encoding="utf-8", errors="replace").read()
            body = page[page.find("<main>"):page.find("</main>")]
            texts.append(html.unescape(re.sub(r"<[^>]+>", " ", body)))
    return texts


def dois_in(texts):
    found = set()
    for text in texts:
        for match in DOI.findall(" ".join(text.split())):
            found.add(match.rstrip(".,;)").lower())
    return sorted(found)


def fetch(url, mailto):
    agent = f"jplag-bibliography-corpus{f' (mailto:{mailto})' if mailto else ''}"
    request = urllib.request.Request(url, headers={"User-Agent": agent})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def resolve(dois, mailto):
    """Look up the DOIs in batches. A batch that fails is reported and skipped, not fatal."""
    works = {}
    batches = (len(dois) + BATCH - 1) // BATCH
    for index in range(batches):
        chunk = dois[index * BATCH:(index + 1) * BATCH]
        query = urllib.parse.quote("doi:" + "|".join(chunk), safe=":|/.")
        url = f"{OPENALEX}?filter={query}&per-page={BATCH}" + (f"&mailto={mailto}" if mailto else "")
        try:
            payload = fetch(url, mailto)
        except Exception as error:
            print(f"  batch {index + 1}/{batches}: failed ({error})", file=sys.stderr)
            continue
        for work in payload.get("results", []):
            key = (work.get("doi") or "").replace("https://doi.org/", "").lower()
            if key:
                works[key] = work
        print(f"  batch {index + 1}/{batches}: {len(payload.get('results', []))} resolved", file=sys.stderr)
        time.sleep(PAUSE_SECONDS)
    return works


def abstract_of(work):
    """OpenAlex stores an abstract as an inverted index; rebuild the running text."""
    inverted = work.get("abstract_inverted_index")
    if not inverted:
        return ""
    words = {}
    for word, positions in inverted.items():
        for position in positions:
            words[position] = word
    return " ".join(words[position] for position in sorted(words))


def main(reports_directory, output_directory):
    mailto = os.environ.get("OPENALEX_MAILTO", "")
    keep_titles = os.environ.get("KEEP_TITLES") == "1"
    if not mailto:
        print("note: set OPENALEX_MAILTO=<email> to use OpenAlex's polite pool.", file=sys.stderr)

    texts = documents_in(reports_directory)
    dois = dois_in(texts)
    print(f"{len(texts)} document(s); {len(dois)} distinct DOI(s) cited.", file=sys.stderr)
    if not dois:
        print("Nothing to resolve.", file=sys.stderr)
        return 0

    works = resolve(dois, mailto)
    os.makedirs(output_directory, exist_ok=True)
    written = 0
    without_abstract = 0
    manifest = {}
    for doi in dois:
        work = works.get(doi)
        if not work:
            continue
        abstract = abstract_of(work)
        if len(abstract.split()) < MINIMUM_ABSTRACT_WORDS:
            without_abstract += 1
            continue
        title = work.get("title") or work.get("display_name") or ""
        body = f"{title}\n\n{abstract}\n" if keep_titles else abstract + "\n"
        name = "CITED-" + re.sub(r"[^A-Za-z0-9._-]", "_", doi)[:120] + ".txt"
        open(os.path.join(output_directory, name), "w", encoding="utf-8").write(body)
        manifest[doi] = {"title": title, "year": work.get("publication_year"), "file": name}
        written += 1

    json.dump(manifest, open(os.path.join(output_directory, "manifest.json"), "w"), indent=1)
    print(f"\nresolved {len(works)}/{len(dois)}; wrote {written} source document(s) to {output_directory}", file=sys.stderr)
    print(f"  {len(dois) - len(works)} DOI(s) not found, {without_abstract} resolved but without a usable abstract", file=sys.stderr)
    if keep_titles:
        print("  KEEP_TITLES=1: titles are included -- expect every correctly formatted "
              "reference-list entry to match its own source.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] in ("-h", "--help"):
        print(__doc__.strip(), file=sys.stderr)
        sys.exit(0 if len(sys.argv) > 1 and sys.argv[1] in ("-h", "--help") else 2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
