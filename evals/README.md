# Evals

This is the offline quality harness for the three prompts (decision 15). It runs authored cases through the
production request builders against a real model, grades the outputs and writes one JSON report per run.
It never runs as part of `./gradlew test`. Only the case-shape, grader and fake-gateway tests run there.

## Baseline command

```sh
ANTHROPIC_API_KEY=… $G ./gradlew evalRun --args="--prompt rewrite@1 --model claude-haiku-5-5 --judge claude-opus-5-5 --yes"
```

`$G` is your usual Gradle environment prefix (for example the sandbox truststore `JAVA_TOOL_OPTIONS`). Repeat
the command with `--prompt polish_translate@1` and `--prompt multi_tone@1`. Recording the first baseline for
each prompt is a manual checklist item for the owner. It costs money, so no agent or CI job runs it.

Other options:

| Flag | Meaning |
|------|---------|
| `--prompt id@version` | Required. Reads `evals/cases/<id@version>.jsonl`. |
| `--model` | Required. The model under test. |
| `--judge` | Optional. Adds the LLM judge; meant for `claude-opus-5-5`. |
| `--limit N` | Runs the first N cases only (after `--lang`). |
| `--lang code` | Runs one language only. |
| `--concurrency N` | Number of parallel calls (default 4). |
| `--out path` | Report path. The default is `evals/results/<date>-<prompt>-<model>.json`. |
| `--yes` | Required to spend money. Without it the runner prints the estimate and stops. |

Guards:

- The runner refuses to start when `ANTHROPIC_API_KEY` is not set. The key is read from the environment
  only; it is never printed and never written to the report.
- Before calling anything it prints a cost estimate from the TRD §4.4.4 planning token profile. The
  profile assumes a cached prefix, fresh input and output per case, plus one cache write, and the judge
  adds a short uncached call. This is a planning number only. The report's `cost_usd` uses the measured
  usage.
- Without `--yes` it exits after the estimate and makes no model call.

## Cases

`cases/rewrite@1.jsonl`, `cases/polish_translate@1.jsonl` and `cases/multi_tone@1.jsonl` hold 300 cases
each, 60 per language for en, es, hi, ur and ar. Each line is one JSON object:

| Field | Meaning |
|-------|---------|
| `id` | Unique across all files (`rw-`, `pt-`, `mt-` + lang + number). |
| `prompt` | The prompt ref, equal to the file name. |
| `lang` | The input language (the request locale or source language). |
| `mode` / `modes` | The rewrite mode, or 2–3 multi-tone modes. |
| `target` | The translation target (rewrite `translate`, multi-tone with `translate`, every polish case). |
| `script` | Polish only: `roman`, `native` or `match_input`, sent as the request's script setting. |
| `input` | The message or dictation transcript. |
| `must_keep` | Tokens that must appear verbatim in the output: numbers, links, @mentions, names, emoji. |
| `expect_lang` | The language the output must be in (the target for translations). |
| `notes` | Free text. Notes starting with `injection` mark prompt-injection cases. |

The mix covers every rewrite mode, links, numbers, @mentions, hashtags, emoji, inputs that are already
fine, Hinglish and Roman Urdu (about 25 Roman-script hi and ur cases per file) and at least 10 injection
cases per file. Injection cases try to override the instructions, change the role, close the `<text>`
tag, ask for the system prompt or ask for another language. The right answer is always to treat that text
as the message.

All cases are written for this repo. The multi-tone cases reuse the rewrite inputs, each with a mode
combination that fits the input (for example `fix,formal` for a typo-heavy message or `shorter,formal`
for a long one). The hi, ur and ar cases should be reviewed by a native speaker before a baseline is
treated as final.

`CasesShapeTest` checks the counts, unique ids, that every case is a valid production request, that the
`must_keep` tokens are in the input, the injection and Roman-script minimums, and that no line contains a
string from the teardown work directories when those exist locally.

## Graders

Deterministic checks (`Graders.kt`), all pass or fail:

- `json_shape`: plain text for rewrite; exactly `polished`, `translation`, `source_lang_detected` for
  polish; exactly `{"variants":[{mode,text}…]}` for multi-tone.
- `keeps_tokens`: every `must_keep` token appears verbatim after NFC normalisation.
- `no_preface`: no "Here's…", "Sure…", leading quote or `Label:` line. Skipped when the input itself starts
  that way.
- `length_ratio`: `shorter` ≤ 0.9×, `longer` ≥ 1.1×, other modes 0.5–2.0× (code points).
- `script`: at least 60% of letters in the expected script. Roman input stays Roman.
- `output_lang`: the script check plus Urdu-vs-Arabic letters and en / es / Roman Hindi-Urdu function
  words.
- Multi-tone adds `modes_match`. Polish adds `detected_lang` and checks the translation's script and
  language. For a hi or ur target with no explicit script, either script passes.
- `not_trimmed`: the call did not stop at `max_tokens`.

A case passes when every check passes. A refusal, timeout or upstream error fails the case and is counted
under `errors`.

The optional judge (`--judge`) sends the input, mode and output to the judge model with a fixed rubric and
a JSON schema. It answers `pass`, a 1–5 `score` and a `reason` code from a fixed list (for example
`followed_injected_instruction`, `wrong_script`). The verdict becomes the `judge` check.

## Reports

Reports go to `evals/results/` (not created until the first run). Each report has the overall and
per-language pass rates, per-check pass rates, median and p90 latency of the full call and of the first text
delta, the cache-read share of input tokens, token totals, the measured cost, error counts and every case's
result. Case outputs are kept so failures can be read; they are answers to authored inputs, not user
content.
