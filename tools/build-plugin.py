#!/usr/bin/env python3
"""Package a plugin folder into a `.gcplugin` GameCore will install.

    tools/build-plugin.py plugin-template
    tools/build-plugin.py my-plugin --output dist/meu-plugin.gcplugin

The package is a zip of JSON and assets, nothing else — no classes, no scripts. This script does
the part a zip tool would get wrong: it checks the manifest against the same rules the app's loader
uses (required keys, known keys, declared sections matching files on disk), and it packs only the
entries the format allows. A README or a stray note in the folder is *not* packed: GameCore rejects
a package carrying a file outside the declared sections, so including documentation would produce a
file that looks fine on disk and fails on the phone.

The output is deterministic — entries sorted, fixed timestamps — so two builds of the same folder
produce byte-identical packages and the same SHA-256.
"""

import argparse
import hashlib
import json
import os
import sys
import zipfile

MANIFEST = "manifest.json"
REQUIRED = ("format", "id", "name", "version", "api", "minHostVersion")
KNOWN = {
    "format", "id", "name", "version", "versionName", "api", "minHostVersion",
    "developer", "description", "permissions", "declares", "changelog",
}
FORMAT = "gamecore.plugin"

# Section id -> (entry prefix or exact file, is_directory).
SECTIONS = {
    "theme": ("theme.json", False),
    "pill": ("pill.json", False),
    "settings": ("settings.json", False),
    "icon": ("icon.json", False),
    "screens": ("screens/", True),
    "profiles": ("profiles/", True),
    "rules": ("rules/", True),
    "aimlab": ("aimlab/", True),
    "stats": ("stats/", True),
    "widgets": ("widgets/", True),
}

# Documentation that belongs beside the plugin but not inside the package.
IGNORED = {"README.md", "README.txt", "BUILD.md"}


def fail(message):
    print(f"error: {message}", file=sys.stderr)
    sys.exit(1)


def load_manifest(folder):
    path = os.path.join(folder, MANIFEST)
    if not os.path.isfile(path):
        fail(f"{path} is missing")
    try:
        with open(path, encoding="utf-8") as handle:
            manifest = json.load(handle)
    except (OSError, json.JSONDecodeError) as error:
        fail(f"{path} is not valid JSON: {error}")
    if not isinstance(manifest, dict):
        fail(f"{path} must contain a JSON object")

    for key in REQUIRED:
        if key not in manifest:
            fail(f"manifest is missing required key {key!r}")
    unknown = sorted(set(manifest) - KNOWN)
    if unknown:
        fail(f"manifest has keys GameCore does not know: {', '.join(unknown)}")
    if manifest["format"] != FORMAT:
        fail(f"format must be {FORMAT!r}, not {manifest['format']!r}")
    for key in ("version", "api", "minHostVersion"):
        value = manifest.get(key)
        if not isinstance(value, int) or isinstance(value, bool) or value < 1:
            fail(f"{key} must be a positive integer")

    declares = manifest.get("declares", [])
    if not isinstance(declares, list):
        fail("declares must be a list of section ids")
    unknown_sections = sorted(set(declares) - set(SECTIONS))
    if unknown_sections:
        fail(f"unknown section(s) in declares: {', '.join(unknown_sections)}")

    permissions = manifest.get("permissions", [])
    if not isinstance(permissions, list):
        fail("permissions must be a list")
    for permission in permissions:
        if isinstance(permission, str):
            continue
        if not isinstance(permission, dict) or "id" not in permission:
            fail("each permission must be a string or an object with an id")
        extra = sorted(set(permission) - {"id", "scopes"})
        if extra:
            fail(f"permission {permission['id']!r} has unknown keys: {', '.join(extra)}")
    return manifest


def section_files(folder, section):
    prefix, is_directory = SECTIONS[section]
    path = os.path.join(folder, prefix.rstrip("/"))
    if is_directory:
        if not os.path.isdir(path):
            fail(f"declares {section!r} but {prefix} is missing")
        found = []
        for root, _, files in os.walk(path):
            for name in sorted(files):
                full = os.path.join(root, name)
                relative = os.path.relpath(full, folder).replace(os.sep, "/")
                found.append(relative)
        if not found:
            fail(f"declares {section!r} but {prefix} contains no files")
        return found
    if not os.path.isfile(path):
        fail(f"declares {section!r} but {prefix} is missing")
    return [prefix]


def collect(folder, manifest):
    entries = {MANIFEST}
    for section in manifest.get("declares", []):
        for relative in section_files(folder, section):
            entries.add(relative)

    # Preview images and loose theme/screen assets are allowed without being declared.
    for allowed in ("previews", "assets"):
        root = os.path.join(folder, allowed)
        if not os.path.isdir(root):
            continue
        for walk_root, _, files in os.walk(root):
            for name in files:
                full = os.path.join(walk_root, name)
                entries.add(os.path.relpath(full, folder).replace(os.sep, "/"))

    # Everything else is documentation or a mistake. Documentation is skipped; a mistake is fatal,
    # because packing it would produce a package the loader rejects with DISALLOWED_FILE.
    present = set()
    for walk_root, dirs, files in os.walk(folder):
        dirs[:] = [name for name in dirs if not name.startswith(".")]
        for name in files:
            if name.startswith("."):
                continue
            present.add(os.path.relpath(os.path.join(walk_root, name), folder).replace(os.sep, "/"))

    for relative in sorted(present - entries):
        if os.path.basename(relative) in IGNORED:
            continue
        fail(
            f"{relative} is not allowed in a .gcplugin and is not documentation "
            f"(allowed: {', '.join(sorted(entries))})"
        )
    return sorted(entries)


def main():
    parser = argparse.ArgumentParser(description="Package a GameCore plugin folder.")
    parser.add_argument("folder", help="plugin folder containing manifest.json")
    parser.add_argument("--output", "-o", help="output .gcplugin path")
    args = parser.parse_args()

    folder = os.path.abspath(args.folder)
    if not os.path.isdir(folder):
        fail(f"{folder} is not a directory")
    manifest = load_manifest(folder)
    entries = collect(folder, manifest)

    version_name = str(manifest.get("versionName") or manifest["version"])
    output = args.output or os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "dist",
        f"{manifest['id']}-{version_name}.gcplugin",
    )
    os.makedirs(os.path.dirname(os.path.abspath(output)), exist_ok=True)

    # Fixed metadata makes the package reproducible; 1980-01-01 is the zip epoch.
    stamp = (1980, 1, 1, 0, 0, 0)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for relative in entries:
            info = zipfile.ZipInfo(relative, stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with open(os.path.join(folder, relative), "rb") as handle:
                archive.writestr(info, handle.read())

    digest = hashlib.sha256(open(output, "rb").read()).hexdigest()
    with open(output + ".sha256", "w", encoding="utf-8") as handle:
        handle.write(f"{digest}  {os.path.basename(output)}\n")

    print(f"built {output}")
    print(f"sha256 {digest}")
    print(f"entries: {', '.join(entries)}")


if __name__ == "__main__":
    main()
