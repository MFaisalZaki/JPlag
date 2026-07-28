# Apptainer image

The semantic text engine, its Java runtime, and its SBERT model in one file.
Give it a directory of modules, get back the results.

```bash
apptainer run plagiarism-check.sif <dataset-root> <output-root>
```

The image is self-contained: the model and the PyTorch runtime are downloaded
once **at build time** and baked in, so the finished `.sif` needs no network and
no Maven, Java or Python on the host.

## Build

```bash
text-semantic-engine/apptainer/build-image.sh              # -> apptainer/plagiarism-check.sif
text-semantic-engine/apptainer/build-image.sh /tmp/pc.sif  # or somewhere else
```

Or directly, **from the repository root** — the definition's `%files` paths are
relative to the build's working directory:

```bash
apptainer build text-semantic-engine/apptainer/plagiarism-check.sif \
                text-semantic-engine/apptainer/plagiarism-check.def
```

The build needs network access and takes roughly 15–25 minutes; the image comes
out at a bit under 2 GB.

**Build on the architecture you will run on.** The PyTorch native library baked
into the image is platform-specific, and it is chosen by whatever CPU the build
runs on. Apptainer is Linux-only, so on macOS `build-image.sh` falls back to
running the official Apptainer image under Docker or Podman (needs
`--privileged`) — that produces an `aarch64` image, which is the wrong one for
an `x86_64` cluster and, on Apple silicon specifically, crashes outright (see
Troubleshooting). Treat the macOS path as a way to check the recipe, and build
the image you actually submit on a Linux host of the target architecture.

The build ends with two safeguards. `%post` runs a two-document demo coursework
twice — once to pull the model down, then again with `DJL_OFFLINE=true` — so a
build that finishes has proven the model loads with the network shut off.
`apptainer build` then runs the `%test` section, which repeats the check against
the finished, read-only image.

## Run

```
<dataset-root>/
  <module>/                       e.g. SD2005
    <coursework>/                 e.g. 883461
      <studentid>-<name>-<id>.pdf
      warned/
        <studentid>-<name>-<id>.pdf
```

```bash
apptainer run plagiarism-check.sif ./2025_6 ./2025_6-results
```

The output root defaults to `./results` if you leave it off. Each coursework is
checked entirely on its own — its own index, its own reports, no cross-module
comparison — and the output mirrors the input layout:

```
<output-root>/
  <module>/<coursework>/
    reports/<document>.html   one originality report per submission
    matches.txt               ranked source matches per submission
    summary.txt               each submission's matched % and top source
    run.log                   full engine output for this coursework
    index/                    the coursework's index (KEEP_INDEX=0 to drop it)
  stats.csv                   one row per coursework, with time and peak memory
  stats.txt                   the same as a table, with per-module and overall averages
  run.log
```

### Binding paths

Apptainer mounts your home directory and the current working directory
automatically. Data anywhere else — a scratch filesystem, `/data`, a project
share — has to be bound explicitly, or the container simply will not see it:

```bash
apptainer run --bind /data:/data plagiarism-check.sif /data/2025_6 /data/results
```

### Options

All tuning is through environment variables. `--env` passes them in regardless
of what your shell exports:

```bash
apptainer run --env ONLY='SD2005/*',KEEP_INDEX=0 plagiarism-check.sif ./2025_6 ./out
```

| Variable | Default | Effect |
| --- | --- | --- |
| `BACKEND` | `ENSEMBLE` | retrieval signal: `TFIDF`, `SBERT` or `ENSEMBLE` |
| `TOP_K` | `all` | archived documents each submission is compared against |
| `SENTENCE_THRESHOLD` | `0.85` | sentence match cutoff; lower values report shared topic rather than reuse |
| `AUTHOR_PATTERN` | `^([0-9]+)-` | regex taking the author (student id) from the file name |
| `SAME_AUTHOR` | `exclude` | a student's own earlier work: `exclude` it or `flag` it as self-reuse |
| `KEEP_INDEX` | `1` | `0` deletes each coursework's index after the check |
| `RESUME` | `0` | `1` skips courseworks already recorded in `stats.csv` |
| `ONLY` | — | restrict the run, e.g. `ONLY='SD2005/*'` |
| `JAVA_TOOL_OPTIONS` | — | JVM flags, e.g. `-Xmx8g` if the default quarter of RAM is thin |

See [`../scripts/README.md`](../scripts/README.md) for what these mean in
detail — in particular why `SENTENCE_THRESHOLD` has to sit high.

### The other scripts

Every engine script is on `PATH` inside the image, so the image is usable for
the one-off workflows too:

```bash
apptainer exec plagiarism-check.sif check-dataset.sh ./essays ./results
apptainer exec plagiarism-check.sif build-database.sh ./archive ./corpus-index
apptainer exec plagiarism-check.sif run-plagiarism.sh ./new ./corpus-index ./results
apptainer exec plagiarism-check.sif run-all-courseworks.sh --help
```

### On a cluster

Nothing in the run is interactive, so a batch script is just the `apptainer run`
line. Give the JVM room — the engine holds the index and the sentence embeddings
of the whole coursework in memory — and keep the output on a filesystem the
compute node can write to:

```bash
#!/bin/bash
#SBATCH --cpus-per-task=8
#SBATCH --mem=16G
#SBATCH --time=08:00:00

apptainer run --bind /scratch:/scratch \
    --env JAVA_TOOL_OPTIONS=-Xmx12g,RESUME=1 \
    /scratch/$USER/plagiarism-check.sif /scratch/$USER/2025_6 /scratch/$USER/results
```

`RESUME=1` makes a re-queued job pick up where a timed-out one stopped: it skips
every coursework already recorded in `stats.csv` and keeps its statistics.

## Troubleshooting

**The run exits non-zero.** At least one coursework failed. Every other
coursework still produced its results, and `stats.csv` marks the failures
`FAILED`; their `<module>/<coursework>/run.log` has the engine's output. Fix the
cause and re-run with `RESUME=1` to redo only what is missing.

**`SIGILL … libarm_compute … Disabling SVE`, or the engine aborts the moment it
starts embedding.** An `aarch64` image running on a CPU that does not implement
the instructions its `/proc/cpuinfo` advertises — the Apple silicon VM behind
Docker Desktop and `podman machine` reports SVE2 it cannot execute, and PyTorch's
ARM kernels take it at its word. Build the image on the target Linux machine.

**`Could not load the SBERT model`.** The container is not finding the baked-in
cache. Check `DJL_CACHE_DIR` is still `/opt/djl-cache` — passing `--cleanenv`
without it, or overriding it by accident, sends DJL looking in `~/.djl.ai` on
the host, which with `DJL_OFFLINE=true` fails immediately rather than silently
re-downloading a 300 MB model onto a login node.

**`dataset directory not found`, on a path that plainly exists.** It is not
bound into the container. Apptainer mounts only your home directory and the
current directory by default; everything else needs `--bind`.

## What is inside

| Path | |
| --- | --- |
| `/opt/jplag/text-semantic-engine/target/classes` | the compiled engine |
| `/opt/jplag/lib/*.jar` | its runtime dependencies (Lucene, DJL, PDFBox, CoreNLP, …) |
| `/opt/jplag/text-semantic-engine/scripts/` | the workflow scripts, on `PATH` |
| `/opt/djl-cache` | the SBERT model and the PyTorch native runtime |

`DJL_CACHE_DIR` points at `/opt/djl-cache` rather than the default `~/.djl.ai`
on purpose: Apptainer mounts the host's home directory over the container's, so
the default location would miss the baked-in model and try to download it again.
`DJL_OFFLINE=true` for the same reason — it turns a would-be network fetch into
an immediate, legible error. Both can be overridden with `--env` if you ever
want the container to fetch a different model.
