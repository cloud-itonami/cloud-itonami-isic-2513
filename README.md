# cloud-itonami-isic-2513: Manufacture of steam generators, except central heating hot water boilers

Open Business Blueprint for **ISIC 2513**: manufacture of steam generators, except central heating hot water boilers — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **industrial steam-generator/boiler plant operations**: production-batch data logging (product-type/hydrotest-pressure/quantity/defect-rate), fabrication/welding/pressure-vessel-assembly/hydrotest-bench-equipment maintenance scheduling, safety-concern flagging, and outbound product shipment coordination.

This repository designs a forkable OSS business for steam-generator/
boiler-plant operations: run by a qualified operator so a plant keeps
its own operating records instead of renting a closed SaaS.

## Scope: plant operations coordination, not fabrication-line control

ISIC 2513 covers the **manufacturing plant** that fabricates, welds, assembles and hydrotests finished fire-tube boilers, water-tube boilers, waste-heat-recovery boilers and heat-recovery steam generators — industrial/utility-scale steam-raising pressure vessels — **excluding central heating hot water boilers**, which this class explicitly excludes (a household central-heating hot-water boiler is not a product this actor's product-type registry will ever accept). This actor coordinates the back-office record keeping around that plant — it never touches the fabrication/welding/pressure-vessel-assembly-line equipment directly, and it is never a pressure-vessel-safety-certification authority (e.g. ASME Boiler and Pressure Vessel Code (BPVC) Section I "S" stamp certification, or National Board of Boiler and Pressure Vessel Inspectors (NBBI) registration).

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — fabrication/welding/pressure-vessel-assembly/hydrotest batch, output-quality data logging (administrative, not an operational decision)
- `:schedule-maintenance` — fabrication/welding/pressure-vessel-assembly/hydrotest-bench-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a pressure-vessel-safety/weld-integrity concern (always escalates)
- `:coordinate-shipment` — outbound product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(fabrication/welding/pressure-vessel-assembly/hydrotest-bench line
equipment, high-pressure and boiler-explosion hazards, pressure-vessel
safety certification, downstream explosion/burn/consumer-safety
consequence via the systems the batch's steam generators end up
installed in):

- Does NOT control fabrication or welding/assembly-line equipment directly
- Does NOT make plant-safety or certification decisions (that's the plant supervisor's / certification body's exclusive human/institutional authority)
- Does NOT actuate fabrication/welding/pressure-vessel-assembly-line equipment (human plant supervisor decides)
- Does NOT self-issue an ASME BPVC/National Board pressure-vessel safety-certification mark (e.g. ASME Boiler and Pressure Vessel Code Section I "S" stamp certification, or National Board of Boiler and Pressure Vessel Inspectors (NBBI) registration — the accredited certification body's exclusive authority — a PERMANENT, unconditional block)
- ONLY proposes/coordinates operations back-office; all actuation and certification requires explicit human/institutional authority
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`steamgenmfg.operation/build`, a langgraph-clj StateGraph):
1. **`steamgenmfg.advisor`** (sealed intelligence node, `SteamGeneratorAdvisor`): proposes decisions only, never commits
2. **`steamgenmfg.governor`** (independent, `Steam Generator Plant Operations Governor`): validates against domain rules, re-derived from `steamgenmfg.registry`'s pure functions and `steamgenmfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct fabrication/welding/pressure-vessel-assembly-line-equipment control)
     - Directly actuating fabrication/welding/pressure-vessel-assembly-line equipment (`:actuate-equipment? true`) is a PERMANENT, unconditional block
     - Self-issuing an ASME BPVC/National Board pressure-vessel safety-certification mark (`:issue-certification? true`, any op) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped quantity past its own logged production quantity (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a production-batch patch (including central heating hot water boilers, which this class explicitly excludes)
     - No physically implausible `:hydrotest-pressure-bar` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`steamgenmfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`steamgenmfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
