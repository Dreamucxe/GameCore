# Plugin template

A complete, installable GameCore plugin in two JSON files. No Android build, no Kotlin, no
dependencies — copy this folder, edit the two files, and package it with `tools/build-plugin.py`.

```
plugin-template/
    manifest.json   identity, permissions and the list of sections
    theme.json      the colours, corner radius and typeface
```

## Build it

From the repository root:

```bash
python3 tools/build-plugin.py plugin-template
```

That writes `dist/com.example.hello-1.0.0.gcplugin` and a `.sha256` beside it. Copy the
`.gcplugin` to the phone, open **Settings → Plugins → Choose files**, and pick it. A new plugin
arrives switched off with every permission ungranted; the detail screen is where you grant
`change_ui` and turn it on.

The build script refuses a package GameCore would refuse:

- a missing required manifest key, or a key GameCore does not know;
- a section listed in `declares` with no file, or a section file that is not declared;
- a single-file section anywhere but at its exact path (`theme.json`, `pill.json`, `settings.json`,
  `icon.json`);
- any file GameCore would treat as `DISALLOWED_FILE` (`README.md` and dotfiles are documentation
  and are simply not packed).

## Make it yours

1. Change `id` to a name you own, in reverse-domain form: `com.yourname.yourplugin`. Two
   plugins cannot share an id.
2. Change `name`, `developer` and `description`. They are what the install screen shows.
3. Edit `theme.json`. The six required colours are `primary`, `onPrimary`, `background`,
   `surface`, `onSurface` and `accent`; `roles` adds the softer UI colours. Every colour must be
   `#RRGGBB` or `#AARRGGBB`. Raise `version` and `versionName` for every release — GameCore uses
   `version` to tell an update from a downgrade.

## Add more than a theme

A plugin can declare any of the sections GameCore defines. Start with
[`docs/PLUGIN_FORMAT.md`](../docs/PLUGIN_FORMAT.md) for the package layout and the complete
permission list, then [`docs/PLUGIN_SDK.md`](../docs/PLUGIN_SDK.md) for the Kotlin DSL that
type-checks a definition before it becomes a package. The host accepts only the JSON those
documents describe: there is no code, no network access and no way to grant yourself a
permission from inside a package.
