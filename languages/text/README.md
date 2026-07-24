# JPlag Text Language Module

Plagiarism detection for **natural-language text** (essays, reports, free-text answers), built on the standard JPlag comparison core (Greedy String Tiling over token sequences).

By default the module is a naive, language-agnostic word matcher: each word becomes a token, and JPlag finds long runs of identical words. This is excellent at catching **(near-)verbatim reuse**, but blind to paraphrasing — swap a few words for synonyms and the token runs break.

To also catch **paraphrased** text, the module offers optional, WordNet-based normalization. Each option collapses surface variants into a shared token type, so the core comparison can match reworded passages. **All options are off by default**, so existing behavior is unchanged unless you opt in.

> Need to detect paraphrases that also **reorder or restructure** sentences? Greedy String Tiling requires contiguous matches and cannot. See the companion [`text-semantic-engine`](../../text-semantic-engine/README.md), an order-independent engine.

## Normalization options

| Option | Flag | What it collapses | Example |
|---|---|---|---|
| Lemmatization | `--lemmatize` | inflected forms → base form | `cats`, `cat` → `cat` |
| Stop-word removal | `--removeStopwords` | drops English function words | `the`, `of`, `is`, … removed |
| Synonym canonicalization | `--expandSynonyms` | synonyms → one canonical term (implies lemmatization) | `big`, `large` → one token |

These are backed by [WordNet](https://wordnet.princeton.edu/) (via [extJWNL](https://github.com/extjwnl/extjwnl)) and are therefore **English-specific**. If WordNet cannot be loaded, they are disabled with a warning and the module falls back to naive matching.

### Trade-offs

- `--expandSynonyms` is the strongest paraphrase lever but also raises **false positives** — it has no word-sense disambiguation and maps each word by its most common sense. Enable it deliberately.
- Because lemmatization and synonyms use WordNet, they only make sense for **English** input. Leave them off for other languages (naive matching still works).

## Usage (CLI)

Language-specific options are only available with the **subcommand** form of the CLI (the language name as a subcommand, not `-l`):

```bash
# Naive, verbatim matching (default behavior, any language)
java -jar jplag.jar text /path/to/submissions

# Paraphrase-aware matching (English)
java -jar jplag.jar text /path/to/submissions --lemmatize --removeStopwords --expandSynonyms
```

List all options for the module with:

```bash
java -jar jplag.jar text -h
```

(The runnable JAR is produced in `cli/target/` as `jplag-<version>-jar-with-dependencies.jar`; `jplag.jar` above is a stand-in for that file.)

Accepted file extensions: `.txt`, `.asc`, `.tex`, `.md`, `.rtf`, `.csv`, `.wiki`, `.json`, `.yaml`, `.yml`, `.xml`.

## How it works

Tokenization uses Stanford CoreNLP (the `tokenize` annotator). Each resulting word is normalized by [`TextNormalizer`](src/main/java/de/jplag/text/TextNormalizer.java) — lower-case, optional stop-word drop, optional lemmatization, optional synonym canonicalization — and the normalized string becomes the token type ([`TextTokenType`](src/main/java/de/jplag/text/TextTokenType.java)). Since JPlag matches identical token types, normalizing synonyms/inflections to the same string is exactly what lets the core detect paraphrases.

## Limitations

- **Order-dependent.** Greedy String Tiling matches contiguous runs, so reordered clauses, active↔passive voice, and sentence splitting/merging are not detected — even with all options on. For that, use [`text-semantic-engine`](../../text-semantic-engine/README.md).
- **English only** for lemmatization/synonyms.
- Synonym expansion trades precision for recall (see Trade-offs).
