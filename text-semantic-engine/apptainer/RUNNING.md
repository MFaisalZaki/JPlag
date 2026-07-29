# Running the text-semantic engine in Apptainer

A step-by-step runbook: build the image once, then run a whole term of coursework
through it with a single command, offline.

The image bundles the compiled engine, a Java 25 runtime, every jar it needs, and
the SBERT model. Nothing has to be installed on the machine that runs it — no
Java, no Maven, no Python, no network.

---

## 0. Before you start

| | |
| --- | --- |
| **To build** | a **Linux** host with `apptainer` (or `singularity`), network access, ~10 GB free disk, ~20 min |
| **To run** | any host with `apptainer` and the `.sif` file. No network |
| **Architecture** | build on the **same CPU architecture you will run on** (`uname -m`) |

The architecture rule is not optional. The PyTorch native library baked into the
image is chosen by whichever CPU does the build, so an `aarch64` image will not
run on an `x86_64` cluster. On Apple silicon it is worse than useless: the
`aarch64` image built through Docker/Podman crashes with `SIGILL` because the VM
advertises SVE2 instructions it cannot execute.

**On macOS, therefore: build on the cluster (or any Linux box of the target
architecture), not on your laptop.** The macOS path exists only to check that
the recipe still works.

---

## 1. Build the image

From **anywhere** — the script `cd`s to the repository root itself, which is where
the definition file's `%files` paths are anchored:

```bash
cd ~/Developer/Plagiarism-alternatives/JPlag
text-semantic-engine/apptainer/build-image.sh
# -> text-semantic-engine/apptainer/plagiarism-check.sif
```

Somewhere else, or under another name:

```bash
text-semantic-engine/apptainer/build-image.sh /scratch/$USER/plagiarism-check.sif
```

Or invoke Apptainer directly, **from the repository root**:

```bash
apptainer build text-semantic-engine/apptainer/plagiarism-check.sif \
                text-semantic-engine/apptainer/plagiarism-check.def
```

What happens: Maven compiles the engine and its sibling modules, the jars are
frozen into `/opt/jplag/lib`, then a two-document demo coursework is run twice —
once to pull the SBERT model down, once with `DJL_OFFLINE=true` to prove the
cache is complete. If the model were missing, the **build** fails here rather
than the job on the cluster. `apptainer build` then runs the `%test` section,
which repeats that check against the finished, read-only image.

Takes 15–25 minutes and produces a file a bit under 2 GB.

> The `.sif` is not in `.gitignore` — do not commit it. Build it into
> `/scratch` or `/tmp`, or `rm` it when you are done.

<details>
<summary>Building on macOS anyway (recipe check only)</summary>

Apptainer is Linux-only. With no `apptainer` on `PATH`, `build-image.sh` falls
back to running the official Apptainer image under Docker or Podman
(`--privileged` required). Both are installed on this machine, so:

```bash
text-semantic-engine/apptainer/build-image.sh /tmp/plagiarism-check.sif
```

works — but produces an `aarch64` image that will crash on Apple silicon and is
the wrong architecture for an `x86_64` cluster. Use it to verify the definition
file still builds, nothing more.
</details>

---

## 2. Get the image and the data onto the machine that will run it

```bash
scp text-semantic-engine/apptainer/plagiarism-check.sif  cluster:/scratch/$USER/
rsync -a ~/Developer/Plagiarism-alternatives/2025_6/     cluster:/scratch/$USER/2025_6/
```

---

## 3. Smoke test

Three checks, none of which touch your data:

```bash
apptainer run-help /scratch/$USER/plagiarism-check.sif   # the built-in help
apptainer run      /scratch/$USER/plagiarism-check.sif --help
apptainer test     /scratch/$USER/plagiarism-check.sif   # runs a 2-document demo end to end
```

`apptainer test` ending in `>> ok` means the engine starts, the model loads with
the network off, and a full index → query → report cycle completes.

---

## 4. Run it

The image expects a dataset two levels deep — module, then coursework:

```
<dataset-root>/
  SD2005/                             <- module
    883461/                           <- coursework
      240008189-Op-Ed-4959827.pdf     <- <studentid>-<assignment>-<submissionid>.pdf
      240010283-Op-Ed-4961327.pdf
      warned/                         <- optional: submissions that got a warning
        240004651-Op-Ed-4961199.pdf
```

```bash
cd /scratch/$USER
apptainer run plagiarism-check.sif ./2025_6 ./2025_6-results
```

That is the whole thing. Every coursework is checked **entirely on its own** —
its own index, its own reports, no cross-module comparison. Everything under a
coursework is both indexed *and* checked, `warned/` included, so warned
submissions are compared against the regular ones and against each other.

Leave the output path off and it defaults to `./results`.

### Binding paths

Apptainer automatically mounts your **home directory** and the **current working
directory**. Anything else — a scratch filesystem, `/data`, a project share — is
invisible inside the container until you bind it:

```bash
apptainer run --bind /data:/data plagiarism-check.sif /data/2025_6 /data/results
```

A "dataset directory not found" error on a path that plainly exists is almost
always this.

### What it costs

Measured on 7 modules / 447 documents: **≈0.9 s per document**, peak **1.4 GB**
RSS for the largest coursework (87–142 documents). A full 22-module term is
roughly 20–30 minutes and stays under 2 GB. Give the JVM headroom — the index
and the coursework's sentence embeddings are held in memory:

```bash
apptainer run --env JAVA_TOOL_OPTIONS=-Xmx12g plagiarism-check.sif ./2025_6 ./results
```

---

## 5. Options

Every option is an environment variable. Pass them with `--env`, which works
whether or not your shell exports them. **Use one `--env` per variable** —
Apptainer splits a single `--env` on commas, which mangles any value containing
one:

```bash
apptainer run \
  --env ONLY='SD2005/*' \
  --env KEEP_INDEX=0 \
  --env SENTENCE_THRESHOLD=0.88 \
  plagiarism-check.sif ./2025_6 ./results
```

| Variable | Default | Effect |
| --- | --- | --- |
| `BACKEND` | `ENSEMBLE` | retrieval signal: `TFIDF`, `SBERT` or `ENSEMBLE` |
| `TOP_K` | `all` | archived documents each submission is compared against. `all` compares against the whole coursework, so retrieval only *orders* results rather than deciding what is looked at |
| `SENTENCE_THRESHOLD` | `0.85` | sentence match cutoff. Has to sit high — see the note below |
| `AUTHOR_PATTERN` | `^([0-9]+)-` | regex taking the author (student id) from the file name; `''` disables author handling |
| `COAUTHOR_PATTERN` | `\b2[0-9]{8}\b` | every match on a document's cover sheet is a co-author, so both members of a paired submission own it and neither is reported against the other; `''` disables |
| `SAME_AUTHOR` | `exclude` | a student's own earlier work: `exclude` it, or `flag` it as self-reuse |
| `COMMON_SENTENCE_SHARE` | `0.10` | a sentence appearing in more than this share of the coursework counts as given material (assignment brief, prescribed method, template) and is left out; `0` disables. Ignored below 10 documents |
| `CHECK_ALL_SECTIONS` | `0` | `1` also checks cover sheets and reference lists. They are identical across a cohort by design, so this scores everything highly and shows nothing |
| `FLAG_THRESHOLD` | `20` | matched % at or above which a document counts as *flagged* in the statistics. Everything still gets a report; this sets the size of the queue a marker reads |
| `KEEP_INDEX` | `1` | `0` deletes each coursework's index after the check, to save disk |
| `RESUME` | `0` | `1` skips courseworks already recorded in `stats.csv` |
| `ONLY` | — | restrict the run to matching `<module>/<coursework>` paths, comma-separated globs: `ONLY='SD2005/*'`, `ONLY='SD2005/883461,PN1001/*'` |
| `JAVA_TOOL_OPTIONS` | — | JVM flags, e.g. `-Xmx12g` |

> **Why `SENTENCE_THRESHOLD` has to stay high.** Sentence embeddings rate any two
> sentences on the same subject highly whether or not either was copied, and the
> engine takes the *best* match over every sentence of every candidate source —
> so the cutoff applies to a maximum over hundreds of comparisons. On a
> 26-submission single-prompt cohort, `0.70` reports 173 matches and `0.85`
> reports 31. Read a match as evidence only where the *wording* is shared: the
> report's category (copy-paste / lightly edited / paraphrase) comes from literal
> word overlap and is the signal to weigh.

Start with a single module to sanity-check the settings before committing to the
whole term:

```bash
apptainer run --env ONLY='SD2005/*' plagiarism-check.sif ./2025_6 ./trial
```

---

## 6. Reading the output

```
<output-root>/
  <module>/<coursework>/
    reports/<document>.html   one Turnitin-style originality report per submission
    matches.txt               ranked source matches per submission
    summary.txt               each submission's matched % and top source, worst first
    run.log                   full engine output for this coursework
    index/                    the coursework's index (KEEP_INDEX=0 to drop it)
  stats.csv                   one row per coursework: docs, flagged, time, peak MB, status
  stats.txt                   the same as a table, plus per-module and overall averages
  run.log                     the run's own progress log
```

Start at `stats.txt` for the shape of the run, then a coursework's `summary.txt`
to see which submissions are worth opening, then the `reports/*.html` for those.
The reports are self-contained HTML — copy them off the cluster and open them in
any browser.

---

## 7. On a cluster (SLURM)

Nothing in the run is interactive, so the batch script is just the `apptainer
run` line:

```bash
#!/bin/bash
#SBATCH --job-name=plagiarism-check
#SBATCH --cpus-per-task=8
#SBATCH --mem=16G
#SBATCH --time=08:00:00
#SBATCH --output=plagiarism-%j.log

apptainer run --bind /scratch:/scratch \
    --env JAVA_TOOL_OPTIONS=-Xmx12g \
    --env RESUME=1 \
    /scratch/$USER/plagiarism-check.sif \
    /scratch/$USER/2025_6 \
    /scratch/$USER/2025_6-results
```

`RESUME=1` makes a re-queued job pick up where a timed-out one stopped: it skips
every coursework already recorded in `stats.csv` and keeps its statistics. Safe
to leave on permanently.

Keep the output on a filesystem the compute node can write to, and remember that
`$HOME` and the submit directory are the only paths mounted without `--bind`.

**If the run exits non-zero**, at least one coursework failed. Every other
coursework still produced its results; the failures are marked `FAILED` in
`stats.csv` and their `<module>/<coursework>/run.log` has the engine's output.
Fix the cause and re-run with `RESUME=1` to redo only what is missing.

---

## 8. The other workflows

Every engine script is on `PATH` inside the image, so the one-off workflows work
without the module/coursework layout:

```bash
# a self-contained dataset: top-level files are the corpus, sub-directories are checked against it
apptainer exec plagiarism-check.sif check-dataset.sh ./essays ./results

# build a reusable corpus index, then check new documents against it
apptainer exec plagiarism-check.sif build-database.sh ./past-submissions ./corpus-index
apptainer exec plagiarism-check.sif run-plagiarism.sh ./new-submissions ./corpus-index ./results

# each script documents its own options
apptainer exec plagiarism-check.sif run-all-courseworks.sh --help
apptainer exec plagiarism-check.sif run-plagiarism.sh --help
```

`exec` does not go through the `%runscript`, so pass options the same way —
`--env VAR=value` before the image name.

To poke around inside:

```bash
apptainer shell plagiarism-check.sif
```

---

## 9. Troubleshooting

**`dataset directory not found`, on a path that exists.** Not bound into the
container. Only `$HOME` and the current directory are mounted by default; add
`--bind /path:/path`.

**`SIGILL … libarm_compute … Disabling SVE`, or the engine dies the moment it
starts embedding.** An `aarch64` image on a CPU that does not implement the
instructions its `/proc/cpuinfo` advertises — the Apple silicon VM behind Docker
Desktop and `podman machine` is the usual culprit. Build the image on the target
Linux machine (§1).

**`Could not load the SBERT model`.** The container is not finding the baked-in
cache at `/opt/djl-cache`. Do not override `DJL_CACHE_DIR`, and be careful with
`--cleanenv` — sending DJL to `~/.djl.ai` on the host fails immediately under
`DJL_OFFLINE=true` (which is deliberate: better a clear error than a silent
300 MB download onto a login node).

**Everything is flagged.** Expected on a single-prompt cohort at a low
threshold, and the reason the defaults are what they are. Check
`CHECK_ALL_SECTIONS` is `0` (cover sheets and bibliographies are identical
across a cohort by design) and `COMMON_SENTENCE_SHARE` is non-zero (it strips
the assignment brief), then raise `FLAG_THRESHOLD` to shorten the queue rather
than lowering `SENTENCE_THRESHOLD`.

**Out of memory / the JVM is thrashing.** The default heap is a quarter of the
node's RAM, which is thin on a shared node. Set
`--env JAVA_TOOL_OPTIONS=-Xmx12g` and give SLURM `--mem` at least a couple of GB
above it.

**Disk fills up.** Each coursework keeps its Lucene index under
`<output>/<module>/<coursework>/index/`. `--env KEEP_INDEX=0` deletes each one
after its check.

---

## What is inside the image

| Path | |
| --- | --- |
| `/opt/jplag/text-semantic-engine/target/classes` | the compiled engine |
| `/opt/jplag/lib/*.jar` | its runtime dependencies (Lucene, DJL, PDFBox, CoreNLP, …) |
| `/opt/jplag/text-semantic-engine/scripts/` | the workflow scripts, on `PATH` |
| `/opt/djl-cache` | the SBERT model (`all-MiniLM-L6-v2`) and the PyTorch native runtime |

Entry point: `run-all-courseworks.sh`. `DJL_CACHE_DIR=/opt/djl-cache` and
`DJL_OFFLINE=true` are set in `%environment` — the cache lives in the image
rather than `~/.djl.ai` because Apptainer mounts the host's home over the
container's, which would hide the baked-in model.

See also [`README.md`](README.md) (image reference) and
[`../scripts/README.md`](../scripts/README.md) (what the options mean in detail).
