# HandShaker In-Game Test Plan

This checklist is based on the current project structure and commands in this repo.

## 1) Prerequisites

- Java 21 installed (project toolchain is Java 21).
- One server you want to test (`Paper`, `Fabric`, or `NeoForge`).
- At least 2 test accounts if possible:
  - Java account with HandShaker client mod.
  - Java account without HandShaker client mod.
- Optional for Bedrock tests:
  - Bedrock client.
  - `Geyser` + `Floodgate` installed on server side (Bedrock detection path).

## 2) Build Artifacts

From repo root:

```powershell
.\gradlew build
```

Expected:
- Build succeeds.
- Test jars are collected in `build/output`.

## 3) Install Server + Mod/Plugin

Pick one server target:

- **Paper**: place `hand-shaker-paper-*.jar` into `plugins/`.
- **Fabric server**: place `hand-shaker-fabric-*.jar` into `mods/`.
- **NeoForge server**: place `hand-shaker-neoforge-*.jar` into `mods/`.

For Java client-side handshake tests, put matching HandShaker client jar in test client `mods/`.

## 4) First Boot Validation

1. Start server.
2. Confirm HandShaker enabled in logs.
3. Verify config folder exists and default files were generated.

Expected config files:
- `config.yml`
- `mods-required.yml`
- `mods-blacklisted.yml`
- `mods-whitelisted.yml`
- `mods-actions.yml`
- `mods-ignored.yml`

## 5) Command Surface Smoke Test

> Use command root for your platform:
> - Paper modern: `/handshakerv3` (aliases: `/hsv3`, `/hs-v3`)
> - Paper legacy + Fabric + NeoForge: `/handshaker`

Run and verify each returns valid output (not usage errors):

1. Help/root:
   - `/handshakerv3`
   - or `/handshaker`
2. Reload:
   - `/handshakerv3 reload`
   - or `/handshaker reload`
3. Info pages:
   - `/... info configured_mods`
   - `/... info all_mods`
4. Config read/mutate:
   - `/... config`
   - `/... config behavior vanilla`
   - `/... config behavior strict`
5. Mode toggles:
   - `/... mode mods_required off`
   - `/... mode mods_required on`
6. Ignore list:
   - `/... manage ignore list`

## 6) Join Flow Tests (Core)

### A) Strict mode rejects non-HandShaker Java client

1. Set strict mode:
   - `/... config behavior strict`
2. Join with Java client **without** HandShaker mod.

Expected:
- Player is denied with `messages.no-handshake`.

### B) Vanilla mode allows non-HandShaker Java client

1. Set vanilla mode:
   - `/... config behavior vanilla`
2. Join with Java client **without** HandShaker mod.

Expected:
- Join succeeds.

### C) HandShaker client can join and is visible to admin tools

1. Re-enable strict mode:
   - `/... config behavior strict`
2. Join with Java client **with** HandShaker mod.
3. Run:
   - `/... manage player <playerName>`
   - `/... info player <playerName>`

Expected:
- Player is online.
- Mod list/history is displayed.

## 7) Rule Enforcement Tests

Use a known mod id present on your test client (example: `jade`, `sodium`, etc).

### A) Blacklisted mod action

1. Configure:
   - `/... manage add <modId> blacklisted kick`
2. Rejoin with that mod installed.

Expected:
- Player is kicked with blacklisted message/action.

### B) Required mod missing

1. Configure requirement for a mod NOT installed on test client:
   - `/... manage add <requiredModId> required`
2. Rejoin without that mod.

Expected:
- Player is denied for missing required mod(s).

### C) Allowed/optional custom action execution

1. Ensure `test_action` exists in actions config (default sample does).
2. Configure:
   - `/... manage add <modId> allowed test_action`
3. Rejoin with `<modId>` installed.

Expected:
- Player is allowed.
- Custom action commands run (message/diamond/say as configured).

### D) Remove rule cleanup

1. Remove test entries:
   - `/... manage remove <modId>`
   - `/... manage remove <requiredModId>`
2. Rejoin.

Expected:
- No test-rule enforcement remains.

## 8) Integrity + Hash Tests

### A) Integrity mode toggle

1. Set signed:
   - `/... config integrity signed`
2. Rejoin with official client build.
3. Set dev:
   - `/... config integrity dev`
4. Rejoin.

Expected:
- Signed mode enforces signature checks.
- Dev mode allows development/unsigned handshake path.

### B) Required modpack hash workflow

1. Join with a known-good client.
2. Set hash from current client:
   - `/... config required_modpack_hash current`
3. Rejoin with same client.
4. Rejoin with changed mod set.

Expected:
- Same modpack passes.
- Different modpack is denied (`modpack-hash-mismatch`).

## 9) Bedrock Tests (Geyser/Floodgate)

> Bedrock support is relevant for Paper/Fabric server side in this project.

### A) Bedrock blocked

1. Set:
   - `/... config allow_bedrock false`
2. Join from Bedrock via Geyser/Floodgate.

Expected:
- Bedrock player is denied with `messages.bedrock`.

### B) Bedrock allowed

1. Set:
   - `/... config allow_bedrock true`
2. Join from Bedrock via Geyser/Floodgate.

Expected:
- Bedrock player is allowed to connect.

## 10) Regression + Persistence

1. Run several config/rule changes.
2. Execute `/... reload`.
3. Restart server.
4. Re-test one blacklisted and one required scenario.

Expected:
- Settings persist across reload/restart.
- Enforcement behavior remains consistent.

## 11) Suggested Quick Pass/Fail Template

For each scenario, capture:

- Server type/version:
- Client type (Java/Bedrock):
- Commands executed:
- Expected result:
- Actual result:
- Logs/errors:
- Status: PASS / FAIL

---

If you want, expand this into a matrix per platform (`Paper`, `Fabric`, `NeoForge`) with checkboxes and a separate section for `1.21.10` vs `1.21.11`.
