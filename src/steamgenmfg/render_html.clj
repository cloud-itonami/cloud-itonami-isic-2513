(ns steamgenmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously shipped
  no demo page and no generator. This namespace drives the REAL actor
  stack (`steamgenmfg.operation` -> `steamgenmfg.governor` ->
  `steamgenmfg.store`, via `langgraph.graph/run*`) and renders the page
  from what that run actually produced. Nothing on the page is typed by
  hand except `action-gate-rows` below, which documents this actor's own
  fixed op contract (see the comment there).

  SUBJECT IDS -- what is seeded and what cannot be.
  `steamgenmfg.store/sample-data!` seeds exactly two directories:
  batches `batch-001`/`batch-002`/`batch-003` and equipment
  `fab-001`/`bench-002`. It seeds `:maintenance {}`, `:shipments {}`
  and `:safety-concerns []` EMPTY, because a maintenance window, a
  shipment and a safety concern are DRAFTS THIS ACTOR CREATES -- the
  subject of `:schedule-maintenance`/`:coordinate-shipment`/
  `:flag-safety-concern` is the id of the record being created, so it
  is structurally impossible for it to pre-exist in the seed. So:

    - every subject that names a PRE-EXISTING entity (each
      `:log-production-batch` subject) is a seeded batch id;
    - every entity REFERENCE inside a request `:value`
      (`:equipment-id`, `:batch-id`) is a seeded equipment/batch id --
      these are the ids the governor independently re-derives ground
      truth from, so this is where fabrication would actually matter;
    - draft subject ids are named after the seeded entity they
      reference (`mnt-fab-001-weld-seam`, `ship-batch-002-west`) so the
      linkage stays legible on the page.

  This scenario is authored here rather than reusing `steamgenmfg.sim`
  verbatim: sim was run first (`clojure -M:dev:run`) to confirm the real
  ledger output, then this scenario was written to cover ALL TWELVE
  governor rules plus a rejected human approval, and to keep each
  seeded batch's own last ledger fact meaningful.

  Determinism: no timestamps, no random ids, no wall-clock reads. All
  dates on the page come from seed data or from the scenario's own
  request values. Two consecutive runs are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [steamgenmfg.operation :as op]
            [steamgenmfg.store :as store]))

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a freshly seeded store through a scenario that reaches every
  disposition this actor can produce. Returns the store.

  CLEAN LIFECYCLE (batch-001 / fab-001):
    1. `:log-production-batch batch-001` -- governor-clean, high
       confidence, no physical risk: the ONE op in phase 3's `:auto`
       set, so it AUTO-COMMITS with no human in the loop.
    2. `:schedule-maintenance` on `fab-001` (verified + registered) --
       `steamgenmfg.phase` deliberately keeps this op out of every
       phase's `:auto` set, so a governor-clean proposal still
       ESCALATES; a human plant supervisor approves and it commits.
    3. `:flag-safety-concern` on `fab-001` -- `:stake
       :coordination/safety-concern` is in `governor/high-stakes`, so
       it ALWAYS escalates regardless of confidence; approved.
    4. `:coordinate-shipment` on `batch-001` (50.0 of 400.0 produced,
       100.0 already shipped) -- escalates, approved, and the commit
       moves the batch's own `:shipped-units` 100.0 -> 150.0.

  REJECTED APPROVAL:
    5. A second `batch-001` shipment the human approver REJECTS. The
       ledger records `:approval-rejected` and the SSoT is NOT touched
       -- `:shipped-units` stays at 150.0, visible on the page.

  HARD HOLDS -- one per governor rule, all twelve, none of which can
  be overridden by any phase or any human:
    :equipment-not-verified          maintenance against `bench-002`
                                     (seeded UNVERIFIED/unregistered)
    :already-scheduled               the step-2 window scheduled twice
    :equipment-actuate-blocked       maintenance on `fab-001` declaring
                                     `:actuate-equipment? true`
    :batch-not-verified              shipment against `batch-003`
                                     (seeded UNVERIFIED/unregistered)
    :shipment-quantity-exceeded      10.0 units against `batch-002`,
                                     which has already shipped 115.0 of
                                     its own logged 120.0
    :invalid-product-type            a `batch-003` patch declaring
                                     `:central-heating-hot-water-boiler`
                                     -- the product ISIC 2513 excludes
    :invalid-hydrotest-pressure-bar  a `batch-002` patch declaring
                                     999999.0 bar
    :invalid-defect-rate             a `batch-003` patch declaring 999.0%
    :certification-authority-blocked a `batch-002` patch declaring
                                     `:issue-certification? true`
                                     (self-issuing an ASME BPVC 'S'
                                     stamp -- permanently blocked)
    :not-propose-effect              a `batch-002` request whose own
                                     `:effect` is `:direct-write`
    :unknown-op + :equipment-control-blocked
                                     `:actuate-fabrication-line` against
                                     `batch-003` -- both rules fire on
                                     the one request"
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    ;; --- clean lifecycle -------------------------------------------------
    (exec! actor "clean-1"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-type :fire-tube-boiler :last-assessed "2026-07-16"}})

    (exec! actor "clean-2"
           {:op :schedule-maintenance :effect :propose :subject "mnt-fab-001-weld-seam"
            :value {:equipment-id "fab-001" :maintenance-type :weld-seam-inspection
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})
    (approve! actor "clean-2")

    (exec! actor "clean-3"
           {:op :flag-safety-concern :effect :propose :subject "concern-fab-001-weld"
            :value {:equipment-id "fab-001" :severity :moderate
                    :description "圧力容器溶接部の異常兆候、亀裂の疑い"}})
    (approve! actor "clean-3")

    (exec! actor "clean-4"
           {:op :coordinate-shipment :effect :propose :subject "ship-batch-001-north"
            :value {:batch-id "batch-001" :units 50.0
                    :destination "buyer-yard-north"}})
    (approve! actor "clean-4")

    ;; --- human approver rejects (no SSoT mutation) -----------------------
    (exec! actor "reject-1"
           {:op :coordinate-shipment :effect :propose :subject "ship-batch-001-south"
            :value {:batch-id "batch-001" :units 60.0
                    :destination "buyer-yard-south"}})
    (reject! actor "reject-1")

    ;; --- HARD holds, one per governor rule -------------------------------
    (exec! actor "hold-equipment-not-verified"
           {:op :schedule-maintenance :effect :propose :subject "mnt-bench-002-gauge"
            :value {:equipment-id "bench-002" :maintenance-type :gauge-calibration
                    :scheduled-date "2026-08-05" :actuate-equipment? false}})

    (exec! actor "hold-already-scheduled"
           {:op :schedule-maintenance :effect :propose :subject "mnt-fab-001-weld-seam"
            :value {:equipment-id "fab-001" :maintenance-type :weld-seam-inspection
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! actor "hold-actuate-blocked"
           {:op :schedule-maintenance :effect :propose :subject "mnt-fab-001-force-run"
            :value {:equipment-id "fab-001" :maintenance-type :force-run
                    :scheduled-date "2026-09-01" :actuate-equipment? true}})

    (exec! actor "hold-batch-not-verified"
           {:op :coordinate-shipment :effect :propose :subject "ship-batch-003-east"
            :value {:batch-id "batch-003" :units 50.0
                    :destination "buyer-yard-east"}})

    (exec! actor "hold-quantity-exceeded"
           {:op :coordinate-shipment :effect :propose :subject "ship-batch-002-west"
            :value {:batch-id "batch-002" :units 10.0
                    :destination "buyer-yard-west"}})

    (exec! actor "hold-invalid-product-type"
           {:op :log-production-batch :effect :propose :subject "batch-003"
            :patch {:product-type :central-heating-hot-water-boiler}})

    (exec! actor "hold-invalid-hydrotest"
           {:op :log-production-batch :effect :propose :subject "batch-002"
            :patch {:hydrotest-pressure-bar 999999.0}})

    (exec! actor "hold-invalid-defect-rate"
           {:op :log-production-batch :effect :propose :subject "batch-003"
            :patch {:defect-rate-percent 999.0}})

    (exec! actor "hold-certification-authority"
           {:op :log-production-batch :effect :propose :subject "batch-002"
            :patch {:issue-certification? true}})

    (exec! actor "hold-not-propose-effect"
           {:op :log-production-batch :effect :direct-write :subject "batch-002"
            :patch {:product-type :water-tube-boiler}})

    (exec! actor "hold-unknown-op"
           {:op :actuate-fabrication-line :effect :propose :subject "batch-003"})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- nm
  "`:basis` entries are keywords for a hold (rule names) and the
  advisor's own cited ids -- plain strings -- for a commit."
  [x]
  (if (keyword? x) (name x) (str x)))

(defn- join-names [xs] (str/join ", " (map nm xs)))

(def ^:private dash "<span class=\"muted\">&mdash;</span>")

(defn- opt
  "Render a store field that is legitimately absent (e.g. `bench-002`'s
  seeded `:last-maintenance-date nil`) without inventing a value."
  [v]
  (if (or (nil? v) (= "" v)) dash (esc v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- num-cell [v]
  (if (nil? v) dash (str "<span class=\"num\">" (esc v) "</span>")))

(defn- ground-truth-cell
  "Both flags are permanent fields on the entity's own record, and are
  exactly what `steamgenmfg.registry/equipment-ready?` /`batch-ready?`
  -- and therefore the governor -- read."
  [{:keys [verified? registered?]}]
  (if (and verified? registered?)
    "<span class=\"ok\">verified + registered</span>"
    (str "<span class=\"critical\">"
         (if verified? "verified" "UNVERIFIED") " / "
         (if registered? "registered" "unregistered")
         "</span>")))

;; The store appends exactly three fact types to the ledger:
;; `:committed` (from `operation`'s :commit node) and `:governor-hold` /
;; `:approval-rejected` (from its :hold node). `:approval-granted` and
;; `:approval-requested` are written to the in-memory :audit channel
;; ONLY and never reach the ledger, so there is deliberately no branch
;; for them here -- it would be dead code.
(defn- disposition-cell [{:keys [t basis phase-reason] :as f}]
  (cond
    (nil? f) "<span class=\"muted\">no activity</span>"

    (= :committed t) "<span class=\"ok\">committed</span>"

    (= :approval-rejected t)
    (str "<span class=\"warn\">approval rejected &middot; " (esc (join-names basis)) "</span>")

    ;; A `:governor-hold` carries the violated rules in `:basis`. The
    ;; same fact shape is also used when the ROLLOUT PHASE (not the
    ;; governor) refuses a write, in which case `:basis` is empty and
    ;; `:phase-reason` says why -- so the two are distinguished here
    ;; rather than labelling every hold "HARD".
    (and (= :governor-hold t) (seq basis))
    (str "<span class=\"critical\">HARD hold &middot; " (esc (join-names basis)) "</span>")

    (= :governor-hold t)
    (str "<span class=\"critical\">phase hold &middot; " (esc (nm (or phase-reason :unspecified))) "</span>")

    :else (str "<span class=\"muted\">" (esc (nm t)) "</span>")))

(defn- last-fact-for [ledger subject]
  (last (filter #(= subject (:subject %)) ledger)))

(defn- detail-cell [{:keys [t summary violations]}]
  (let [details (->> violations (map :detail) (remove nil?) (str/join " / "))]
    (cond
      (= :committed t) (opt summary)
      (seq details) (esc details)
      :else dash)))

(defn- tr [& cells]
  (str "        <tr>" (str/join "" (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- batch-row [ledger {:keys [id product-type model hydrotest-pressure-bar
                                 quantity-units shipped-units defect-rate-percent
                                 last-assessed] :as b}]
  (tr (code id)
      (code product-type)
      (esc model)
      (num-cell hydrotest-pressure-bar)
      (num-cell quantity-units)
      (num-cell shipped-units)
      (num-cell defect-rate-percent)
      (ground-truth-cell b)
      (opt last-assessed)
      (disposition-cell (last-fact-for ledger id))))

;; NOTE deliberately no "last ledger fact" column here. A ledger fact's
;; `:subject` is the id of the record an op acts on, and an op never
;; acts on an equipment unit directly -- `:schedule-maintenance`'s
;; subject is the maintenance window it drafts, and the unit appears
;; only as `:equipment-id` inside the request value. Keying this table
;; on `:subject` would print "no activity" against every unit forever.
;; `:last-scheduled-maintenance-date` is the real linkage: the store
;; writes it on the equipment record when a maintenance window actually
;; commits, so a held proposal leaves it empty.
(defn- equipment-row [{:keys [id kind last-maintenance-date
                              last-scheduled-maintenance-date] :as e}]
  (tr (code id)
      (code kind)
      (ground-truth-cell e)
      (opt last-maintenance-date)
      (opt last-scheduled-maintenance-date)))

(defn- maintenance-row [{:keys [id maintenance-number equipment-id maintenance-type
                                scheduled-date scheduled? actuate-equipment?]}]
  (tr (code maintenance-number)
      (code id)
      (code equipment-id)
      (code maintenance-type)
      (opt scheduled-date)
      (if scheduled?
        "<span class=\"ok\">scheduled</span>"
        "<span class=\"muted\">draft</span>")
      (if actuate-equipment?
        "<span class=\"critical\">true</span>"
        "<span class=\"ok\">false</span>")))

(defn- shipment-row [db record]
  (let [sid (get record "shipment_id")
        {:keys [shipment-number batch-id units destination]} (store/shipment db sid)]
    (tr (code (get record "record_id"))
        (code sid)
        (code batch-id)
        (num-cell units)
        (opt destination)
        (code shipment-number)
        (if (get record "immutable")
          "<span class=\"ok\">immutable</span>"
          "<span class=\"warn\">mutable</span>"))))

(defn- concern-row [{:keys [id equipment-id severity description]}]
  (tr (code id) (code equipment-id) (code severity) (opt description)))

(defn- ledger-row [{:keys [op subject actor confidence basis] :as f}]
  ;; `:t` and `:op` are always keywords, so they print with their
  ;; leading colon to match how the rest of the page names them.
  ;; `:basis` is mixed (rule keywords on a hold, cited ids -- plain
  ;; strings -- on a commit), so that column stays bare via `nm`.
  (tr (code (:t f))
      (code op)
      (esc subject)
      (opt actor)
      (num-cell confidence)
      (if (seq basis) (esc (join-names basis)) dash)
      (disposition-cell f)
      (detail-cell f)))

(def ^:private action-gate-rows
  ;; Static description of this actor's OWN fixed op contract, read off
  ;; `steamgenmfg.governor/allowed-ops`, `governor/high-stakes` and
  ;; `steamgenmfg.phase/phases`. This is documentation of code that
  ;; cannot change between runs -- not runtime telemetry -- so it is
  ;; legitimately hand-described here. Every other cell on this page is
  ;; read back from the store after a real run.
  ["        <tr><td><code>:log-production-batch</code></td><td><span class=\"ok\">phase-3 AUTO-COMMIT when governor-clean &middot; the only op in any phase's <code>:auto</code> set</span></td><td>product-type / hydrotest-pressure-bar / defect-rate each validated against a closed plausible range; a self-issued certification claim is blocked</td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"warn\">ALWAYS human approval &middot; deliberately absent from every phase's <code>:auto</code> set</span></td><td>equipment <code>:verified?</code> AND <code>:registered?</code> re-derived independently; double-scheduling refused off a dedicated <code>:scheduled?</code> fact; <code>:actuate-equipment? true</code> permanently blocked</td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; <code>:coordination/safety-concern</code> is high-stakes at any confidence</span></td><td>never gated on the referenced equipment being verified &mdash; safety reporting is not blocked on an administrative technicality</td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">ALWAYS human approval &middot; not auto-eligible at any phase</span></td><td>batch <code>:verified?</code> AND <code>:registered?</code> re-derived independently; shipped-to-date + claimed units recomputed against the batch's own logged quantity, never trusted from the proposal</td></tr>"])

(defn render
  "Renders the operator console from a store `db` that has already been
  driven through a real scenario by `run-demo!`."
  [db]
  (let [ledger (vec (store/ledger db))
        hard-holds (filter #(and (= :governor-hold (:t %)) (seq (:basis %))) ledger)]
    (str
     "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-2513 &middot; steam generator plant operations</title>\n<style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of steam generators (ISIC 2513) &mdash; Operator Console</h1>\n"
     "</header>\n"
     "<p><span class=\"badge\">read-only sample</span> <span class=\"badge\">governor-gated</span> <span class=\"badge\">maintenance &amp; shipment always human-approved</span></p>\n"
     "<p class=\"subtitle\">Generated at build time by <code>steamgenmfg.render-html</code> (<code>clojure -M:dev:render-html</code>) by running the real "
     "<code>steamgenmfg.operation</code> actor &rarr; <code>steamgenmfg.governor</code> &rarr; <code>steamgenmfg.store</code> stack over the seeded plant. "
     "Every value below was read back out of the store after that run; nothing is transcribed by hand. "
     "This run produced <span class=\"num\">" (count ledger) "</span> ledger facts, of which <span class=\"num\">"
     (count hard-holds) "</span> are HARD holds that never reached a human.</p>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">Ground truth for every shipment decision. <code>:verified?</code> and <code>:registered?</code> are permanent fields on the batch's own record &mdash; the governor re-derives them itself and never accepts the advisor's report of them.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Product type</th><th>Model</th><th>Hydrotest (bar)</th><th>Produced (units)</th><th>Shipped (units)</th><th>Defect rate (%)</th><th>Ground truth</th><th>Last assessed</th><th>Last ledger fact</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial batch-row ledger) (store/all-batches db))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Plant equipment</h2>\n"
     "    <p class=\"muted\">Fabrication / welding / pressure-vessel-assembly / hydrotest-bench units. Maintenance may only ever be <em>scheduled</em> against one of these &mdash; this actor never actuates equipment. <em>Last scheduled window</em> is written onto the unit only when a maintenance window actually commits, so a unit whose proposals were all held stays empty.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Unit</th><th>Kind</th><th>Ground truth</th><th>Last maintenance</th><th>Last scheduled window</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map equipment-row (store/all-equipment db))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Steam Generator Plant Operations Governor)</h2>\n"
     "    <p class=\"muted\">The closed allowlist of the four ops this actor may route. HARD holds cannot be overridden by any rollout phase or any human approver.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th><th>Independent re-checks</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Committed maintenance windows</h2>\n"
     "    <p class=\"muted\">Draft schedules that cleared the governor <em>and</em> a human plant supervisor. A held proposal never appears here &mdash; only the <code>:commit</code> node writes the SSoT.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Draft no.</th><th>Maintenance id</th><th>Equipment</th><th>Type</th><th>Scheduled date</th><th>State</th><th>Actuate equipment?</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map maintenance-row (store/all-maintenance db))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Committed shipment coordinations</h2>\n"
     "    <p class=\"muted\">Unsigned coordination drafts &mdash; this actor records the shipment a plant coordinator would keep; it never dispatches a real freight carrier.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Draft no.</th><th>Shipment id</th><th>Batch</th><th>Units</th><th>Destination</th><th>Shipment no.</th><th>Record</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial shipment-row db) (store/shipment-history db))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Safety concerns</h2>\n"
     "    <p class=\"muted\">Always escalated to a human at any confidence, and never blocked on whether the referenced equipment happens to be verified.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Concern id</th><th>Equipment</th><th>Severity</th><th>Description</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map concern-row (store/safety-concerns db))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log &mdash; every commit, HARD hold and rejected approval this scenario produced, in order. The ledger carries exactly three fact types: <code>:committed</code>, <code>:governor-hold</code> and <code>:approval-rejected</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Actor</th><th>Confidence</th><th>Basis</th><th>Disposition</th><th>Detail</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-2513 &mdash; industrial steam-generator / boiler plant-operations coordination actor. "
     "ISIC 2513 covers manufacture of steam generators <em>except</em> central heating hot water boilers.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (io/make-parents out)
    (spit out html)
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count (store/all-maintenance db)) " maintenance windows, "
                  (count (store/shipment-history db)) " shipment drafts, "
                  (count (store/safety-concerns db)) " safety concerns)"))))
