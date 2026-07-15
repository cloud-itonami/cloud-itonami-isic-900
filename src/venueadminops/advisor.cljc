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

;; ----------------------------- DefaultAdvisor

(defn advisor
  "Choose an advisor implementation. The mock is sufficient for dev/test.
  For production, use the real LLM seam (future)."
  [mode]
  (case mode
    :mock (mock-advisor)
    :test-out-of-scope (out-of-scope-test-advisor)
    (mock-advisor)))  ;; default to mock
