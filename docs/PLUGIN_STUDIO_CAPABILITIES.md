# Plugin Studio capability boundary

For the user-facing workspace map and step-by-step authoring flow, see the
[Plugin Studio feature map and tutorial](PLUGIN_STUDIO_FEATURE_GUIDE.md).

This document records what GameCore's data-only plugin runtime can actually execute. It is the
contract Plugin Studio follows: an editor control is offered only when the exported value reaches a
real host codec and a real runtime consumer. Requested features outside this table are not presented
as working.

## Runtime-backed creator sections

| Creator area | Package entry | Real host behavior | Required request |
| --- | --- | --- | --- |
| Theme | `theme.json` | Applies schema 1–4 colours, roles, type, glitch and glass | `change_ui` |
| Read-only screens | `screens/screens.json` | Opens validated component trees from a plugin detail page | `change_ui`; live readings also need `read_approved_data` scopes |
| Monitoring overlay | `pill.json` | Draws one bounded, non-touchable overlay window while the host overlay service is active | `create_overlay`; live readings also need data scopes |
| Typed settings | `settings.json` | Host-rendered bool, integer, float, enum and string controls stored in the plugin namespace | `store_data` |
| Dashboard widgets | `widgets/<id>.json` | Draws bounded, read-only cards on Home | `add_dashboard_widgets`; live readings also need data scopes |
| Automations | `rules/rules.json` | Evaluates one edge trigger plus a flat AND of conditions, then requests allowlisted broker actions | `run_automations` and `system_actions` |

Every permission in a package is only a request. New installs are disabled with an empty grant;
GameCore remains the authority that enables the plugin and grants each permission and scope.

## Closed component and data vocabulary

Studio authors only the component types whose current renderer has meaningful read-only behavior:
`column`, `row`, `grid`, `scroll`, `card`, `text`, `stat`, `spacer`, `image` and `chart`.
It deliberately does not offer `button`, `slider` or `switch` in a plugin screen. The host codec can
parse those three types, but the current renderer draws them as inert state illustrations and has no
action callback; presenting them as controls would be false functionality.

Overlay modules are tighter: `scroll` is excluded because the overlay window has
`FLAG_NOT_TOUCHABLE`. Overlay charts are literal-only. Screens and dashboard widgets can display
instant FPS, CPU, GPU, RAM, battery and temperature readings. Their retained live chart series are
limited to FPS, CPU, RAM and temperature. Rotation-rate and movement readings are available only in
the overlay service, which is the only one that owns the motion sampler.

Automations are limited to the seven host triggers, six numeric variables, six comparison operators
and seven broker actions compiled into GameCore. Rules carry no command text, shell, arbitrary
settings key, action value, loop, branch, delay, schedule or retry.

## Features that are not represented as working

The current runtime has no safe, complete execution path for:

- touchable or resizable plugin overlays, multiple overlay screens, or custom overlay z-order;
- screen buttons that dispatch an action, navigation or settings write;
- arbitrary Android intents, external-app control, network access, shell commands or executable
  plugin code;
- plugin-to-plugin messaging or shared plugin storage;
- scheduled, branching, parallel, looping or retrying workflows;
- generic setting bindings for screens and widgets (schema-4 glass intensity is the one implemented
  setting-to-behavior bridge);
- package authenticity signed by GameCore's release key on the phone. Shipping that private key in an
  APK would destroy its trust value; the host can verify package structure and SHA-256 integrity, but
  a creator build cannot safely impersonate the release signer;
- per-plugin assets, sounds, downloaded fonts or arbitrary vectors in component trees;
- a marketplace. It is explicitly outside the requested scope.

## Simulation and validation rule

A creator preview or sandbox may use simulated readings and outcomes only when every such value is
visibly labelled **simulation**. It must never pass a simulated result to the production broker, and
must never describe a simulated action as a device change. Package tests validate structure and
schema; only GameCore's trusted runtime can report a real permission, metric or action outcome.
