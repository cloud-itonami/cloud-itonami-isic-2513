# ADR-0001: SteamGeneratorAdvisor ⊣ Steam Generator Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2513` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2513` publishes an OSS blueprint for manufacture of
steam generators, except central heating hot water boilers --
industrial/utility-scale steam-raising pressure vessels (fire-tube
boilers, water-tube boilers, waste-heat-recovery boilers, heat-
recovery steam generators) -- **plant operations coordination**
(production-batch product-type/hydrotest-pressure/quantity/defect-rate
data logging, fabrication/welding/pressure-vessel-assembly/hydrotest-
bench-equipment maintenance scheduling, safety-concern flagging, and
outbound product shipment coordination). Like every actor in this
fleet, the blueprint alone is not an implementation: this ADR records
the governed-actor architecture that promotes it to real, tested code,
following the same langgraph StateGraph + independent Governor + Phase
0->3 rollout pattern established across the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-2815` (Manufacture of
ovens, furnaces and furnace burners): both are back-office
coordination actors for a fixed manufacturing plant with QC-tested,
discrete-unit finished-goods output and a real physical/consumer
safety dimension, and both share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`), the same two-entity verified/
registered gate structure (equipment for maintenance scheduling, batch
for shipment coordination), and the same permanent equipment-actuation
and certification-authority blocks. This build mirrors
`cloud-itonami-isic-2815`'s architecture closely but adapts the hazard
profile, equipment vocabulary, and product taxonomy to the steam-
generator/boiler plant: its finished goods are fabricated, welded,
pressure-vessel-assembled and hydrotested fire-tube boilers, water-
tube boilers, waste-heat-recovery boilers and heat-recovery steam
generators rather than ovens/furnaces/furnace burners, so its
equipment kinds are `:fabrication-line` and `:hydrotest-bench` rather
than 2815's fabrication/assembly line and thermal-test bench, and its
routine QC field is `:hydrotest-pressure-bar` (industrial steam-
generator/boiler hydrostatic pressure test, plausibility-checked
0-600 bar -- informed by real industrial steam-generator/boiler test
practice: small fire-tube boilers commonly operate at working
pressures of roughly 10-17 bar, industrial water-tube boilers used in
process/cogeneration service commonly run 20-100 bar design pressure,
utility/supercritical power boilers can reach roughly 250-300 bar
design pressure (ultra-supercritical units up to ~350 bar), and ASME
BPVC Section I hydrostatic testing is typically performed at 1.5x the
maximum allowable working pressure (MAWP)) rather than 2815's
`:thermal-test-degc` (thermal-performance test). Like 2815, shipment
quantity is tracked in finished-unit UNITS (`:units`/`:quantity-
units`/`:shipped-units`), since steam generators are likewise discrete
counted units rather than a bulk weight.

This vertical shares 2815's structural DOMAIN-SPECIFIC permanent
block, adapted to the pressure-vessel safety-certification regime:
industrial steam generators are subject to pressure-vessel safety-
certification regimes (e.g. ASME Boiler and Pressure Vessel Code
(BPVC) Section I "S" stamp certification, National Board of Boiler and
Pressure Vessel Inspectors (NBBI) registration). This actor is never
the certification authority -- any proposal (regardless of op) that
declares `:issue-certification? true` is a HARD, PERMANENT,
unconditional block (`steamgenmfg.governor/certification-authority-
blocked-violations`), the same "no phase, no human override" posture
as the equipment-actuation block.

This vertical additionally has a DOMAIN-SPECIFIC product-taxonomy
exclusion not present in 2815 or 2812: ISIC 2513's own class
definition explicitly EXCLUDES central heating hot water boilers from
this class. `steamgenmfg.registry/valid-product-types` is therefore a
closed set of four steam-generator product types
(`:fire-tube-boiler`/`:water-tube-boiler`/`:waste-heat-recovery-
boiler`/`:heat-recovery-steam-generator`) that deliberately omits any
central-heating-hot-water-boiler value -- an attempt to log one as a
production-batch product-type is HARD-held by the same `:invalid-
product-type` check that rejects any other fabricated product type
(`steamgenmfg.governor-contract-test/central-heating-hot-water-boiler-
product-type-is-held` exercises this directly).

This vertical has NO pre-existing `kotoba-lang/steamgenmfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`steamgenmfg.registry` (equipment/batch verification, shipment-
quantity recompute, product-type validation, hydrotest-pressure
plausibility validation, defect-rate plausibility validation) are re-
verified independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most directly
`cloud-itonami-isic-2815`'s `ovenfurnacemfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:steam-generator-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "steam-generator-plant-operations-
governor" --owner cloud-itonami`, zero hits before this repo was
created). The `steamgenmfg` namespace prefix is likewise grep-verified
UNIQUE fleet-wide (`gh search code "steamgenmfg" --owner
cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external steam-generator-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
steam-generator/boiler vertical has NO pre-existing capability library
to wrap. The equipment/batch-verification / shipment-quantity /
product-type / hydrotest-pressure / defect-rate validation functions
live as pure functions in `steamgenmfg.registry` and are re-verified
independently by `steamgenmfg.governor` -- the same "ground truth, not
self-report" discipline established across prior actors (most directly
`cloud-itonami-isic-2815`'s `ovenfurnacemfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of steam-generator/
boiler plant operations. It does NOT:
- Control fabrication or welding/pressure-vessel-assembly-line equipment directly
- Make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
- Actuate fabrication/welding/pressure-vessel-assembly-line equipment
- Self-issue an ASME BPVC/National Board pressure-vessel safety-certification mark (e.g. ASME Boiler and Pressure Vessel Code Section I "S" stamp certification, or National Board of Boiler and Pressure Vessel Inspectors (NBBI) registration)

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority or
the certification body's authority — it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY BOUNDARY**: steam-generator/boiler manufacturing is a
safety-critical domain (fabrication/welding/pressure-vessel-assembly/
hydrotest-bench line hazards, high-pressure and boiler-explosion
hazards, pressure-vessel safety certification, downstream explosion/
burn/consumer-safety consequence via the systems the batch's steam
generators end up installed in). Safety-concern flagging NEVER auto-
commits. All safety concerns escalate immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (equipment-safety concern, pressure-vessel-
safety/weld-integrity concern) ALWAYS escalates, never auto-commits.
This is not a "low-stakes proposal" -- it is a circuit-breaker that
must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2815`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "plant/batch record must be independently
verified/registered before any action" HARD invariant applied to the
two distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether a
batch's own recorded shipped-to-date unit quantity plus the
proposal's own claimed unit quantity would exceed the batch's own
recorded production quantity -- never taken on the advisor's
self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into twelve concrete checks
in `steamgenmfg.governor`, mirroring `cloud-itonami-isic-2815`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct fabrication/welding/pressure-vessel-assembly-line-equipment control, equipment actuation, or self-issued pressure-vessel safety certification (ASME BPVC/National Board) is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Steam-generator/boiler plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off, and no pressure-vessel safety-certification mark
can ever be self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into twelve concrete governor checks) protect against scope creep into
unauthorized equipment operation, equipment actuation, or
certification self-issuance. Safety concerns are a circuit-breaker,
not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(+) The domain-specific product-taxonomy exclusion (no central heating
hot water boilers) is enforced structurally, not just documented --
`valid-product-types` cannot express that value, and a dedicated test
exercises the exclusion directly.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and certification
issuance remain human-/institution-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, certification-body APIs)
— this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-2513`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-quantity-exceeded, equipment-actuate-
  blocked, certification-authority-blocked, already-scheduled,
  invalid-product-type, invalid-hydrotest-pressure-bar, invalid-
  defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
