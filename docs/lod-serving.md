# Permission-gated Stage LOD serving

Set `GROUNDS_LOD_SERVING_ENABLED=true` on the ordinary Stage Lobby. Keep
`GROUNDS_LOD_PILOT_ENABLED` off. This mode has no UUID list or 30-minute serving timer.
The API owns durable shared byte/job limits; reaching a limit stops optional LOD delivery.

Configuration:

| Variable | Value |
| --- | --- |
| `GROUNDS_LOD_SERVING_GENERATOR_SHA256` | Exact generator identity from the configured Maps worker |
| `GROUNDS_LOD_SERVING_CAPABILITY` | Stable server capability, supplied by a Kubernetes Secret reference |
| `GROUNDS_LOD_SERVING_CAPABILITY_FILE` | Alternative absolute read-only capability file; choose exactly one credential source |
| `GROUNDS_LOD_SERVING_SERVICE_ORIGIN` | HTTPS Maps API origin; defaults to `https://api.grounds.gg` |
| `GROUNDS_LOD_SERVING_CACHE_DIR` | Absolute writable bounded cache; defaults under JVM temporary storage |

Only the actually loaded published, prelit Stage `lobby/mainlobby` can bind. Its verified canonical
and transport digests and the runtime registry are passed to authenticated discovery. The host
never substitutes an older artifact for a newer map version, exposes the capability or contacts R2.
Installation makes no HTTP request; an entitled compatible client starts the lazy shared load.
Background map processing prepares missing LOD independently of joins.

The actual network UUID and current Permissions snapshot must grant `grounds.lod.receive` before
source admission and again before serialization. An active global `*` grant covers this node
under normal scope/deny precedence. A role name alone grants nothing. Existing admitted sessions
support live revoke/regrant. Initially denied clients retain no negotiation state and must send
fresh compatible declarations or reconnect after their first grant. Permission snapshots load at
pre-login before normal LSS negotiation. Previously cached Voxy terrain remains on the client.

The initial local cap is 64 entitled sessions. Initially denied clients cannot consume those slots.
Disconnects discard local state and late credits without refunding the shared database budget.
Transient source/discovery failures use at most 22 demand-driven attempts with backoff capped at
60 seconds; reconnects do not reset this envelope. Permanent quota/auth failures stay closed.
Normal maps, NPC scenes and readiness remain independent of optional delivery failure.

Metrics use the existing `grounds.lod.delivery.*` meters. Serialized application bodies do not
claim confirmed socket or proxy bytes. This serves compatible Voxy/LSS clients; native Distant
Horizons serving is separate.
