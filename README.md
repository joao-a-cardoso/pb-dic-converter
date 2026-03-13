# PocketBook Dictionary Converter

Convert **StarDict** or **Kaikki (Wiktionary)** dictionaries to PocketBook's native `.dic` format.

## Requirements

- Java 17+ JDK
- Language files from [LanguageFilesPocketbookConverter](https://github.com/Markismus/LanguageFilesPocketbookConverter) (one folder per language)
- For `converter.exe` path: Wine (Linux/macOS) + [`converter.exe`](https://www.mobileread.com/forums/showthread.php?t=68032)

## Pipelines

Two options for the final `.dic` conversion step:

```
StarDict
  .ifo + .idx + .dict.dz  →  stardict-2-tab  →  .tsv  →  tab-2-xdxf  →  .xdxf  →  xdxf-2-pbdic  →  .dic
  .ifo + .idx + .dict.dz  →  stardict-2-tab  →  .tsv  →  tab-2-xdxf  →  .xdxf  →  converter.exe  →  .dic

Kaikki (Wiktionary)
  .jsonl / .jsonl.gz       →  kaikki-2-tab   →  .tsv  →  tab-2-xdxf  →  .xdxf  →  xdxf-2-pbdic  →  .dic
  .jsonl / .jsonl.gz       →  kaikki-2-tab   →  .tsv  →  tab-2-xdxf  →  .xdxf  →  converter.exe  →  .dic
```

`xdxf-2-pbdic` is a pure-Java tool included in this toolchain — no Wine or closed-source dependencies required.  
`converter.exe` is the original Windows tool; run it via Wine on Linux/macOS.

The `.tsv` intermediate is kept on disk for inspection. Use `out=-` / `in=-` to pipe directly between tools and skip it.

## Quick Start

```bash
chmod +x linux/stardict-2-tab linux/kaikki-2-tab linux/tab-2-xdxf linux/xdxf-2-pbdic linux/check-pbdic

# StarDict → .dic (using xdxf-2-pbdic)
linux/stardict-2-tab -i data/stardict/dict-pt-pt/dict-data.ifo -o data/out/dict-pt-pt.tsv
linux/tab-2-xdxf    -i data/out/dict-pt-pt.tsv -o data/out/dict-pt-pt.xdxf -l pt -n "Dicionário PT-PT"
linux/xdxf-2-pbdic  -i data/out/dict-pt-pt.xdxf -o data/out/dict-pt-pt.dic -l pt -D windows/pt -j false

# Kaikki → .dic (using xdxf-2-pbdic)
linux/kaikki-2-tab -i data/kaikki/pt-extract.jsonl -l pt -e SEPARATE -o data/out/kaikki-pt.tsv
linux/tab-2-xdxf   -i data/out/kaikki-pt.tsv -o data/out/kaikki-pt.xdxf -l pt -n "Dicionário PT (Kaikki)"
linux/xdxf-2-pbdic -i data/out/kaikki-pt.xdxf -o data/out/kaikki-pt.dic -l pt -D windows/pt -j true

# Kaikki → .xdxf piped (no intermediate .tsv), then convert with converter.exe
linux/chain-kaikki-2-xdxf kiakki2xdxf-pten-pt.config
cd windows/ && wine ./converter.exe ../data/out/kaikki-pten-pt.xdxf pt
```

## Config Files

Each conversion has its own config file. All tools read it via `-c` and use their own namespace — CLI arguments always override config values.

```bash
# Run a full piped conversion
linux/chain-kaikki-2-xdxf kiakki2xdxf-pten-pt.config
linux/chain-stardict-2-xdxf stardict2xdxf-pt-pt.config
```

Example config (`kiakki2xdxf-pten-pt.config`):

```properties
kaikki2tab.in=data/kaikki/pt-extract.jsonl.gz
kaikki2tab.out=-
kaikki2tab.lang=pt,en
kaikki2tab.embedded-defs=SEPARATE

tab2xdxf.in=-
tab2xdxf.out=data/out/kaikki-pten-pt.xdxf
tab2xdxf.lang=pt,en
tab2xdxf.name=Wiktionary.org PT+EN->PT (Kaikki)

xdxf2pcdic.in=data/out/kaikki-pten-pt.xdxf
xdxf2pcdic.out=data/out/kaikki-pten-pt.dic
xdxf2pcdic.lang=pt
xdxf2pcdic.join=true
```

Namespaces: `stardict-2-tab` → `stardict2tab.*` · `kaikki-2-tab` → `kaikki2tab.*` · `tab-2-xdxf` → `tab2xdxf.*` · `xdxf-2-pbdic` → `xdxf2pcdic.*`

## Folder Structure

```
linux/          stardict-2-tab  kaikki-2-tab  tab-2-xdxf  xdxf-2-pbdic  check-pbdic
data/
  stardict/
    dict-pt-pt/ dict-data.ifo  .idx  .dict.dz
  kaikki/       pt-extract.jsonl  (or .jsonl.gz)
  out/          *.tsv  *.xdxf  *.dic
windows/
  converter.exe
  pt/           collates.txt  keyboard.txt  morphems.txt
kiakki2xdxf-pten-pt.config
kiakki2xdxf-pt-pt.config
stardict2xdxf-pt-pt.config
```

## Tools

| Tool | Input | Output |
|------|-------|--------|
| `stardict-2-tab` | StarDict `.ifo` | TSV |
| `kaikki-2-tab` | Kaikki `.jsonl` / `.jsonl.gz` | TSV |
| `tab-2-xdxf` | TSV | XDXF (XML-validated) |
| `xdxf-2-pbdic` | XDXF + language files | `.dic` |
| `check-pbdic` | `.dic` + `.xdxf` | Validation report |

See `pocketbook-dict-guide.html` for full documentation.

---

> **Legacy:** `dict-2-xdxf` (single-tool StarDict/tabfile → XDXF) is deprecated in favour of the modular toolchain above.
