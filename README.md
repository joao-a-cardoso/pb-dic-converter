# PocketBook Dictionary Converter

Convert **StarDict** or **Kaikki (Wiktionary)** dictionaries to PocketBook's native `.dic` format — or to StarDict (for KOReader and other StarDict readers).

📖 **[Open the full guide](https://htmlpreview.github.io/?https://github.com/joao-a-cardoso/pb-dic-converter/blob/master/pocketbook-dict-guide.html)**

## Requirements

- Java **25** JDK (`java` must be on PATH) — the tools are run as single-file source programs with `java --source 25`
- Language files from [LanguageFilesPocketbookConverter](https://github.com/Markismus/LanguageFilesPocketbookConverter) (one folder per language; `en` and `pt` are included in `lang/`)

## Pipelines

```
StarDict
  .ifo + .idx + .dict.dz  →  stardict-2-tab  →  .tsv  →  tab-2-xdxf  →  .xdxf  ─┬→  xdxf-2-pbdic     →  .dic
                                                                                 │
Kaikki (Wiktionary)                                                              │
  .jsonl / .jsonl.gz      →  kaikki-2-tab    →  .tsv  →  tab-2-xdxf  →  .xdxf  ─┴→  xdxf-2-stardict  →  StarDict (.ifo/.idx/.dict[.dz])

                                         check-pbdic  validates a .dic against its source .xdxf
```

`xdxf-2-pbdic` is a pure-Java tool included in this toolchain — no Wine or closed-source dependencies required.

The `.tsv` intermediate can be kept on disk for inspection. Use `-o -` / `-i -` (or `out=-` / `in=-` in a config file) to pipe directly between tools and skip it.

## Quick Start

```bash
chmod +x linux/*

# StarDict → .dic
linux/stardict-2-tab -i data/stardict/dict-pt-pt/dict-data.ifo -o data/out/dict-pt-pt.tsv
linux/tab-2-xdxf     -i data/out/dict-pt-pt.tsv -o data/out/dict-pt-pt.xdxf -l pt -n "Dicionário PT-PT"
linux/xdxf-2-pbdic   -i data/out/dict-pt-pt.xdxf -o data/out/dict-pt-pt.dic -l pt -m EXACT

# Kaikki → .dic
linux/kaikki-2-tab -i data/kaikki/kaikki.org-all-to-Portuguese.jsonl.gz -o data/out/kaikki-pt.tsv -l pt -e SEPARATE
linux/tab-2-xdxf   -i data/out/kaikki-pt.tsv -o data/out/kaikki-pt.xdxf -l pt -n "Dicionário PT (Kaikki)"
linux/xdxf-2-pbdic -i data/out/kaikki-pt.xdxf -o data/out/kaikki-pt.dic -l pt -m ALWAYS

# Validate the generated .dic
linux/check-pbdic -x data/out/kaikki-pt.xdxf -d data/out/kaikki-pt.dic -l pt

# XDXF → StarDict (writes data/out/kaikki-pt.d/stardict.{ifo,idx,dict.dz})
linux/xdxf-2-stardict -i data/out/kaikki-pt.xdxf -o data/out/kaikki-pt.d -m ALWAYS -z true

# Source → .xdxf in one step, driven by a config file
linux/chain-kaikki-2-xdxf   confs/kaikki2xdxf-pt-pt.config
linux/chain-stardict-2-xdxf confs/stardict2xdxf-pt-pt.config

# Kaikki → StarDict, the whole pipeline in one step
linux/chain-kaikki-2-stardict confs/kaikki2stardict-pt-pt.config

# … for another Kaikki version, without editing the config
linux/chain-kaikki-2-stardict confs/kaikki2stardict-pt-pt.config --def version=2026-12
```

The Kaikki source data is not in the repository; see `data/kaikki/Download-Portuguese.html` for where to download it.

Chain scripts — each runs several tools from **one** config file:

| Script | Steps | Config |
|---|---|---|
| `chain-kaikki-2-xdxf`     | Kaikki → TSV → XDXF | `confs/kaikki2xdxf-*.config` |
| `chain-stardict-2-xdxf`   | StarDict → TSV → XDXF | `confs/stardict2xdxf-*.config` |
| `chain-kaikki-2-stardict` | Kaikki → TSV → XDXF → StarDict | `confs/kaikki2stardict-*.config` (needs the `xdxf2stardict.*` keys) |

- They can be run from any folder; the config's relative paths are taken from the project root.
- They stop at the first failing step. The TSV goes to a temporary file, so if the first step fails, an existing `.xdxf` is not overwritten with an empty dictionary.
- `--def <name>=<value>` (repeatable) after the config is passed to every step — see *Variables and comments* below.
- `-h` shows the usage. To build a `.dic`, run `xdxf-2-pbdic` on the chain's `.xdxf`.

## Config Files

Each conversion has its own config file in `confs/`. All tools read it via `-c` and only use the keys in their own namespace — CLI arguments always override config values.

| Tool | Namespace | Keys |
|------|-----------|------|
| `stardict-2-tab`  | `stardict2tab.*`  | `in`, `out` |
| `kaikki-2-tab`    | `kaikki2tab.*`    | `in`, `out`, `lang`, `embedded-defs`, `terms-file` |
| `tab-2-xdxf`      | `tab2xdxf.*`      | `in`, `out`, `lang`, `lang-to`, `name`, `desc`, `validate` |
| `xdxf-2-pbdic`    | `xdxf2pbdic.*`    | `in`, `out`, `lang`, `langdir`, `name`, `merge-defs` |
| `xdxf-2-stardict` | `xdxf2stardict.*` | `in`, `out`, `name`, `merge-defs`, `compress` |
| `check-pbdic`     | `checkpbdic.*`    | `xdxf`, `dic`, `lang`, `langdir`, `sample` |


Key style — a config uses **either** prefixed keys **or** bare keys, never both:

- **Prefixed** (`tab2xdxf.in=`, `kaikki2tab.lang=`): for configs shared by several tools, as in `confs/`. Each tool reads only its own namespace and ignores the others. A key in its own namespace that it does not know (e.g. a typo such as `tab2xdxf.no-validate`) gives `Warning: unknown config key …, ignoring.`
- **Bare** (`in=`, `out=`, `lang=`): for a config used by a single tool; the keys apply to whichever tool reads the file.
- **Mixed**: rejected — every tool stops with `Error: config <file> mixes prefixed and bare keys; add a prefix to: …` (exit code 1), because a bare key in a shared config would be taken by every tool.

Variables and comments:

```properties
# a line starting with # is a comment
def lang=pt                                  # headword language
def lang_to=pt                               # definition language
def title=Wiktionary.org ${lang}-${lang_to}  # a variable may use other variables

kaikki2tab.lang=$lang
tab2xdxf.lang=$lang
tab2xdxf.lang-to=$lang_to
tab2xdxf.name=$title (Kaikki)                # → Wiktionary.org pt-pt (Kaikki)
```

- `def <name>=<value>` defines a variable (name: letters, digits, `_`); use it in any value as `$name`, or `${name}` when letters or digits follow. `$$` is a literal `$`.
- Variables are substituted in config values only — not in keys or command-line arguments. `def` lines are not keys, so they don't count for the prefixed/bare rule.
- A `#` preceded by a space or tab starts a comment, up to the end of the line. A `#` without whitespace before it is kept (`C#`); write `\#` for a literal `#` after a space.
- Errors (exit code 1): an undefined variable (`undefined variable '$lnag' in 'tab2xdxf.lang'`), a variable defined twice, a definition loop (`variable loop: a -> b -> a`) or a malformed `def` line.
- `--def <name>=<value>` on the command line (repeatable, every tool and chain script) replaces the config's `def` of the same name, or adds a new variable: `--def version=2026-12 --def lang=en`. It is listed as `def version : 2026-12 (command line)`. A malformed `--def` or the same name twice is an error; without `-c` it has no effect (warning).

Example config (`confs/kaikki2xdxf-pt-pt.config`):

```properties
def version=2026-11                          # Kaikki data version (output file and dictionary name)
def lang=pt                                  # headword language
def lang_to=pt                               # definition language
def title=Wiktionary.org ${lang}-${lang_to}  # start of the dictionary name and description

kaikki2tab.in=data/kaikki/kaikki.org-all-to-Portuguese.jsonl.gz
kaikki2tab.out=-
kaikki2tab.lang=$lang
kaikki2tab.embedded-defs=BOTH

tab2xdxf.in=-
tab2xdxf.out=data/out/kaikki-pt-pt-${version}.xdxf
tab2xdxf.lang=$lang
tab2xdxf.lang-to=$lang_to
tab2xdxf.name=$title (Kaikki $version)
tab2xdxf.desc=$title (built from Kaikki's source data)
```

Available configs:

| Config | Source | Output |
|--------|--------|--------|
| `confs/kaikki2xdxf-pt-pt.config`  | Kaikki, Portuguese words → Portuguese definitions | `data/out/kaikki-pt-pt-<version>.xdxf` |
| `confs/kaikki2xdxf-en-pt.config`  | Kaikki, English words → Portuguese definitions    | `data/out/kaikki-en-pt-<version>.xdxf` |
| `confs/stardict2xdxf-pt-pt.config` | StarDict `data/stardict/dict-pt-pt` (reader-dict) | `data/out/readerdict-pt-pt.xdxf` |
| `confs/kaikki2stardict-pt-pt.config` | as `kaikki2xdxf-pt-pt`, plus StarDict | `…-<version>.xdxf` and `data/out/kaikki-pt-pt-<version>.d/` |
| `confs/kaikki2stardict-en-pt.config` | as `kaikki2xdxf-en-pt`, plus StarDict | `…-<version>.xdxf` and `data/out/kaikki-en-pt-<version>.d/` |

## Tools

| Tool | Input | Output | Main options |
|------|-------|--------|--------------|
| `stardict-2-tab`  | StarDict `.ifo` (`.idx`/`.dict.dz` found alongside) | TSV | `-i`, `-o` |
| `kaikki-2-tab`    | Kaikki `.jsonl` / `.jsonl.gz` | TSV | `-i`, `-o`, `-l <iso2>`, `-e KEEP\|SEPARATE\|BOTH` (default `BOTH`), `--terms-file <file>` |
| `tab-2-xdxf`      | TSV | XDXF (XML-validated) | `-i`, `-o`, `-l`, `-t <lang-to>` (default `-l`), `-n <name>`, `--desc <desc>` (default name), `-v true\|false` (default `true`) |
| `xdxf-2-pbdic`    | XDXF + language files | `.dic` | `-i`, `-o`, `-l`, `-m ALWAYS\|EXACT\|NEVER`, `-D <langdir>` (default `lang/<lang>`), `-n <name>` |
| `xdxf-2-stardict` | XDXF | StarDict directory (`stardict.ifo/.idx/.dict[.dz]`) | `-i`, `-o <dir>`, `-m ALWAYS\|EXACT\|NEVER`, `-z true\|false` (compress), `-n <name>` |
| `check-pbdic`     | `.dic` + `.xdxf` | Validation report (exit 1 on failure) | `-x`, `-d`, `-l`, `-D <langdir>` (default `lang/<lang>`), `-s <samples>` (default 20) |

Every tool accepts `-c <config>`, `--def <name>=<value>` and `-h` / `--help`. Language codes (`-l`, `-t`) are two-letter ISO 639-1 codes, in any case (`pt`, `PT`). Progress messages go to stderr, so stdout is safe for piping.

Outputs are all or nothing (every converter: `kaikki-2-tab`, `stardict-2-tab`, `tab-2-xdxf`, `xdxf-2-pbdic`, `xdxf-2-stardict`): the output file, the StarDict files or stdout are written only at the end, once everything succeeded (validation included). Until then the output is kept in memory, compressed. On an error an existing output is left as it was, and a pipe receives nothing, so the next tool fails visibly instead of converting a cut-off input. A wrong output path (missing folder, read-only file) is reported at the start, before the work, in one line (no Java stack trace).

`kaikki-2-tab` label translations: labels such as *Etymology*, *feminine* or the English domain topics (*Botany*, *Medicine*) are translated with a `.properties` file (`english-term=translation`, case-insensitive). By default it is `java/term-translations_<lang>.properties`, from `-l`; `--terms-file <file>` (config: `kaikki2tab.terms-file`) uses another one. The file used is logged; a missing default file only means the labels stay in English. Dictionaries whose definitions are in another language than the headwords (EN → PT) should point to the definitions' language — `confs/kaikki2xdxf-en-pt.config` uses `java/term-translations_${lang_to}.properties`.

`--merge-defs` (duplicate headwords):

- `ALWAYS` — case-insensitive headword match; merge different definitions, drop identical ones
- `EXACT` — case-sensitive headword match; merge different definitions, drop identical ones
- `NEVER` — never merge; only drop byte-identical duplicates

Before merging, a definition repeated for the same headword (compared as the mode compares headwords) is kept only once, wherever the copies are: the first in sorted order stays (`Dropped <n> repeated definitions`). Entries are sorted so that the case variants of a word are next to each other (`xdxf-2-pbdic`: by the collation key, then the lower-case word, then the word).

With `ALWAYS`, a merged entry is named after its most common spelling: a common word before a name, a name before an acronym (judged by the spelling only: `casa`, `Fausta`, `FUNASA`), then the one with fewer capitals — `Fausta` + `fausta` → `fausta`, `CASA` + `casa` → `casa`. The same applies when a repeated definition is dropped (`Selá` + `selá` with the same definition → `selá`), except that an acronym keeps its capitals (`FUNASA` + `funasa` with the same definition → `FUNASA`).

PocketBook `.dic` size limit (`xdxf-2-pbdic` only — StarDict has no such limit): an entry must fit in one `.dic` block (27,000 bytes, the firmware's decompression buffer).
- Merging stops when the next definition would not fit: it starts a new entry with the same headword, and the following definitions are merged into that one (`Note: definition of '<word>' not joined: the result would be <n> bytes, more than fits in one block …`).
- A single definition that is too large on its own is truncated at a line or word boundary and ends with `[...]` (`Warning: definition of '<word>' is … bytes … truncated`). In the Kaikki 2026-11 data this only affects *sistema*.

`xdxf-2-stardict` writes `stardict.ifo`, `.idx` and `.dict.dz` (or `.dict`) into the `-o` folder; it replaces only these `stardict.*` files (and a stale `stardict.syn`) — other files in the folder are left alone. If one of them is read-only, or the folder is not writable, it stops before any work and leaves the previous build as it was.

If `--name` is not given, `xdxf-2-pbdic` and `xdxf-2-stardict` take the name from the XDXF header, falling back to the output file name.

## Folder Structure

```
linux/          stardict-2-tab  kaikki-2-tab  tab-2-xdxf  xdxf-2-pbdic  xdxf-2-stardict  check-pbdic
                chain-kaikki-2-xdxf  chain-stardict-2-xdxf  chain-kaikki-2-stardict  runjava
                library.sh   (shared functions of the chain-* scripts; sourced, not run)
java/           ConvStardict2Tab.java  ConvKaikki2Tab.java  ConvTab2Xdxf.java
                ConvXdxf2Pbdic.java    ConvXdxf2Stardict.java  CheckPbdic.java
                term-translations_pt.properties   (kaikki-2-tab label translations)
  shared/       ToolRunner  CliException  ToolLog  ArgsHelper   (run, errors, messages, options/config)
                IoHelper     (inputs/outputs: "-", .gz; all-or-nothing SafeOutput)
                XdxfHelper  XmlHelper  DictzipHelper  MergeMode  JsonParser  JsonException
                LangHelper  TermTranslator  TextHelper  Constants
confs/          kaikki2xdxf-en-pt.config  kaikki2xdxf-pt-pt.config  stardict2xdxf-pt-pt.config
                kaikki2stardict-en-pt.config  kaikki2stardict-pt-pt.config
data/
  stardict/
    dict-pt-pt/ dict-data.ifo  .idx  .dict.dz  .syn
  kaikki/       kaikki.org-all-to-Portuguese.jsonl.gz  (not in git — see Download-Portuguese.html)
  out/          *.tsv  *.xdxf  *.dic  *.d/  (not in git)
lang/
  en/  pt/      collates.txt  keyboard.txt  morphems.txt
windows/        converter.exe and language files for the original Windows converter (not in git)
docs/           sdic_format.pdf  toolchain-design-reference.pdf  architecture.html
  src/          toolchain-design-reference.html  sdic_format.html  (sources of the PDFs)
```

## Project Structure

The tool logic lives in `java/` as standard `.java` source files (one class per tool, plus helpers in `java/shared/`).
The `linux/` scripts are thin wrappers that delegate to `runjava`, which locates the
`java/` directory relative to itself and invokes `java --source 25 <ClassName>.java`.
No compilation step is needed.

The `chain-*` scripts source `linux/library.sh`, which holds what they share: argument handling (`-h`, the config file, `--def`), running from the project root, the config section check, the temporary TSV and the `[n/N]` step messages. A chain script itself only sets its name and number of steps and lists its tool calls.
