(ns steamgenmfg.render-html
  "Build-time HTML renderer for the operator console.

  Drives the REAL SteamGeneratorOperationActor (`steamgenmfg.operation/
  build` -> a compiled langgraph-clj StateGraph) over the REAL seeded
  store (`steamgenmfg.store/sample-data!`), through the REAL Steam
  Generator Plant Operations Governor (`steamgenmfg.governor/check`) and
  the REAL rollout phase gate (`steamgenmfg.phase/gate`), and renders
  whatever those produced. Nothing on the page is written by hand:

    - every table row is read back out of the store after the run
      (`store/ledger`, `store/all-batches`, `store/all-equipment`,
      `store/all-maintenance`, `store/shipment`,
      `store/safety-concerns`, `store/maintenance-history`,
      `store/shipment-history`),
    - every HARD-hold rule name and every violation detail string is
      the governor's own `:violations` entry off the ledger fact --
      never a literal in this namespace,
    - the phase gate table is derived from `steamgenmfg.phase/phases`,
      and the governor configuration / ground-truth bound tables from
      `steamgenmfg.governor` and `steamgenmfg.registry` public vars.

  Subject provenance (the demo may not invent subjects): every batch and
  equipment id driven below is either seeded by `store/sample-data!`
  (`batch-001` `batch-002` `batch-003` `fab-001` `bench-002`) or created
  by an intake/registration op inside this demo itself -- `batch-004` is
  created by the `t01` `:log-production-batch` commit, and every
  `mnt-*` / `ship-*` / `concern-*` subject is the draft record that its
  own op registers via `steamgenmfg.registry`.

  Fields rendered are only fields the domain model actually carries. In
  particular `:approved-by` is NOT rendered on a committed shipment /
  maintenance record: `steamgenmfg.operation`'s `:request-approval` node
  puts the approver on the record's `:payload`, while
  `store/commit-record!` persists `:value` -- so the approver is shown
  from the run timeline (where it is real), not from the stored record
  (where it does not exist).

  Deterministic: no clock, no randomness, no network. Re-running writes
  a byte-identical file.

  Run: `clojure -M:dev:render-html [out-file]`
  (default out-file `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [steamgenmfg.governor :as governor]
            [steamgenmfg.operation :as op]
            [steamgenmfg.phase :as phase]
            [steamgenmfg.registry :as registry]
            [steamgenmfg.store :as store]))

;; ----------------------------- the run -----------------------------

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase phase/default-phase})

(def ^:private scenarios
  "One entry = one coordination request driven through the real actor.
  `:approval`, when present, is the human decision handed back to the
  paused graph (`interrupt-before #{:request-approval}`)."
  [{:tid "t01"
    :exercises "Intake of a NEW production batch. Governor-clean, and :log-production-batch is the one op in phase 3's :auto set -> auto-commit. batch-004 exists for the rest of this page only because this op created it."
    :request {:op :log-production-batch :effect :propose :subject "batch-004"
              :patch {:product-type :waste-heat-recovery-boiler
                      :model "WH-R80"
                      :hydrotest-pressure-bar 45.0
                      :quantity-units 60.0
                      :defect-rate-percent 1.2
                      :last-assessed "2026-07-20"}}}

   {:tid "t02"
    :exercises "Maintenance window against a verified + registered fabrication line. Never auto-eligible at any phase -> escalates; the human plant supervisor approves."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "fab-001"
                      :maintenance-type :weld-seam-inspection
                      :scheduled-date "2026-08-01"
                      :actuate-equipment? false}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t03"
    :exercises "Safety concern. Always high-stakes, so the governor escalates regardless of confidence; the human approves."
    :request {:op :flag-safety-concern :effect :propose :subject "concern-1"
              :value {:equipment-id "fab-001" :severity :moderate
                      :description "圧力容器溶接部の異常兆候、亀裂の疑い"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t04"
    :exercises "Shipment against a verified + registered batch with headroom. Escalates; the human shipping approver approves and the batch's shipped-units advances."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-1"
              :value {:batch-id "batch-001" :units 50.0
                      :destination "buyer-yard-north"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t05"
    :exercises "Governor-clean shipment the human VETOES. Distinct from a HARD hold: the governor cleared it, a person did not."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-2"
              :value {:batch-id "batch-002" :units 4.0
                      :destination "buyer-yard-west"}}
    :approval {:status :rejected :by "coord-1"}}

   {:tid "t06"
    :exercises "Shipment against batch-004 -- the batch t01 just created, which carries no verified?/registered? ground truth. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-3"
              :value {:batch-id "batch-004" :units 10.0
                      :destination "buyer-yard-north"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t07"
    :exercises "Shipment whose claimed units would push batch-002 past its own recorded production quantity. The governor recomputes from the batch's own fields. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-4"
              :value {:batch-id "batch-002" :units 10.0
                      :destination "buyer-yard-east"}}}

   {:tid "t08"
    :exercises "Maintenance against the seeded hydrotest bench, which is neither inspected nor on file. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-2"
              :value {:equipment-id "bench-002"
                      :maintenance-type :gauge-calibration
                      :scheduled-date "2026-08-05"
                      :actuate-equipment? false}}}

   {:tid "t09"
    :exercises "A maintenance proposal that tries to ACTUATE the fabrication line rather than draft a window. Permanent scope boundary -- never reaches a human. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-3"
              :value {:equipment-id "fab-001" :maintenance-type :force-run
                      :scheduled-date "2026-09-01"
                      :actuate-equipment? true}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t10"
    :exercises "The SAME maintenance window as t02, scheduled twice. Guarded off a dedicated :scheduled? fact, never a :status value. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "fab-001"
                      :maintenance-type :weld-seam-inspection
                      :scheduled-date "2026-08-01"
                      :actuate-equipment? false}}}

   {:tid "t11"
    :exercises "A batch patch declaring a central-heating hot-water boiler -- the product ISIC 2513 explicitly excludes. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:product-type :central-heating-hot-water-boiler}}}

   {:tid "t12"
    :exercises "A batch patch with a hydrotest reading far outside any physically plausible pressure-vessel test. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:hydrotest-pressure-bar 4200.0}}}

   {:tid "t13"
    :exercises "A batch patch claiming a defect rate above 100%. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:defect-rate-percent 480.0}}}

   {:tid "t14"
    :exercises "A patch trying to self-issue an ASME BPVC 'S' stamp / NBBI registration. Authority this actor never holds -- permanent. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:issue-certification? true}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t15"
    :exercises "A mis-wired caller whose own request :effect is not :propose -- checked before anything else. HARD hold."
    :request {:op :log-production-batch :effect :direct-write :subject "batch-001"
              :patch {:product-type :fire-tube-boiler}}}

   {:tid "t16"
    :exercises "An op outside the closed allowlist. Both the op allowlist and the proposal-effect allowlist reject it. HARD hold."
    :request {:op :actuate-fabrication-line :effect :propose :subject "batch-001"}}])

(defn- drive!
  "Runs one scenario through the real compiled graph and returns the
  scenario enriched with what the graph actually did."
  [actor {:keys [tid request approval] :as scenario}]
  (let [r1 (g/run* actor {:request request :context coordinator} {:thread-id tid})
        paused? (= :interrupted (:status r1))
        r2 (when (and approval paused?)
             (g/run* actor {:approval approval} {:thread-id tid :resume? true}))
        final (:state (or r2 r1))
        audit (:audit final [])]
    (assoc scenario
           :verdict (:verdict final)
           :paused? paused?
           :escalation (first (filter #(= :approval-requested (:t %)) audit))
           :human (when r2 (:status approval))
           :disposition (:disposition final))))

(defn run-demo!
  "Seeds a MemStore, builds the real actor, drives every scenario.
  Returns {:db store :runs [..]}."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]
    {:db db :runs (mapv #(drive! actor %) scenarios)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- fmt
  "Render a stored value, or an em dash when the domain model has no
  value for that field on that record."
  [v]
  (if (nil? v) "—" (esc v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- flag [v]
  (if (true? v)
    "<span class=\"ok\">true</span>"
    (str "<span class=\"no\">" (if (nil? v) "—" (esc v)) "</span>")))

(defn- codes
  "Render a SEQUENCE of keywords in the order the code produced it --
  used for `:basis`, whose order is the governor's own evaluation
  order."
  [coll]
  (str/join " " (map code coll)))

(defn- kw-codes
  "Render a SET of keywords. Sorted, because a set has no order and an
  unsorted render would make the output non-deterministic."
  [coll]
  (str/join " " (map code (sort-by str coll))))

(defn- tr [& cells] (str "<tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "<table><thead><tr>"
       (apply str (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead><tbody>\n"
       (str/join "\n" rows)
       "\n</tbody></table>"))

(defn- card [title note body]
  (str "<section class=\"card\"><h2>" (esc title) "</h2>"
       (when note (str "<p class=\"muted\">" note "</p>"))
       body "</section>"))

;; ----------------------------- sections -----------------------------

(defn- ledger-of [db] (vec (store/ledger db)))

(defn- holds [db]
  (filterv #(= :governor-hold (:t %)) (ledger-of db)))

(defn- stat [label value]
  (str "<div class=\"stat\"><span class=\"n\">" (esc value) "</span>"
       "<span class=\"l\">" (esc label) "</span></div>"))

(defn- summary-section [db runs]
  (let [led (ledger-of db)
        n (fn [t] (count (filter #(= t (:t %)) led)))]
    (card "Run summary"
          (str "Every number below is a count over the actor's own append-only ledger "
               "after driving " (count runs) " requests through "
               (code "steamgenmfg.operation/build") ".")
          (str "<div class=\"stats\">"
               (stat "requests driven" (count runs))
               (stat "ledger facts" (count led))
               (stat "commits" (n :committed))
               (stat "governor HARD holds" (n :governor-hold))
               (stat "human approvals" (count (filter #(= :approved (:human %)) runs)))
               (stat "human rejections" (count (filter #(= :rejected (:human %)) runs)))
               "</div>"
               "<p class=\"muted\">Note: <code>:approval-granted</code> is emitted to the graph's "
               "in-memory <code>:audit</code> channel only — <code>steamgenmfg.operation</code> never "
               "appends it to the store ledger, so it is not a fact this page counts. An approved "
               "request is visible as the <code>:committed</code> fact it produced.</p>"))))

(defn- verdict-cell [{:keys [verdict]}]
  (cond
    (nil? verdict) "<span class=\"muted\">—</span>"
    (:hard? verdict)
    (str "<span class=\"bad\">HARD</span> "
         (str/join " " (map code (map :rule (:violations verdict)))))
    (:escalate? verdict)
    (str "<span class=\"warn\">escalate</span>"
         (when (:high-stakes? verdict) " <span class=\"muted\">high-stakes</span>"))
    :else (str "<span class=\"ok\">clean</span> <span class=\"muted\">conf "
               (esc (:confidence verdict)) "</span>")))

(defn- human-cell [{:keys [approval human paused?]}]
  (cond
    (= :approved human) "<span class=\"ok\">approved</span>"
    (= :rejected human) "<span class=\"bad\">rejected</span>"
    (and approval (not paused?))
    "<span class=\"muted\">never offered (no interrupt)</span>"
    :else "<span class=\"muted\">—</span>"))

(defn- disposition-cell [{:keys [disposition]}]
  (case disposition
    :commit "<span class=\"ok\">commit</span>"
    :hold "<span class=\"bad\">hold</span>"
    :escalate "<span class=\"warn\">escalate</span>"
    (str "<span class=\"muted\">" (fmt disposition) "</span>")))

(defn- timeline-section [runs]
  (card "Request timeline"
        (str "One row = one <code>langgraph.graph/run*</code> over the compiled actor. "
             "The governor column is the verdict map the governor itself returned; the human "
             "column is the decision handed back to the graph while it was paused at "
             (code ":request-approval") ".")
        (table ["Thread" "Op" "Subject" "Governor" "Human" "Final" "What this exercises"]
               (for [{:keys [tid request escalation exercises] :as r} runs]
                 (tr (code tid)
                     (code (:op request))
                     (code (:subject request))
                     (verdict-cell r)
                     (human-cell r)
                     (str (disposition-cell r)
                          (when-let [reason (:reason escalation)]
                            (str " <span class=\"muted\">after escalation "
                                 (code reason) "</span>")))
                     (str "<span class=\"muted\">" (esc exercises) "</span>"))))))

(defn- holds-section [db]
  (let [hs (holds db)]
    (card "Governor HARD holds"
          (str "Each row is a <code>:governor-hold</code> fact on the append-only ledger. The rule "
               "name and the detail text are the governor's own "
               (code ":violations") " entries — this page holds no rule text of its own.")
          (table ["Rule" "Op" "Subject" "Confidence" "Governor's own detail"]
                 (for [h hs
                       v (:violations h)]
                   (tr (str "<span class=\"bad\">" (esc (:rule v)) "</span>")
                       (code (:op h))
                       (code (:subject h))
                       (fmt (:confidence h))
                       (esc (:detail v))))))))

(defn- rejections-section [db]
  (let [rs (filterv #(= :approval-rejected (:t %)) (ledger-of db))]
    (when (seq rs)
      (card "Human rejections"
            (str "A governor-clean proposal a person declined. Written to the ledger by the same "
                 (code ":hold") " node, but with basis " (code ":approver-rejected") " — not a "
                 "compliance violation.")
            (table ["Op" "Subject" "Basis" "Confidence"]
                   (for [r rs]
                     (tr (code (:op r)) (code (:subject r))
                         (codes (:basis r)) (fmt (:confidence r)))))))))

(defn- phase-section []
  (let [ph phase/default-phase
        {:keys [label writes auto]} (get phase/phases ph)]
    (card (str "Rollout phase gate — phase " ph " (" label ")")
          (str "Derived from " (code "steamgenmfg.phase/phases") ". A governor HOLD always stays a "
               "HOLD; an op that may write but is not auto-eligible escalates to a human even when "
               "the governor is clean.")
          (table ["Op" "May write in this phase" "May auto-commit when governor-clean"]
                 (for [o (sort-by str governor/allowed-ops)]
                   (tr (code o)
                       (if (contains? writes o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"bad\">no — HOLD (:phase-disabled)</span>")
                       (if (contains? auto o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"warn\">no — always human approval</span>")))))))

(defn- governor-section []
  (card "Governor configuration"
        (str "Read straight off the public vars of " (code "steamgenmfg.governor") ".")
        (table ["Setting" "Value"]
               [(tr "confidence floor" (code governor/confidence-floor))
                (tr "allowed ops" (kw-codes governor/allowed-ops))
                (tr "allowed proposal effects" (kw-codes governor/allowed-proposal-effects))
                (tr "always-human stakes" (kw-codes governor/high-stakes))])))

(defn- bounds-section []
  (card "Independent ground-truth bounds"
        (str "The values " (code "steamgenmfg.registry") " uses to re-derive the truth itself, "
             "rather than believing the advisor's rationale.")
        (table ["Bound" "Value"]
               [(tr "valid product types" (kw-codes registry/valid-product-types))
                (tr "hydrotest pressure (bar)"
                    (str (code registry/hydrotest-pressure-bar-min) " … "
                         (code registry/hydrotest-pressure-bar-max)))
                (tr "defect rate (%)"
                    (str (code registry/defect-rate-min-percent) " … "
                         (code registry/defect-rate-max-percent)))])))

(defn- last-fact-for [led subject]
  (last (filter #(= subject (:subject %)) led)))

(defn- subject-status [led subject]
  (let [f (last-fact-for led subject)]
    (cond
      (nil? f) "<span class=\"muted\">no ledger activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"bad\">rejected by approver</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"bad\">HARD hold</span> " (codes (:basis f)))
      :else (str "<span class=\"muted\">" (esc (:t f)) "</span>"))))

(defn- remaining [b]
  (let [q (:quantity-units b) s (:shipped-units b 0.0)]
    (if (and (number? q) (number? s)) (- (double q) (double s)) nil)))

(defn- batches-section [db]
  (let [led (ledger-of db)]
    (card "Production batches"
          (str "Read back from " (code "steamgenmfg.store/all-batches") " after the run. "
               (code "batch-001") " " (code "batch-002") " " (code "batch-003")
               " are seeded by " (code "store/sample-data!") "; " (code "batch-004")
               " exists because the <code>t01</code> intake op committed it. "
               "A field the record does not carry shows as —; "
               (code "batch-004") " has no " (code ":shipped-units") " of its own yet, so "
               "<em>Remaining</em> uses the same <code>0.0</code> default "
               (code "steamgenmfg.registry") " itself applies when it recomputes headroom.")
          (table ["Batch" "Product type" "Model" "Hydrotest (bar)" "Quantity (units)"
                  "Shipped (units)" "Remaining" "Defect rate (%)" "verified?" "registered?"
                  "ready?" "Last assessed" "Ledger status"]
                 (for [b (store/all-batches db)]
                   (tr (code (:id b)) (fmt (:product-type b)) (fmt (:model b))
                       (fmt (:hydrotest-pressure-bar b)) (fmt (:quantity-units b))
                       (fmt (:shipped-units b)) (fmt (remaining b))
                       (fmt (:defect-rate-percent b))
                       (flag (:verified? b)) (flag (:registered? b))
                       (if (registry/batch-ready? b)
                         "<span class=\"ok\">yes</span>" "<span class=\"bad\">no</span>")
                       (fmt (:last-assessed b))
                       (subject-status led (:id b))))))))

(defn- equipment-section [db]
  (card "Fabrication / hydrotest equipment"
        (str "Read back from " (code "steamgenmfg.store/all-equipment") ". Equipment ids are never "
             "a request <code>:subject</code> in this domain (a maintenance draft id is), so no "
             "ledger-status column is shown for them — "
             (code ":last-scheduled-maintenance-date") " is the field the commit path actually "
             "writes onto an equipment record.")
        (table ["Unit" "Kind" "verified?" "registered?" "ready?" "Last maintenance"
                "Last scheduled maintenance" "Maintenance drafts on file"]
               (for [e (store/all-equipment db)]
                 (tr (code (:id e)) (fmt (:kind e))
                     (flag (:verified? e)) (flag (:registered? e))
                     (if (registry/equipment-ready? e)
                       "<span class=\"ok\">yes</span>" "<span class=\"bad\">no</span>")
                     (fmt (:last-maintenance-date e))
                     (fmt (:last-scheduled-maintenance-date e))
                     (esc (count (filter #(= (:id e) (:equipment-id %))
                                         (store/all-maintenance db)))))))))

(defn- maintenance-section [db]
  (let [ms (store/all-maintenance db)]
    (card "Maintenance schedule drafts"
          (str "Committed drafts from " (code "steamgenmfg.store/all-maintenance") ". The "
               "maintenance number is minted by " (code "steamgenmfg.registry/register-maintenance")
               " at commit time. Nothing here actuates any equipment.")
          (if (seq ms)
            (table ["Draft" "Equipment" "Type" "Scheduled date" "actuate-equipment?"
                    "scheduled?" "Maintenance number"]
                   (for [m ms]
                     (tr (code (:id m)) (code (:equipment-id m)) (fmt (:maintenance-type m))
                         (fmt (:scheduled-date m)) (flag (:actuate-equipment? m))
                         (flag (:scheduled? m)) (fmt (:maintenance-number m)))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- shipments-section [db]
  (let [hist (store/shipment-history db)
        ships (keep #(store/shipment db (get % "shipment_id")) hist)]
    (card "Shipment coordination drafts"
          (str "Committed drafts, joined from " (code "steamgenmfg.store/shipment-history")
               " back to each stored shipment record. This is a draft a coordinator keeps — it "
               "dispatches no freight carrier.")
          (if (seq ships)
            (table ["Draft" "Batch" "Units" "Destination" "Shipment number"]
                   (for [s ships]
                     (tr (code (:id s)) (code (:batch-id s)) (fmt (:units s))
                         (fmt (:destination s)) (fmt (:shipment-number s)))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- concerns-section [db]
  (let [cs (store/safety-concerns db)]
    (card "Safety concerns"
          (str "The append-only safety-concern log (" (code "steamgenmfg.store/safety-concerns")
               "). A concern may be raised against any equipment, verified or not — it is never "
               "blocked on an administrative technicality.")
          (if (seq cs)
            (table ["Concern" "Equipment" "Severity" "Description"]
                   (for [c cs]
                     (tr (code (:id c)) (code (:equipment-id c)) (fmt (:severity c))
                         (fmt (:description c)))))
            "<p class=\"muted\">none flagged in this run</p>"))))

(defn- ledger-section [db]
  (card "Audit ledger (append-only)"
        (str "The full ledger, in append order, exactly as "
             (code "steamgenmfg.store/ledger") " returns it.")
        (table ["#" "Fact" "Op" "Subject" "Actor" "Disposition" "Basis"]
               (map-indexed
                (fn [i f]
                  (tr (esc (inc i))
                      (let [cls (case (:t f)
                                  :committed "ok"
                                  :governor-hold "bad"
                                  :approval-rejected "bad"
                                  "muted")]
                        (str "<span class=\"" cls "\">" (esc (:t f)) "</span>"))
                      (code (:op f)) (code (:subject f)) (fmt (:actor f))
                      (fmt (:disposition f)) (codes (:basis f))))
                (ledger-of db)))))

;; ----------------------------- page -----------------------------

(def ^:private page-css
  (str "*{box-sizing:border-box}"
       "body{margin:0;font:14px/1.6 -apple-system,BlinkMacSystemFont,'Helvetica Neue','Noto Sans JP',sans-serif;"
       "color:#1a1a1a;background:#f2f2f2}"
       ".bar{background:#00118f;color:#fff;padding:1.4rem 1.6rem}"
       ".bar h1{margin:0 0 .3rem;font-size:1.15rem;font-weight:700}"
       ".bar p{margin:0;font-size:.82rem;opacity:.85}"
       ".chip{display:inline-block;background:#fff;color:#00118f;border-radius:999px;"
       "padding:.1rem .6rem;font-size:.74rem;font-weight:700;margin-right:.4rem}"
       "main{max-width:1180px;margin:1.4rem auto 3rem;padding:0 1rem}"
       ".card{background:#fff;border:1px solid #e6e6e6;border-radius:8px;padding:1.1rem 1.3rem;"
       "margin-bottom:1.1rem}"
       ".card h2{margin:0 0 .4rem;font-size:1rem;font-weight:700}"
       ".muted{color:#767676;font-size:.82rem;margin:.2rem 0 .7rem}"
       "table{border-collapse:collapse;width:100%;font-size:.81rem}"
       "th,td{text-align:left;padding:.42rem .5rem;border-bottom:1px solid #e6e6e6;"
       "vertical-align:top}"
       "th{font-weight:700;color:#767676;font-size:.76rem;white-space:nowrap}"
       "tbody tr:last-child td{border-bottom:none}"
       "code{background:#f2f2f2;border-radius:3px;padding:.05rem .28rem;font-size:.76rem;"
       "font-family:ui-monospace,SFMono-Regular,Menlo,monospace}"
       ".ok{color:#115a36;font-weight:600}"
       ".warn{color:#8b3200;font-weight:600}"
       ".bad{color:#a90000;font-weight:700}"
       ".no{color:#767676}"
       ".stats{display:flex;flex-wrap:wrap;gap:.7rem}"
       ".stat{border:1px solid #e6e6e6;border-radius:6px;padding:.6rem .9rem;min-width:8.5rem}"
       ".stat .n{display:block;font-size:1.5rem;font-weight:700;line-height:1.1}"
       ".stat .l{display:block;font-size:.74rem;color:#767676}"
       "footer{max-width:1180px;margin:0 auto 2.5rem;padding:0 1rem;color:#767676;font-size:.78rem}"))

(defn render
  "The whole page, from the post-run store and the run log."
  [{:keys [db runs]}]
  (str "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<meta name=\"color-scheme\" content=\"light\">"
       "<title>Operator console — cloud-itonami-isic-2513 (steamgenmfg)</title>"
       "<style>" page-css "</style></head>\n<body>\n"
       "<header class=\"bar\">"
       "<h1>Steam generator plant operations — operator console</h1>"
       "<p><span class=\"chip\">ISIC 2513</span>"
       "<span class=\"chip\">steamgenmfg</span>"
       "governor <code style=\"background:rgba(255,255,255,.15);color:#fff\">"
       "steam-generator-plant-operations-governor</code> · actor "
       (esc (:actor-id coordinator)) " · role " (esc (:actor-role coordinator))
       " · phase " (esc (:phase coordinator))
       "</p></header>\n<main>\n"
       (str/join "\n"
                 (remove nil?
                         [(summary-section db runs)
                          (timeline-section runs)
                          (holds-section db)
                          (rejections-section db)
                          (phase-section)
                          (governor-section)
                          (bounds-section)
                          (batches-section db)
                          (equipment-section db)
                          (maintenance-section db)
                          (shipments-section db)
                          (concerns-section db)
                          (ledger-section db)]))
       "\n</main>\n<footer>"
       "Generated at build time by <code>steamgenmfg.render-html</code> "
       "(<code>clojure -M:dev:render-html</code>) by driving the real "
       "<code>steamgenmfg.operation</code> actor graph over the real "
       "<code>steamgenmfg.store</code> seed. Deterministic — no clock, no randomness, no network. "
       "No usage, revenue or performance metric is claimed anywhere on this page."
       "</footer>\n</body>\n</html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)]
    ;; A console that shows no real HARD hold is not evidence of a governor.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count runs) " requests)"))))
