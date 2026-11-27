# Stage mechanic translator context

This NPC is a Stage demonstration of clickable dialogue choices. It welcomes the player, offers an explanation or goodbye, and finishes without changing game progress. Use a friendly and factual tone; do not imply that this test awards progress or items.

Keep the `welcome`, `about.choice`, `leave.choice`, `about` and `bye` keys stable. The explanation continues to the goodbye message, then the end node. The player may speak to the NPC again after the conversation ends. English and German are currently declared supported.

The host speaker role is `guide`; there are no authorized actions or interpolation arguments. See the parent authoring guide for validation and literal brace escaping. This project has its own text catalogue because its short keys overlap with the Lobby Guide's keys.
