# htmx (vendored)

| | |
|---|---|
| Project | htmx, https://github.com/bigskysoftware/htmx |
| Version | 2.0.11 (release `v2.0.11`, published 2026-11-01) |
| File | `web/src/main/resources/static/htmx-2.0.11.min.js` (served as `/assets/htmx-2.0.11.min.js`) |
| SHA-256 of the file | `d6fdc75f204e6bdefa99b69bf1e6d4ac69b8a364f77929f45c13476b4000f717` |
| Taken from | `dist/htmx.min.js` inside the official source archive `https://github.com/bigskysoftware/htmx/archive/refs/tags/v2.0.11.tar.gz` |
| SHA-256 of that archive | `665124df2a65996a8fb18152ef214cada197ffba63f498b5611566c4cbc0ba1b` |
| Release asset digest | The release page lists `htmx.min.js` with the same SHA-256 as the file above (checked on 2026-11-08) |
| Size | 52,182 bytes (16,908 bytes gzipped) |
| Licence | Zero-Clause BSD, read from `LICENSE` in the archive (copied here as `LICENSE`) |

## How it was obtained

The hash was computed on this machine from the archive downloaded from the project's GitHub release, not copied from a web page: download the archive, `shasum -a 256` it, unpack it, `shasum -a 256 dist/htmx.min.js`. The file is unmodified.

## Why it is pinned

It is the largest piece of code in the signed-in page that this project did not write. The page's content security policy allows scripts from this origin only, so the file is served from here and never from a CDN. `HtmxProvenanceTest` fails the build if the bytes change, if the file name stops carrying the version, or if this note and the test disagree.

## To upgrade

Download the new official archive, compute both hashes, replace the file (the name carries the version), update this note and the constants in `HtmxProvenanceTest`, read the release notes for changes to the configuration keys in `fragments/layout.html`, and run the browser check in `tools/browser-check/`.
