(ns venueadminops.advisor
  "VenueAdminAdvisor -- the LLM-driven proposal generator. This advisor
  generates proposals that the governor will evaluate. The advisor is
  *not* responsible for policy or scope enforcement; those are the
  governor's job.

  The advisor's job is to generate well-reasoned proposals based on the
  request and current state, along with a confidence level. The governor
  will then either approve, hold, escalate, or reject the proposal based on
  its three HARD checks and escalation policy.

  For testing, we provide both a mock advisor (hardcoded responses) and a
  real LLM seam (via langchain.model). The mock is sufficient for testing
  the governor's behavior; the real LLM is for production use.")

(defprotocol Advisor
  (propose [adv request context store] "Generate a proposal"))

;; ----------------------------- MockAdvisor (for testing) -----------------------------

(defrecord MockAdvisor []
  Advisor
  (propose [_ request _context store]
    ;; Happy-path proposal: schedule a venue booking
    {:op :schedule-venue-booking
     :venue-id (:venue-id request)
     :summary (str "Schedule " (:event-name request) " at " (:venue-id request))
     :rationale "Standard venue booking coordination"
     :cites []
     :value {:event-name (:event-name request)
             :date (:date request)
             :performer-count (get request :performer-count 1)
             :technical-requirements (get request :technical-requirements [])}
     :effect :propose
     :confidence 0.85}))

(defn mock-advisor [] (->MockAdvisor))

;; ----------------------------- Out-of-Scope Test Advisor
;; This advisor deliberately drafts proposals that touch forbidden scope,
;; so the governor's scope-exclusion scan can be tested end to end.

(defrecord OutOfScopeTestAdvisor []
  Advisor
  (propose [_ request _context _store]
    (case (:test-scenario request)
      :casting
      {:op :coordinate-performer-schedule-proposal
       :venue-id (:venue-id request)
       :summary "Casting decision for lead performer"
       :rationale "We need to select the best talent for this role"
       :cites []
       :value {}
       :effect :propose
       :confidence 0.9}

      :programming
      {:op :schedule-venue-booking
       :venue-id (:venue-id request)
       :summary "Programming decision about what shows to book"
       :rationale "Let's focus on programming the venue with jazz performances"
       :cites []
       :value {}
       :effect :propose
       :confidence 0.9}

      :pricing
      {:op :coordinate-ticketing-logistics
       :venue-id (:venue-id request)
       :summary "Adjust ticket pricing strategy"
       :rationale "We should increase ticket prices to improve revenue"
       :cites []
       :value {}
       :effect :propose
       :confidence 0.9}

      ;; default: return a clean proposal
      {:op :schedule-venue-booking
       :venue-id (:venue-id request)
       :summary "Schedule venue booking"
       :rationale "Standard coordination"
       :cites []
       :value {}
       :effect :propose
       :confidence 0.85})))

(defn out-of-scope-test-advisor [] (->OutOfScopeTestAdvisor))

;; ----------------------------- Governor-Probe Test Advisor
;; Same role as `OutOfScopeTestAdvisor` above -- a deliberate test double, not
;; a production advisor -- but aimed at the governor/phase behaviours the two
;; shipped advisors cannot reach through `venueadminops.operation/run-operation`:
;;
;;   * `MockAdvisor` only ever emits `:schedule-venue-booking` with
;;     `:effect :propose` and confidence 0.85, so three of the five ops on the
;;     governor's closed allowlist, the `:effect-not-propose` HARD check and
;;     both SOFT escalation gates were unreachable end-to-end.
;;   * `OutOfScopeTestAdvisor` only ever emits scope-excluded drafts.
;;
;; Every field it emits is copied from the request (which the caller builds out
;; of the seeded store) -- it invents no venue, booking, date or capacity of its
;; own. Used by `venueadminops.render-html` to drive the operator console.

(defrecord GovernorProbeAdvisor []
  Advisor
  (propose [_ request _context _store]
    (let [venue-id (:venue-id request)
          cites (if-let [b (:booking-id request)] [b] [])
          booked (select-keys request [:event-name :date])]
      (case (:test-scenario request)
        ;; HARD check #2: a draft that claims direct actuation instead of a
        ;; mere proposal. Everything else about it is clean.
        :rogue-effect
        {:op :schedule-venue-booking
         :venue-id venue-id
         :summary (str "Book " (:event-name request) " at " venue-id)
         :rationale "Standard venue booking coordination"
         :cites cites
         :value booked
         :effect :commit
         :confidence 0.9}

        ;; SOFT gate: `:flag-safety-concern` always escalates to a human, no
        ;; matter how confident or otherwise-clean the draft is.
        :safety-concern
        {:op :flag-safety-concern
         :venue-id venue-id
         :summary (str "Rigging hazard on the main stage at " venue-id
                       " -- crowd-safety concern raised for human review")
         :rationale "Back-office coordination may raise a hazard, never adjudicate it"
         :cites cites
         :value (select-keys request [:capacity])
         :effect :propose
         :confidence 0.95}

        ;; SOFT gate: confidence below `governor/confidence-floor`.
        :low-confidence
        {:op :schedule-venue-booking
         :venue-id venue-id
         :summary (str "Tentative date for " (:event-name request) " at " venue-id)
         :rationale "Room-turnaround estimates in the request disagree"
         :cites cites
         :value booked
         :effect :propose
         :confidence 0.4}

        ;; Clean drafts for the three allowlisted ops `MockAdvisor` never emits.
        :clean-supply
        {:op :coordinate-supply-request
         :venue-id venue-id
         :summary (str "Restock front-of-house consumables for " venue-id)
         :rationale "Administrative consumables supply coordination"
         :cites cites
         :value (select-keys request [:capacity])
         :effect :propose
         :confidence 0.88}

        :clean-ticketing
        {:op :coordinate-ticketing-logistics
         :venue-id venue-id
         :summary (str "Will-call desk staffing and gate-scanner allocation for "
                       (:event-name request))
         :rationale "Box-office logistics only -- desk staffing and scanner allocation"
         :cites cites
         :value (select-keys request [:capacity :date])
         :effect :propose
         :confidence 0.82}

        :clean-performer-schedule
        {:op :coordinate-performer-schedule-proposal
         :venue-id venue-id
         :summary (str "Rehearsal call-time coordination for " (:event-name request))
         :rationale "Administrative schedule coordination for performers already engaged"
         :cites cites
         :value booked
         :effect :propose
         :confidence 0.87}

        ;; default: a clean booking draft, same shape as MockAdvisor's
        {:op :schedule-venue-booking
         :venue-id venue-id
         :summary (str "Schedule " (:event-name request) " at " venue-id)
         :rationale "Standard venue booking coordination"
         :cites cites
         :value booked
         :effect :propose
         :confidence 0.85}))))

(defn governor-probe-advisor [] (->GovernorProbeAdvisor))

;; ----------------------------- DefaultAdvisor

(defn advisor
  "Choose an advisor implementation. The mock is sufficient for dev/test.
  For production, use the real LLM seam (future)."
  [mode]
  (case mode
    :mock (mock-advisor)
    :test-out-of-scope (out-of-scope-test-advisor)
    :test-governor-probe (governor-probe-advisor)
    (mock-advisor)))  ;; default to mock
