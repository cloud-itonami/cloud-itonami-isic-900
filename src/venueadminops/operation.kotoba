(ns venueadminops.operation
  "The core execution engine: intake → advise → govern → decide → commit
  | hold | escalate. NOTE: despite the state-machine framing below, this
  is a plain `->` threading pipeline through ordinary functions -- it is
  NOT a compiled `langgraph.graph/state-graph` (deps.edn has no langgraph
  dependency at all). `run-operation` itself also has no direct test
  coverage (test/venueadminops/core_test.clj only exercises the
  store/governor/phase pieces individually, never this pipeline
  end-to-end). A real langgraph-clj StateGraph (with `g/run*`/
  interrupt-before human sign-off) is a known gap for this actor's
  upgrade from :blueprint to :implemented; see e.g.
  cloud-itonami-isco-1221's salesmgmt.actor for the target shape.

  A `request` flows through the pipeline:
  1. :intake — read the request, seed state
  2. :advise — ask the advisor to generate a proposal
  3. :govern — run the proposal through governor checks
  4. :decide — phase + governor result → commit/hold/escalate decision
  5. :commit/:hold/:escalate — terminal states

  In phase 0, only :intake completes; :advise returns 'read-only' hold.
  In phase 1–2, :advise runs but most ops are :hold (approval-gated).
  In phase 3, clean non-safety ops auto-commit."
  (:require [venueadminops.store :as store]
            [venueadminops.advisor :as advisor]
            [venueadminops.governor :as governor]
            [venueadminops.phase :as phase]))

;; ----------------------------- portable helpers ---

#?(:clj (defn- now-ms [] (System/currentTimeMillis)))
#?(:cljs (defn- now-ms [] (js/Date.now)))

#?(:clj (defn- new-uuid [] (java.util.UUID/randomUUID)))
#?(:cljs (defn- new-uuid [] (random-uuid)))

;; ----------------------------- State / Transitions ---

(defn intake
  "Intake state: read the request, seed initial state."
  [{:keys [request context phase store advisor-mode] :as state}]
  (assoc state
    :venue-id (:venue-id request)
    :operation-id (str "op-" (new-uuid))
    :timestamp (now-ms)
    :status :intake))

(defn advise
  "Advise state: ask advisor to generate proposal."
  [{:keys [phase] :as state}]
  (let [adv (advisor/advisor (:advisor-mode state :mock))
        request (:request state)
        context (:context state)
        store (:store state)
        proposal (if (phase/can-operate? phase (:op (advisor/propose adv request context store)))
                   (advisor/propose adv request context store)
                   {:op :noop :effect :propose :confidence 0.0})]
    (assoc state
      :proposal proposal
      :status :advise)))

(defn govern
  "Govern state: run proposal through governor checks."
  [{:keys [proposal context store] :as state}]
  (let [request (:request state)
        gov-result (governor/check request context proposal store)]
    (assoc state
      :governor-result gov-result
      :status :govern)))

(defn decide
  "Decide state: phase + governor result → decision."
  [{:keys [phase proposal governor-result] :as state}]
  (let [op (:op proposal)
        decision (phase/phase-decision phase op governor-result)]
    (assoc state
      :decision decision
      :status :decide)))

(defn commit
  "Terminal: commit the proposal."
  [state]
  (let [{:keys [store proposal]} state
        record {:operation-id (:operation-id state)
                :timestamp (:timestamp state)
                :venue-id (:venue-id state)
                :proposal proposal
                :decision :commit}]
    (store/append-ledger! (:store state) {:type :commit :record record})
    (store/commit-record! (:store state) record)
    (assoc state :status :committed :decision-record record)))

(defn hold
  "Terminal: hold for approval."
  [state]
  (let [{:keys [proposal]} state
        record {:operation-id (:operation-id state)
                :timestamp (:timestamp state)
                :venue-id (:venue-id state)
                :proposal proposal
                :decision :hold}]
    (store/append-ledger! (:store state) {:type :hold :record record})
    (assoc state :status :held :decision-record record)))

(defn escalate
  "Terminal: escalate for human review."
  [state]
  (let [{:keys [proposal]} state
        record {:operation-id (:operation-id state)
                :timestamp (:timestamp state)
                :venue-id (:venue-id state)
                :proposal proposal
                :decision :escalate}]
    (store/append-ledger! (:store state) {:type :escalate :record record})
    (assoc state :status :escalated :decision-record record)))

;; ----------------------------- Graph / Router

(defn route-from-decide
  "Router after decide state: send to commit, hold, or escalate based on decision."
  [state]
  (case (:decision state)
    :commit :commit
    :escalate :escalate
    :hold :hold))

(defn run-operation
  "Execute one complete operation: request → final state.

  Args:
    request — {:venue-id .. :event-name .. :date .. ...}
    phase — 0, 1, 2, or 3
    store — a Store instance
    context — additional context (e.g. user info, audit trail)
    advisor-mode — :mock, :test-out-of-scope, etc.

  Returns the final state with :status one of :committed, :held, :escalated."
  [request phase store context advisor-mode]
  (let [state {:request request
               :context context
               :phase phase
               :store store
               :advisor-mode advisor-mode}]
    (-> state
        (intake)
        (advise)
        (govern)
        (decide)
        ((fn [s]
           (case (route-from-decide s)
             :commit (commit s)
             :hold (hold s)
             :escalate (escalate s)))))))
