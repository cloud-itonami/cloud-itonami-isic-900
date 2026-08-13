(ns venueadminops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had NO demo console and no generator at all -- only
  the product face `docs/index.html`.

  Everything on the page is produced by running THIS repo's real actor:
  `venueadminops.operation/run-operation` -> `venueadminops.advisor` ->
  `venueadminops.governor` -> `venueadminops.phase` -> `venueadminops.store`,
  against a fresh `store/seed-db`. Every venue id, booking id, event name,
  date and capacity in the page is read back out of that seeded store (the
  driver refuses to start a scenario whose venue/booking is not actually in
  the store -- see `request-for`), and every disposition, violation rule and
  violation detail string is the governor's own output. Nothing is typed by
  hand into the markup except section prose and the fixed-behaviour
  descriptions in `rule-notes`, which are labelled as such on the page.

  NOTE on the pipeline: `operation/run-operation` is a plain `->` threading
  pipeline, NOT a `langgraph.graph/state-graph` -- this repo has no langgraph
  dependency (README line 36 still claims a StateGraph; `operation.cljc`'s own
  docstring records the drift). There is therefore no `interrupt-before` /
  resume seam and no human-approval step to drive: a `:hold` is terminal.
  That is measured, not assumed -- see the `retention-rows` section, which
  scans the actual records the store kept.

  DETERMINISM: byte-identical across reruns. Two record fields are
  deliberately never rendered -- `:operation-id` (a random UUID) and
  `:timestamp` (wall clock). Both are disclosed in the retention table
  instead of being silently dropped.

  Usage: `clojure -M:dev:render-html [out-file]`
         (default `docs/samples/operator-console.html`)"
  (:require [clojure.string :as str]
            [jp-go-dds.skin :as skin]
            [venueadminops.store :as store]
            [venueadminops.operation :as op]
            [venueadminops.governor :as governor]
            [venueadminops.phase :as phase]))

;; ============================ the run ============================

(def operator-context
  "The operator context the build-time driver hands to every
  `run-operation` call. It is NOT store data -- it exists so the retention
  section can MEASURE whether the actor keeps operator identity anywhere
  (spoiler: measured at render time, not asserted here)."
  {:actor-id "venue-admin-1"
   :actor-role :venue-operations-coordinator})

(def scenarios
  "The scenario matrix. `:venue`/`:booking` are looked up in the seeded store
  and the driver throws if either is absent, so no scenario can reference an
  entity that does not exist. `:expect` is checked against the actual
  pipeline outcome in `-main` -- a drift between the two fails the build."
  [{:id "S01" :phase 3 :mode :mock :venue "venue-1" :booking "booking-1"
    :expect :committed
    :label "Booking draft auto-commits at phase 3"}
   {:id "S02" :phase 3 :mode :mock :venue "venue-2" :booking "booking-2"
    :expect :committed
    :label "Second venue, same clean path"}
   {:id "S03" :phase 1 :mode :mock :venue "venue-1" :booking "booking-1"
    :expect :held-clean
    :label "Same draft at phase 1 -- clean, but approval-gated"}
   {:id "S04" :phase 2 :mode :mock :venue "venue-2" :booking "booking-2"
    :expect :held-clean
    :label "Phase 2 still auto-commits nothing (:auto is empty)"}
   {:id "S05" :phase 0 :mode :mock :venue "venue-1" :booking "booking-1"
    :expect :held-hard
    :label "Phase 0 is read-only -- the draft degrades to :noop"}
   {:id "S06" :phase 3 :mode :test-governor-probe :test-scenario :clean-supply
    :venue "venue-3"
    :expect :held-hard
    :label "Unverified venue blocks an otherwise clean supply draft"}
   {:id "S07" :phase 3 :mode :test-out-of-scope :test-scenario :casting
    :venue "venue-3"
    :expect :held-hard
    :label "Unverified venue AND scope drift -- two HARD rules at once"}
   {:id "S08" :phase 3 :mode :test-out-of-scope :test-scenario :casting
    :venue "venue-1" :booking "booking-1"
    :expect :held-hard
    :label "Advisor drifts into casting"}
   {:id "S09" :phase 3 :mode :test-out-of-scope :test-scenario :programming
    :venue "venue-2" :booking "booking-2"
    :expect :held-hard
    :label "Advisor drifts into programming/curatorial choice"}
   {:id "S10" :phase 3 :mode :test-out-of-scope :test-scenario :pricing
    :venue "venue-1" :booking "booking-1"
    :expect :held-hard
    :label "Advisor drifts into ticket-pricing policy"}
   {:id "S11" :phase 1 :mode :test-out-of-scope :test-scenario :pricing
    :venue "venue-1" :booking "booking-1"
    :expect :held-hard
    :label "Ticketing op is not yet enabled at phase 1 -- degrades to :noop"}
   {:id "S12" :phase 3 :mode :test-governor-probe :test-scenario :rogue-effect
    :venue "venue-1" :booking "booking-1"
    :expect :held-hard
    :label "Advisor claims direct actuation instead of proposing"}
   {:id "S13" :phase 3 :mode :test-governor-probe :test-scenario :safety-concern
    :venue "venue-1" :booking "booking-1"
    :expect :escalated
    :label "Safety concern always escalates to a human"}
   {:id "S14" :phase 3 :mode :test-governor-probe :test-scenario :low-confidence
    :venue "venue-2" :booking "booking-2"
    :expect :escalated
    :label "Confidence under the floor escalates to a human"}
   {:id "S15" :phase 2 :mode :test-governor-probe :test-scenario :clean-ticketing
    :venue "venue-2" :booking "booking-2"
    :expect :held-clean
    :label "Ticketing logistics enabled at phase 2, still approval-gated"}
   {:id "S16" :phase 3 :mode :test-governor-probe :test-scenario :clean-ticketing
    :venue "venue-2" :booking "booking-2"
    :expect :committed
    :label "Same ticketing draft auto-commits at phase 3"}
   {:id "S17" :phase 3 :mode :test-governor-probe
    :test-scenario :clean-performer-schedule
    :venue "venue-1" :booking "booking-1"
    :expect :committed
    :label "Performer schedule coordination auto-commits at phase 3"}
   {:id "S18" :phase 3 :mode :test-governor-probe :test-scenario :clean-supply
    :venue "venue-2" :booking "booking-2"
    :expect :committed
    :label "Supply coordination auto-commits at phase 3"}])

(defn- request-for
  "Materialise one scenario's request ENTIRELY out of the seeded store.
  Throws if the scenario names a venue or booking the store does not have --
  an evidence floor: the console cannot show an entity the actor never saw."
  [db {:keys [id venue booking test-scenario]}]
  (let [v (or (store/venue db venue)
              (throw (ex-info "scenario names a venue that is not in the seeded store"
                              {:scenario id :venue venue})))
        b (when booking
            (or (store/booking db booking)
                (throw (ex-info "scenario names a booking that is not in the seeded store"
                                {:scenario id :booking booking}))))]
    (cond-> {:venue-id (:venue-id v)
             :capacity (:capacity v)}
      b             (assoc :booking-id (:booking-id b)
                           :event-name (:event-name b)
                           :date (:date b))
      test-scenario (assoc :test-scenario test-scenario))))

(defn run-scenarios!
  "Runs every scenario through the real actor against ONE shared seeded store,
  so the ledger and coordination log accumulate exactly as they would in
  production. Returns `{:db .. :runs [..]}`; each run keeps the final pipeline
  state (which carries the governor result the ledger itself does not retain)."
  []
  (let [db (store/seed-db)
        runs (mapv (fn [s]
                     (let [request (request-for db s)
                           state (op/run-operation request (:phase s) db
                                                   operator-context (:mode s))]
                       (assoc s :request request :state state)))
                   scenarios)]
    {:db db :runs runs}))

(defn- outcome
  "The pipeline's actual disposition, splitting `:held` by whether the
  governor raised HARD violations -- the store's ledger records only
  `:type :hold` for both, so this is derived from the live governor result."
  [{:keys [state]}]
  (case (:status state)
    :committed :committed
    :escalated :escalated
    :held      (if (-> state :governor-result :hard?) :held-hard :held-clean)
    :unknown))

(defn- violations-of [run] (-> run :state :governor-result :violations vec))

;; ============================ html helpers ============================

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- kw [v] (if v (code (str v)) "<span class=\"muted\">&mdash;</span>"))

(defn- td [& cells] (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (when lede (str "    <p class=\"muted\">" lede "</p>\n"))
       body
       "  </section>\n"))

(defn- outcome-cell [o]
  (case o
    :committed  "<span class=\"ok\">committed</span>"
    :held-clean "<span class=\"warn\">held &middot; awaiting approval</span>"
    :held-hard  "<span class=\"critical\">HARD hold &middot; never reaches a human</span>"
    :escalated  "<span class=\"warn\">escalated &middot; human sign-off</span>"
    "<span class=\"muted\">unknown</span>"))

(defn- fmt-val [v]
  (cond
    (string? v)  (esc v)
    (keyword? v) (esc (str v))
    (vector? v)  (if (empty? v) "[]" (esc (pr-str v)))
    :else        (esc (pr-str v))))

(defn- fmt-map [m]
  (if (empty? m)
    "<span class=\"muted\">&mdash;</span>"
    (str/join ", "
              (map (fn [[k v]] (str "<code>" (esc (name k)) "</code>=" (fmt-val v)))
                   (sort-by (comp name key) m)))))

;; ============================ sections ============================

(defn- venue-rows [db runs]
  (let [gate-blocked (->> runs
                          (filter #(some (comp #{:venue-unverified} :rule) (violations-of %)))
                          (map #(-> % :request :venue-id))
                          set)]
    (for [v (store/all-venues db)]
      (td (code (:venue-id v))
          (esc (:name v))
          (esc (:address v))
          (str "<span class=\"num\">" (esc (:capacity v)) "</span>")
          (if (:registered? v) "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>")
          (if (:verified? v) "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>")
          (if (contains? gate-blocked (:venue-id v))
            "<span class=\"critical\">every proposal HARD-held</span>"
            "<span class=\"ok\">proposals may proceed</span>")))))

(defn- booking-rows [db]
  (for [b (store/all-bookings db)]
    (let [v (store/venue db (:venue-id b))]
      (td (code (:booking-id b))
          (code (:venue-id b))
          (esc (:name v))
          (esc (:event-name b))
          (str "<span class=\"num\">" (esc (:date b)) "</span>")
          (esc (:status b))))))

(defn- phase-matrix-rows []
  (let [ops (sort-by name governor/allowed-ops)
        phase-nums (sort (keys phase/phases))]
    (for [o ops]
      (apply td (code o)
             (for [p phase-nums]
               (cond
                 (phase/auto-commit? p o) "<span class=\"ok\">auto-commit</span>"
                 (phase/can-operate? p o) "<span class=\"warn\">approval</span>"
                 :else "<span class=\"muted\">not enabled &rarr; :noop</span>"))))))

(def ^:private rule-notes
  "Fixed-behaviour descriptions of the governor's own rules, read off
  `venueadminops.governor`. Documentation of behaviour that does not vary per
  run -- the only hand-written prose in any table on this page. The `fired`
  counts beside them are computed from this run, not written here."
  {:venue-unverified
   "The target venue must exist in the store AND be independently <code>:registered?</code> and <code>:verified?</code> there. Re-derived from the venue's own record &mdash; a proposal's claim about its venue is never trusted."
   :effect-not-propose
   "Every proposal's <code>:effect</code> must be <code>:propose</code>. Any other value is, by construction, a claim to actuate outside governance."
   :scope-excluded
   "The proposal's op/summary/rationale/cites/value are flattened into one blob and scanned for artistic, casting, programming/curatorial, pricing or safety-authority territory. Permanent &mdash; this actor's charter excludes that territory structurally."
   :op-not-allowed
   "The op is outside the governor's closed allowlist. Also how a phase gate surfaces: an op the current phase does not enable degrades to <code>:noop</code>, which is not on the allowlist."})

(defn- rule-rows [runs]
  (let [all-v (mapcat violations-of runs)
        by-rule (group-by :rule all-v)]
    (for [rule (sort-by name (keys rule-notes))]
      (let [hits (get by-rule rule [])]
        (td (code rule)
            (get rule-notes rule)
            (str "<span class=\"num\">" (count hits) "</span>")
            (if (seq hits)
              (str "<span class=\"critical\">HARD</span> &middot; "
                   (esc (:detail (first hits))))
              "<span class=\"muted\">not raised in this run</span>"))))))

(defn- scenario-rows [runs]
  (for [r runs]
    (let [p (-> r :state :proposal)
          g (-> r :state :governor-result)]
      (td (code (:id r))
          (esc (:label r))
          (str "<span class=\"num\">" (:phase r) "</span>")
          (code (:venue r))
          (kw (:mode r))
          (kw (:op p))
          (str "<span class=\"num\">" (esc (:confidence g)) "</span>")
          (outcome-cell (outcome r))))))

(defn- hard-hold-rows [runs]
  (for [r runs
        v (violations-of r)]
    (td (code (:id r))
        (code (:venue r))
        (kw (-> r :state :proposal :op))
        (str "<span class=\"critical\">" (esc (name (:rule v))) "</span>")
        (esc (:detail v))
        "<span class=\"critical\">no</span>")))

(defn- escalation-rows [runs]
  (for [r runs
        :when (= :escalated (outcome r))]
    (let [p (-> r :state :proposal)
          g (-> r :state :governor-result)
          always? (contains? governor/always-escalate-ops (:op p))
          low? (< (:confidence g) governor/confidence-floor)]
      (td (code (:id r))
          (code (:venue r))
          (kw (:op p))
          (str "<span class=\"num\">" (esc (:confidence g)) "</span>")
          (str/join " &middot; "
                    (cond-> []
                      always? (conj (str "op is in <code>always-escalate-ops</code>"))
                      low?    (conj (str "confidence &lt; floor <span class=\"num\">"
                                         (esc governor/confidence-floor) "</span>"))))
          "<span class=\"warn\">yes &mdash; awaits human sign-off</span>"))))

(defn- proposal-rows [runs]
  (for [r runs]
    (let [p (-> r :state :proposal)]
      (td (code (:id r))
          (kw (:op p))
          (esc (:summary p))
          (esc (:rationale p))
          (if (seq (:cites p))
            (str/join ", " (map code (:cites p)))
            "<span class=\"muted\">&mdash;</span>")
          (kw (:effect p))
          (fmt-map (:value p))))))

(defn- coordination-rows [db]
  (for [rec (store/coordination-log db)]
    (let [p (:proposal rec)]
      (td (code (:venue-id rec))
          (kw (:op p))
          (esc (:summary p))
          (fmt-map (:value p))
          (str "<span class=\"ok\">" (esc (name (:decision rec))) "</span>")))))

(defn- ledger-rows [db runs]
  (let [by-op-id (into {} (map (juxt #(-> % :state :operation-id) identity) runs))]
    (map-indexed
     (fn [i fact]
       (let [rec (:record fact)
             run (get by-op-id (:operation-id rec))
             vs (some-> run violations-of)]
         (td (str "<span class=\"num\">" (inc i) "</span>")
             (code (:type fact))
             (code (or (:id run) "?"))
             (code (:venue-id rec))
             (kw (-> rec :proposal :op))
             (code (:decision rec))
             (if (seq vs)
               (str "<span class=\"critical\">"
                    (esc (str/join ", " (map (comp name :rule) vs)))
                    "</span> <span class=\"muted\">(joined from the live run &mdash; not retained in the record)</span>")
               "<span class=\"muted\">&mdash;</span>"))))
     (store/ledger db))))

;; ---- record retention / attribution, MEASURED at render time ----

(def ^:private approver-keys
  "Every key this fleet's actors use to name who approved something. The
  retention scan looks for ANY of them in the store's own records."
  #{:approved-by :approver :approval :by :actor-id :operator :context :reviewed-by})

(defn- record-keys [db]
  (into (sorted-set) (mapcat keys (store/coordination-log db))))

(defn- ledger-keys [db]
  (into (sorted-set) (mapcat keys (store/ledger db))))

(defn- retained-approver-keys
  "Scans every committed record AND every ledger fact (one level down into the
  record) for any approver-ish key. Returns the set actually found -- so the
  disclosure below is a measurement, not a claim baked into this file."
  [db]
  (let [ks (into (record-keys db)
                 (into (ledger-keys db)
                       (mapcat (comp keys :record) (store/ledger db))))]
    (into (sorted-set) (filter approver-keys ks))))

(defn- retention-rows [db]
  (let [ks (record-keys db)
        rendered {:venue-id "rendered in every table"
                  :proposal "rendered (op, summary, rationale, cites, effect, value)"
                  :decision "rendered"}
        withheld {:operation-id "present, NOT rendered &mdash; a random UUID, would break byte-identical reruns"
                  :timestamp "present, NOT rendered &mdash; wall clock, would break byte-identical reruns"}]
    (concat
     (for [k ks]
       (td (code k)
           "<span class=\"ok\">retained</span>"
           (or (get rendered k) (get withheld k) "&mdash;")))
     [(td (code :context)
          "<span class=\"critical\">dropped</span>"
          (str "the operator context " (code (pr-str operator-context))
               " is passed into <code>run-operation</code> on every scenario and is not copied into either the committed record or the ledger fact"))
      (td (code :governor-result)
          "<span class=\"critical\">dropped</span>"
          "HARD violations are not written to the ledger; the ledger table above joins them from the live run state <span class=\"muted\">(audit only; not retained in record)</span>")])))

(defn- attribution-disclosure [db]
  (let [found (retained-approver-keys db)]
    (if (seq found)
      (str "<p>Measured at render time: the store DOES retain approver-ish keys "
           (str/join ", " (map code found))
           " on its records, so approver attribution is available on this page.</p>")
      (str "<p><strong>Measured at render time, not assumed:</strong> scanning every committed "
           "record and every ledger fact this run produced, <em>none</em> of "
           (str/join ", " (map code (sort approver-keys)))
           " is present. Two separate reasons, and it matters which:</p>"
           "<ul>"
           "<li><strong>Nobody could have approved.</strong> This actor has no approval or resume seam at all &mdash; "
           "<code>operation/hold</code> and <code>operation/escalate</code> are terminal states. "
           "Held and escalated proposals in the tables above are waiting on a human who has no way, in this codebase, to answer. "
           "So the missing approver is not a lost field: no approval event ever happened.</li>"
           "<li><strong>The operator who proposed IS dropped.</strong> The build-time driver passes "
           (code (pr-str operator-context))
           " as <code>context</code> into every <code>run-operation</code> call, and "
           "<code>operation/commit</code>, <code>operation/hold</code> and <code>operation/escalate</code> "
           "all build their record from <code>:operation-id :timestamp :venue-id :proposal :decision</code> only. "
           "The proposing operator's identity is therefore absent from the SSoT &mdash; "
           "<span class=\"muted\">(driver input; not retained in record)</span>. "
           "The store's own docstring promises &ldquo;approved by whom is always a query over an immutable log&rdquo;; "
           "as of this build that promise is not kept by the record shape.</li>"
           "</ul>"))))

;; ============================ page ============================

(defn render [{:keys [db runs]}]
  (let [hard-runs (filter #(= :held-hard (outcome %)) runs)
        hard-rules (into (sorted-set) (map (comp name :rule) (mapcat violations-of runs)))
        n-commit (count (filter #(= :committed (outcome %)) runs))
        n-esc (count (filter #(= :escalated (outcome %)) runs))
        n-hold (count (filter #(= :held-clean (outcome %)) runs))]
    (str
     "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n"
     "<meta name=\"color-scheme\" content=\"light\">\n"
     "<title>cloud-itonami-isic-900 &middot; venueadminops operator console</title>\n"
     "<style>" (skin/dds+skin) "</style>\n"
     "</head>\n<body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Performing-arts venue back-office coordination (ISIC 900) &mdash; Operator Console</h1>\n"
     "</header>\n"
     "<p><span class=\"badge\">read-only sample</span> <span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">generated from a real actor run</span></p>\n"
     "<p class=\"subtitle\">Generated at build time by <code>venueadminops.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) by running "
     "<code>venueadminops.operation/run-operation</code> "
     (count runs) " times against one fresh <code>venueadminops.store/seed-db</code>. "
     "Every venue, booking, event name, date and capacity below is read back out of that store; "
     "every disposition, rule and detail string is the governor's own output.</p>\n"
     "<main>\n"

     (section "This run at a glance" nil
              (table ["Outcome" "Runs" "Meaning"]
                     [(td "<span class=\"ok\">committed</span>"
                          (str "<span class=\"num\">" n-commit "</span>")
                          "clean at a phase whose <code>:auto</code> set contains the op")
                      (td "<span class=\"warn\">held &middot; awaiting approval</span>"
                          (str "<span class=\"num\">" n-hold "</span>")
                          "clean, but the phase does not auto-commit this op")
                      (td "<span class=\"critical\">HARD hold</span>"
                          (str "<span class=\"num\">" (count hard-runs) "</span>")
                          (str "governor raised "
                               (count (mapcat violations-of runs))
                               " un-overridable violation(s) across "
                               (count hard-rules) " distinct rules &mdash; "
                               "<strong>these never reach a human at all</strong>"))
                      (td "<span class=\"warn\">escalated</span>"
                          (str "<span class=\"num\">" n-esc "</span>")
                          "clean, but routed to human sign-off by a SOFT gate")]))

     (section "Venue directory"
              (str "From <code>store/all-venues</code>. The last column is not a field &mdash; "
                   "it is computed from whether this run's governor actually raised "
                   "<code>:venue-unverified</code> against that venue.")
              (table ["Venue" "Name" "Address" "Capacity" "registered?" "verified?" "Gate"]
                     (venue-rows db runs)))

     (section "Booking directory"
              "From <code>store/all-bookings</code>, joined to the venue record by <code>:venue-id</code>. Every scenario request below is built out of these two tables and nothing else."
              (table ["Booking" "Venue" "Venue name" "Event" "Date" "Status"]
                     (booking-rows db)))

     (section "Phase &times; op gate"
              (str "Computed by calling <code>phase/can-operate?</code> and "
                   "<code>phase/auto-commit?</code> for every op on "
                   "<code>governor/allowed-ops</code> at every phase in "
                   "<code>phase/phases</code> &mdash; not transcribed. An op a phase does not "
                   "enable is replaced by <code>:noop</code> in <code>operation/advise</code>, "
                   "which the governor then HARD-blocks as <code>:op-not-allowed</code>.")
              (table ["Op" "Phase 0" "Phase 1" "Phase 2" "Phase 3"]
                     (phase-matrix-rows)))

     (section "Governor rules"
              (str "Three HARD checks plus the closed-allowlist check, all permanent and "
                   "un-overridable by any human approval. <code>fired</code> counts and the "
                   "detail strings are from this run; the descriptions are fixed-behaviour "
                   "documentation read off <code>venueadminops.governor</code>.")
              (str (table ["Rule" "What it checks" "fired" "Example detail (verbatim from the governor)"]
                          (rule-rows runs))
                   "    <p class=\"muted\">Scope scan dictionary: <span class=\"num\">"
                   (count governor/scope-excluded-terms)
                   "</span> case-insensitive terms from <code>governor/scope-excluded-terms</code> &mdash; "
                   (str/join ", " (map code (sort governor/scope-excluded-terms)))
                   ".</p>\n"
                   "    <p class=\"muted\">SOFT escalation gates: op &isin; <code>governor/always-escalate-ops</code> "
                   "(" (str/join ", " (map code (sort-by name governor/always-escalate-ops))) ") "
                   "or confidence &lt; <code>governor/confidence-floor</code> = <span class=\"num\">"
                   governor/confidence-floor "</span>.</p>\n"))

     (section "Scenario run matrix"
              (str "One row per <code>run-operation</code> call, in execution order, against the "
                   "shared store. <code>advisor</code> names which advisor emitted the draft: "
                   "<code>:mock</code> is the shipped production stand-in; "
                   "<code>:test-out-of-scope</code> and <code>:test-governor-probe</code> are the "
                   "repo's deliberate test doubles, used to reach governor rules the mock advisor "
                   "structurally cannot produce.")
              (table ["#" "Scenario" "Phase" "Venue" "Advisor" "Proposed op" "Confidence" "Outcome"]
                     (scenario-rows runs)))

     (section "Proposals the advisors actually emitted"
              "The draft each run handed to the governor, verbatim. Every <code>value</code> field is copied from the request, which is copied from the seeded store."
              (table ["#" "Op" "Summary" "Rationale" "Cites" "Effect" "Value"]
                     (proposal-rows runs)))

     (section "HARD holds &mdash; never reach a human"
              (str "One row per violation (a single run can raise several). These are not "
                   "escalations: <code>phase/phase-decision</code> returns <code>:hold</code> the "
                   "moment <code>:hard?</code> is set, before the escalation branch is ever "
                   "considered, and no human approval can override any of them.")
              (table ["#" "Venue" "Proposed op" "Rule" "Detail (verbatim from the governor)" "Reaches a human?"]
                     (hard-hold-rows runs)))

     (section "SOFT escalations &mdash; routed to a human"
              "Clean proposals the governor still refuses to auto-commit. The reason column is recomputed here from the governor's own <code>always-escalate-ops</code> / <code>confidence-floor</code>, not copied from a label."
              (table ["#" "Venue" "Op" "Confidence" "Why it escalated" "Reaches a human?"]
                     (escalation-rows runs)))

     (section "Committed coordination log"
              "From <code>store/coordination-log</code> &mdash; only proposals that actually committed leave a record here."
              (table ["Venue" "Op" "Summary" "Value" "Decision"]
                     (coordination-rows db)))

     (section "Audit ledger (this run)"
              "From <code>store/ledger</code>, append-only, in order. One fact per run."
              (table ["seq" "Fact" "Scenario" "Venue" "Op" "Decision" "Governor violations"]
                     (ledger-rows db runs)))

     (section "What the SSoT actually retains"
              (str "Measured, not assumed: the key column is the union of the keys present on "
                   "the records the store really kept this run.")
              (str (table ["Key" "Retained?" "Notes"] (retention-rows db))
                   (attribution-disclosure db)))

     "</main>\n"
     "<footer>\n"
     "<p>Regenerate: <code>clojure -M:dev:render-html [out-file]</code>. Deterministic &mdash; "
     "byte-identical across reruns against the same seed. <code>:operation-id</code> (random UUID) "
     "and <code>:timestamp</code> (wall clock) are deliberately withheld from the page rather than "
     "silently dropped; see the retention table.</p>\n"
     "<p class=\"muted\">Pipeline note: <code>operation/run-operation</code> is a plain "
     "<code>-&gt;</code> threading pipeline, not a <code>langgraph.graph/state-graph</code> &mdash; "
     "this repo has no langgraph dependency, so there is no <code>interrupt-before</code> resume "
     "seam and a hold is terminal. <code>README.md</code> still describes a StateGraph; "
     "<code>operation.cljc</code>'s own docstring records the drift.</p>\n"
     "</footer>\n"
     "</body>\n</html>\n")))

;; ============================ entry point ============================

(defn- check-invariants!
  "Build-time invariants. A console that cannot demonstrate an un-overridable
  HARD hold is not evidence of a governor, so the build fails rather than
  emitting a page that quietly proves nothing."
  [{:keys [db runs]}]
  (let [hard (filter #(= :held-hard (outcome %)) runs)
        rules (into (sorted-set) (map :rule (mapcat violations-of runs)))
        drift (remove #(= (:expect %) (outcome %)) runs)]
    (when (empty? runs)
      (throw (ex-info "no scenarios ran -- refusing to report a pass" {})))
    (when (zero? (count (store/ledger db)))
      (throw (ex-info "the run produced an empty audit ledger -- refusing to report a pass" {})))
    (when (empty? hard)
      (throw (ex-info "the run produced ZERO HARD governor holds -- the console would prove nothing about the governor"
                      {:runs (count runs)
                       :outcomes (frequencies (map outcome runs))})))
    (when (seq drift)
      (throw (ex-info "pipeline outcome drifted from the scenario's declared expectation"
                      {:drift (mapv (fn [r] {:id (:id r)
                                             :expected (:expect r)
                                             :actual (outcome r)
                                             :violations (mapv :rule (violations-of r))})
                                    drift)})))
    {:hard-holds (count hard)
     :hard-rules rules
     :ledger (count (store/ledger db))
     :commits (count (store/coordination-log db))}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-scenarios!)
        stats (check-invariants! result)
        html (render result)]
    (spit out html)
    (println "wrote" out
             (str "(" (count html) " bytes, "
                  (count (:runs result)) " runs, "
                  (:ledger stats) " ledger facts, "
                  (:commits stats) " commits, "
                  (:hard-holds stats) " HARD holds across rules "
                  (pr-str (vec (:hard-rules stats))) ")"))))
