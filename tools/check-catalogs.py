#!/usr/bin/env python3
"""Check every language catalogue against the English source.

    tools/check-catalogs.py            # every selectable language
    tools/check-catalogs.py sv da fi   # only the named tags

Exits non-zero on the first problem and prints every problem it finds first. The same rules are
covered by `AppLanguageCatalogTest`, but this runs in a second rather than a Gradle test run, which
is what makes it useful while the catalogues are being written.
"""

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIRECTORY = os.path.join(ROOT, "app", "src", "main", "assets", "i18n")
SOURCE = os.path.join(DIRECTORY, "en.json")
MARKER = re.compile(r"\{\d+}")
PROTECTED = ["GameCore", "Aim Lab", "Shizuku", "Android", "Setup health", "ADB", "1.1.1.1"]


def load(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def check(tag, english, catalogue):
    problems = []
    missing = sorted(set(english) - set(catalogue))
    extra = sorted(set(catalogue) - set(english))
    if missing:
        problems.append(f"{tag}: missing keys: {', '.join(missing)}")
    if extra:
        problems.append(f"{tag}: unknown keys: {', '.join(extra)}")
    for key, value in catalogue.items():
        if not isinstance(value, str) or not value.strip():
            problems.append(f"{tag}: blank or non-string value for {key}")
            continue
        if key in english:
            if sorted(MARKER.findall(value)) != sorted(MARKER.findall(english[key])):
                problems.append(
                    f"{tag}: placeholders changed in {key}: "
                    f"{MARKER.findall(english[key])} -> {MARKER.findall(value)}"
                )
            for word in PROTECTED:
                if word in english[key] and word not in value:
                    problems.append(f"{tag}: protected name {word!r} missing from {key}")
    return problems


def main():
    tags = sys.argv[1:]
    if not tags:
        tags = sorted(
            os.path.splitext(name)[0]
            for name in os.listdir(DIRECTORY)
            if name.endswith(".json") and name != "en.json"
        )
    english = load(SOURCE)
    problems = []
    for tag in tags:
        path = os.path.join(DIRECTORY, f"{tag}.json")
        if not os.path.isfile(path):
            problems.append(f"{tag}: no catalogue at {path}")
            continue
        try:
            catalogue = load(path)
        except json.JSONDecodeError as error:
            problems.append(f"{tag}: invalid JSON: {error}")
            continue
        problems.extend(check(tag, english, catalogue))
    if problems:
        print("\n".join(problems))
        sys.exit(1)
    print(f"ok: {len(tags)} catalogue(s), {len(english)} keys each")


if __name__ == "__main__":
    main()
