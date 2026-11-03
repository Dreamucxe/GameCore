"""The capability boundary, available even when the app is not running.

The app serves the authoritative copy from `StudioCapabilities.kt`. This fallback exists so an MCP
client can still ask what the studio supports before connecting — and so the tool does not pretend
the bridge is required to read a static document.
"""

CAPABILITIES = {
    "bridgeProtocol": 1,
    "packageFormat": "gamecore.plugin",
    "sections": [
        {
            "id": "theme",
            "entry": "theme.json",
            "hostBehavior": "Applies schema 1-4 colours, roles, type, glitch and glass.",
        },
        {
            "id": "screens",
            "entry": "screens/screens.json",
            "hostBehavior": "Read-only component trees opened from the plugin detail page.",
        },
        {
            "id": "overlay",
            "entry": "pill.json",
            "hostBehavior": "One fixed-size, non-touchable overlay while the host overlay service runs.",
        },
        {
            "id": "settings",
            "entry": "settings.json",
            "hostBehavior": "Host-rendered bool, integer, float, enum and string controls.",
        },
        {
            "id": "widgets",
            "entry": "widgets/<id>.json",
            "hostBehavior": "Bounded, read-only Home cards.",
        },
        {
            "id": "rules",
            "entry": "rules/rules.json",
            "hostBehavior": "One edge trigger, a flat AND of conditions and allowlisted broker actions.",
        },
    ],
    "components": [
        "column", "row", "grid", "scroll", "card",
        "text", "stat", "spacer", "image", "chart",
    ],
    "chartSeries": {
        "screensAndWidgets": ["fps", "cpu", "ram", "temperature"],
        "overlay": ["fps", "cpu", "gpu", "ram", "battery", "temperature", "motion"],
    },
    "automation": {
        "shape": "edge-triggered; one trigger and a flat AND of at most 10 conditions",
        "triggers": 7,
        "variables": ["fps", "cpu", "gpu", "ram", "battery", "temp"],
        "operators": 6,
        "actions": 7,
        "noBranching": True,
        "noScheduling": True,
        "noCommandText": True,
    },
    "limits": {
        "screens": 32,
        "overlays": 1,
        "settings": 64,
        "widgets": 32,
        "rules": 32,
        "conditionsPerRule": 10,
        "actionsPerRule": 8,
    },
    "unsupported": [
        "touchable, resizable or multiple overlays",
        "screen buttons or interactive controls with actions",
        "plugin messaging or shared plugin storage",
        "scheduled, branching, looping, retrying or parallel workflows",
        "arbitrary intents, network access, shell commands or executable code",
        "per-plugin assets, sounds, fonts or vectors",
        "generic setting-to-screen/widget bindings",
        "a marketplace",
        "on-device signing with GameCore's release key",
    ],
    "authority": (
        "Permissions in a package are requests. GameCore grants and enforces them after install; "
        "the studio and the bridge cannot grant, install or run a plugin."
    ),
}
