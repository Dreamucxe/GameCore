#!/usr/bin/env python3
"""Build the GameCore language catalogues from the English source catalogue.

`app/src/main/assets/i18n/en.json` is the source of truth: every key, in English. This script
translates each value into every other supported language and writes one JSON file per language
beside it. Running it again rewrites the catalogues from the current English file, so the workflow
for adding a line is: add it to `en.json`, run this, then fix any wording a machine cannot get right.

Only the values are translated. Keys, JSON structure and the `{0}` argument markers are preserved;
brand names and Android API names are masked before the request and restored afterwards so Google
does not transliterate them (`Shizuku` became `しずく` without the mask).

Usage:
    tools/translate-catalog.py                 # every language missing a file
    tools/translate-catalog.py --all           # rewrite every catalogue
    tools/translate-catalog.py --only de,fr    # specific tags
"""

import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOGUE_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "i18n")
SOURCE = os.path.join(CATALOGUE_DIR, "en.json")

# Google's target codes, where they differ from the BCP-47 tag GameCore stores.
TARGETS = {
    "es": "es", "fr": "fr", "de": "de", "it": "it", "pt-BR": "pt-BR",
    "ru": "ru", "uk": "uk", "pl": "pl", "cs": "cs", "sk": "sk",
    "ro": "ro", "hu": "hu", "el": "el", "tr": "tr", "ar": "ar",
    "he": "he", "fa": "fa", "ur": "ur", "hi": "hi", "bn": "bn",
    "id": "id", "ms": "ms", "vi": "vi", "th": "th", "ja": "ja",
    "ko": "ko", "zh-CN": "zh-CN", "zh-TW": "zh-TW", "nl": "nl", "sv": "sv",
    "da": "da", "fi": "fi", "nb": "no", "bg": "bg", "hr": "hr",
    "sr": "sr", "fil": "tl", "sw": "sw", "af": "af", "ca": "ca",
    "ka": "ka", "hy": "hy", "az": "az", "kk": "kk", "uz": "uz",
    "lt": "lt", "lv": "lv", "et": "et", "sl": "sl", "is": "is",
}

# Masked before translation, restored after. Order matters: longer names first.
PROTECTED = [
    "GameCore", "Aim Lab", "Shizuku", "Android", "Setup health", "ADB", "1.1.1.1",
]
MASK_OPEN = "\u27e6"   # ⟦
MASK_CLOSE = "\u27e7"  # ⟧

BATCH = 12


def mask(text):
    for index, word in enumerate(PROTECTED):
        text = text.replace(word, f"{MASK_OPEN}{index}{MASK_CLOSE}")
    return text


def unmask(text):
    for index, word in enumerate(PROTECTED):
        text = text.replace(f"{MASK_OPEN}{index}{MASK_CLOSE}", word)
    return text


def request(lines, target, attempts=8):
    body = urllib.parse.urlencode({
        "client": "gtx", "sl": "en", "tl": TARGETS[target], "dt": "t",
        "q": "\n".join(mask(line) for line in lines),
    }).encode()
    delay = 15.0
    for attempt in range(attempts):
        try:
            req = urllib.request.Request(
                "https://translate.googleapis.com/translate_a/single",
                data=body,
                headers={"User-Agent": "GameCore-catalogue-builder/1.0"},
            )
            with urllib.request.urlopen(req, timeout=60) as response:
                payload = json.load(response)
            translated = "".join(part[0] for part in payload[0])
            return [unmask(line.strip()) for line in translated.split("\n")]
        except Exception as error:  # network, rate limit, malformed response
            if attempt == attempts - 1:
                raise
            print(f"  {target}: {error}; retrying in {delay:g}s", file=sys.stderr)
            time.sleep(delay)
            delay = min(delay * 2, 120.0)
    raise RuntimeError("unreachable")


def translate_language(source, target):
    result = {}
    keys = list(source)
    for start in range(0, len(keys), BATCH):
        chunk = keys[start:start + BATCH]
        lines = [source[key] for key in chunk]
        translated = request(lines, target)
        if len(translated) != len(chunk):
            # A long line can be split by the service; redo this chunk one key at a time.
            translated = []
            for key in chunk:
                one = request([source[key]], target)
                translated.append(one[0] if one else source[key])
        for key, value in zip(chunk, translated):
            # An empty answer is a failed translation, not an instruction to hide the line.
            result[key] = value or source[key]
        time.sleep(1.5)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--all", action="store_true", help="rewrite catalogues that already exist")
    parser.add_argument("--only", help="comma-separated BCP-47 tags")
    args = parser.parse_args()

    with open(SOURCE, encoding="utf-8") as handle:
        source = json.load(handle)

    if args.only:
        wanted = [tag.strip() for tag in args.only.split(",") if tag.strip()]
    else:
        wanted = list(TARGETS)

    for tag in wanted:
        if tag not in TARGETS:
            print(f"skipping {tag}: not a supported target", file=sys.stderr)
            continue
        destination = os.path.join(CATALOGUE_DIR, f"{tag}.json")
        if os.path.exists(destination) and not args.all and not args.only:
            continue
        print(f"{tag} → {destination}", flush=True)
        try:
            catalogue = translate_language(source, tag)
        except Exception as error:
            # One rate-limited language must not abandon the other forty. The next run skips
            # what is already on disk, so leaving a gap is cheaper than losing the whole batch.
            print(f"  {tag}: gave up for now: {error}", file=sys.stderr, flush=True)
            continue
        with open(destination, "w", encoding="utf-8") as handle:
            json.dump(catalogue, handle, ensure_ascii=False, indent=2)
            handle.write("\n")


if __name__ == "__main__":
    main()
