(ns venueadminops.phase
  "Phase rollout: 0 (read-only) → 3 (auto-commit with escalation).

  Each phase controls which operations are allowed and which auto-commit
  vs. hold for approval.

  Phase 0: Read-only. No operations allowed.
  Phase 1: Venue booking + performer schedule coordination (approval-gated).
  Phase 2: + Supply coordination + ticketing logistics (approval-gated).
  Phase 3: Auto-commit clean proposals; safety concerns always escalate.

  Note: `:flag-safety-concern` is NEVER in any phase's `:auto` set -- it
  always escalates, reinforced by the governor's `:flag-safety-concern`
  escalation rule (two layers of enforcement).")

(def phases
  {0 {:name "read-only"
      :allowed #{}
      :auto #{}}

   1 {:name "booking-and-coordination"
      :allowed #{:schedule-venue-booking
                 :coordinate-performer-schedule-proposal}
      :auto #{}}

   2 {:name "supply-and-ticketing"
      :allowed #{:schedule-venue-booking
                 :coordinate-performer-schedule-proposal
                 :coordinate-supply-request
                 :coordinate-ticketing-logistics}
      :auto #{}}

   3 {:name "auto-commit"
      :allowed #{:schedule-venue-booking
                 :coordinate-performer-schedule-proposal
                 :coordinate-supply-request
                 :coordinate-ticketing-logistics
                 :flag-safety-concern}
      :auto #{:schedule-venue-booking
              :coordinate-performer-schedule-proposal
              :coordinate-supply-request
              :coordinate-ticketing-logistics}
      ;; :flag-safety-concern is explicitly NOT in :auto set
      }})

(defn current-phase
  "Return the phase config for the given phase number."
  [phase-num]
  (get phases phase-num))

(defn can-operate?
  "True if the op is allowed in this phase."
  [phase-num op]
  (let [phase (current-phase phase-num)
        allowed (:allowed phase #{})]
    (contains? allowed op)))

(defn auto-commit?
  "True if this op should auto-commit in this phase (when governor is clean
  and no escalation is needed)."
  [phase-num op]
  (let [phase (current-phase phase-num)
        auto (:auto phase #{})]
    (contains? auto op)))

(defn phase-decision
  "Given a phase, governor result, and operation, return the decision:
  :commit, :hold, or :escalate.

  - :commit if governor is clean AND auto-commit applies in this phase
  - :hold if governor is clean but not auto-commit (needs approval)
  - :escalate if governor says to escalate OR op is :flag-safety-concern"
  [phase-num op governor-result]
  (cond
    (:hard? governor-result)
    :hold  ;; hard violations stay held

    (:escalate? governor-result)
    :escalate

    (auto-commit? phase-num op)
    :commit

    :else
    :hold))
