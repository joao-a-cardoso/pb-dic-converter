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

# Source → .xdxf piped in one step (no intermediate .tsv), driven by a config file
linux/chain-kaikki-2-xdxf   confs/kaikki2xdxf-pt-pt.config
linux/chain-stardict-2-xdxf confs/stardict2xdxf-pt-pt.config
```

The Kaikki source data is not in the repository; see `data/kaikki/Download-Portuguese.html` for where to download it.

The `chain-*` scripts must be run from the project root (they call `linux/...` with relative paths). They stop at `.xdxf`; run `xdxf-2-pbdic` or `xdxf-2-stardict` afterwards.

## Config Files

Each conversion has its own config file in `confs/`. All tools read it via `-c` and only use the keys in their own namespace — CLI arguments always override config values.

| Tool | Namespace | Keys |
|------|-----------|------|
| `stardict-2-tab`  | `stardict2tab.*`  | `in`, `out` |
| `kaikki-2-tab`    | `kaikki2tab.*`    | `in`, `out`, `lang`, `embedded-defs` |
| `tab-2-xdxf`      | `tab2xdxf.*`      | `in`, `out`, `lang`, `lang-to`, `name`, `desc`, `validate` |
| `xdxf-2-pbdic`    | `xdxf2pcdic.*`    | `in`, `out`, `lang`, `langdir`, `name`, `merge-defs` |
| `xdxf-2-stardict` | `xdxf2stardict.*` | `in`, `out`, `name`, `merge-defs`, `compress` |
| `check-pbdic`     | `checkpbdic.*`    | `xdxf`, `dic`, `lang`, `langdir`, `sample` |

> Note: the `xdxf-2-pbdic` namespace is spelled `xdxf2pcdic` in the code.

Example config (`confs/kaikki2xdxf-pt-pt.config`):

```properties
kaikki2tab.in=data/kaikki/kaikki.org-all-to-Portuguese.jsonl.gz
kaikki2tab.out=-
kaikki2tab.lang=pt
kaikki2tab.embedded-defs=BOTH

tab2xdxf.in=-
tab2xdxf.out=data/out/kaikki-pt-pt13.xdxf
tab2xdxf.lang=pt
tab2xdxf.lang-to=pt
tab2xdxf.name=Wiktionary.org pt-pt (Kaikki/13)
tab2xdxf.desc=Wiktionary.org pt-pt (built from Kaikki's source data)
```

Available configs:

| Config | Source | Output |
|--------|--------|--------|
| `confs/kaikki2xdxf-pt-pt.config`  | Kaikki, Portuguese words → Portuguese definitions | `data/out/kaikki-pt-pt13.xdxf` |
| `confs/kaikki2xdxf-en-pt.config`  | Kaikki, English words → Portuguese definitions    | `data/out/kaikki-en-pt.xdxf` |
| `confs/stardict2xdxf-pt-pt.config` | StarDict `data/stardict/dict-pt-pt` (reader-dict) | `data/out/readerdict-pt-pt.xdxf` |

## Tools

| Tool | Input | Output | Main options |
|------|-------|--------|--------------|
| `stardict-2-tab`  | StarDict `.ifo` (`.idx`/`.dict.dz` found alongside) | TSV | `-i`, `-o` |
| `kaikki-2-tab`    | Kaikki `.jsonl` / `.jsonl.gz` | TSV | `-i`, `-o`, `-l <iso2>`, `-e KEEP\|SEPARATE\|BOTH` (default `BOTH`) |
| `tab-2-xdxf`      | TSV | XDXF (XML-validated) | `-i`, `-o`, `-l`, `-t <lang-to>` (default `-l`), `-n <name>`, `--desc <desc>` (default name), `-v true\|false` (default `true`) |
| `xdxf-2-pbdic`    | XDXF + language files | `.dic` | `-i`, `-o`, `-l`, `-m ALWAYS\|EXACT\|NEVER`, `-D <langdir>` (default `lang/<lang>`), `-n <name>` |
| `xdxf-2-stardict` | XDXF | StarDict directory (`stardict.ifo/.idx/.dict[.dz]`) | `-i`, `-o <dir>`, `-m ALWAYS\|EXACT\|NEVER`, `-z true\|false` (compress), `-n <name>` |
| `check-pbdic`     | `.dic` + `.xdxf` | Validation report (exit 1 on failure) | `-x`, `-d`, `-l`, `-D <langdir>` (default `lang/<lang>`), `-s <samples>` (default 20) |

Every tool accepts `-c <config>` and `--help`. Progress messages go to stderr, so stdout is safe for piping.

`--merge-defs` (duplicate headwords):

- `ALWAYS` — case-insensitive headword match; merge different definitions, drop identical ones
- `EXACT` — case-sensitive headword match; merge different definitions, drop identical ones
- `NEVER` — never merge; only drop byte-identical duplicates

If `--name` is not given, `xdxf-2-pbdic` and `xdxf-2-stardict` take the name from the XDXF header, falling back to the output file name.

## Folder Structure

```
linux/          stardict-2-tab  kaikki-2-tab  tab-2-xdxf  xdxf-2-pbdic  xdxf-2-stardict  check-pbdic
                chain-kaikki-2-xdxf  chain-stardict-2-xdxf  runjava
java/           ConvStardict2Tab.java  ConvKaikki2Tab.java  ConvTab2Xdxf.java
                ConvXdxf2Pbdic.java    ConvXdxf2Stardict.java  CheckPbdic.java
                term-translations_pt.properties
  shared/       ArgsHelper  Constants  DictzipUtil  JsonParser  LangHelper  XdxfHelper  XmlHelper
confs/          kaikki2xdxf-en-pt.config  kaikki2xdxf-pt-pt.config  stardict2xdxf-pt-pt.config
data/
  stardict/
    dict-pt-pt/ dict-data.ifo  .idx  .dict.dz  .syn
  kaikki/       kaikki.org-all-to-Portuguese.jsonl.gz  (not in git — see Download-Portuguese.html)
  out/          *.tsv  *.xdxf  *.dic  *.d/  (not in git)
lang/
  en/  pt/      collates.txt  keyboard.txt  morphems.txt
windows/        converter.exe and language files for the original Windows converter (not in git)
docs/           sdic_format.pdf  toolchain-design-reference.pdf
```

## Project Structure

The tool logic lives in `java/` as standard `.java` source files (one class per tool, plus helpers in `java/shared/`).
The `linux/` scripts are thin wrappers that delegate to `runjava`, which locates the
`java/` directory relative to itself and invokes `java --source 25 <ClassName>.java`.
No compilation step is needed.

---

> **Legacy:** `dict-2-xdxf` (single-tool StarDict/tabfile → XDXF) is deprecated in favour of the modular toolchain above; `data/dict-2-xdf.conf` is its leftover config.
