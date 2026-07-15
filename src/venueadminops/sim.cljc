(ns venueadminops.sim
  "Demo/simulator driver. Exercises all scenarios:
  - Phase 1–3 happy-path operations
  - All four HARD-check failure modes
  - Escalation rules
  - Safety-concern flagging (always escalates)"
  (:require [venueadminops.store :as store]
            [venueadminops.operation :as op]
            [venueadminops.advisor :as advisor]
            [venueadminops.governor :as governor]))

(defn scenario-happy-path-phase-1
  "Phase 1: venue booking proposal (approval-gated, should hold)"
  []
  (let [st (store/seed-db)
        request {:venue-id "venue-1"
                 :event-name "Jazz Quartet Performance"
                 :date "2026-08-20"
                 :performer-count 4}
        result (op/run-operation request 1 st {} :mock)]
    {:scenario "happy-path-phase-1"
     :status (:status result)
     :decision (:decision result)
     :expected-status :held
     :pass? (= (:status result) :held)}))

(defn scenario-happy-path-phase-3
  "Phase 3: same request auto-commits (clean, non-safety op)"
  []
  (let [st (store/seed-db)
        request {:venue-id "venue-1"
                 :event-name "Jazz Quartet Performance"
                 :date "2026-08-20"
                 :performer-count 4}
        result (op/run-operation request 3 st {} :mock)]
    {:scenario "happy-path-phase-3"
     :status (:status result)
     :decision (:decision result)
     :expected-status :committed
     :pass? (= (:status result) :committed)}))

(defn scenario-unregistered-venue
  "HARD check #1: target venue is unverified → hold regardless of phase"
  []
  (let [st (store/seed-db)
        request {:venue-id "venue-3"  ;; unverified
                 :event-name "Summer Concert"
                 :date "2026-08-15"}
        result (op/run-operation request 3 st {} :mock)]
    {:scenario "unregistered-venue-hard-check"
     :status (:status result)
     :decision (:decision result)
     :governor-violations (-> result :governor-result :violations)
     :expected-status :held
     :pass? (= (:status result) :held)}))

(defn scenario-effect-not-propose
  "HARD check #2: effect not :propose → hard violation"
  []
  (let [st (store/seed-db)
        request {:venue-id "venue-1"
                 :event-name "Performance"
                 :date "2026-08-15"
                 :force-effect :commit}  ;; signals the advisor to use non-:propose effect
        ;; For this test, we'll manually create a proposal with wrong effect
        adv (advisor/mock-advisor)
        proposal (advisor/propose adv request {} st)
        proposal-bad-effect (assoc proposal :effect :commit)
        gov-result (venueadminops.governor/check request {} proposal-bad-effect st)]
    {:scenario "effect-not-propose-hard-check"
     :ok? (:ok? gov-result)
     :violations (:violations gov-result)
     :expected-ok? false
     :pass? (not (:ok? gov-result))}))

(defn scenario-scope-exclusion-casting
  "HARD check #3: proposal touches casting (out of scope) → hard block"
  []
  (let [st (store/seed-db)
        request {:venue-id "venue-1"
                 :test-scenario :casting}
        adv (advisor/out-of-scope-test-advisor)
        proposal (advisor/propose adv request {} st)
        gov-result (venueadminops.governor/check request {} proposal st)]
    {:scenario "scope-exclusion-casting"
     :ok? (:ok? gov-result)
     :violations (:violations gov-result)
     :expected-ok? false
     :pass? (not (:ok? gov-result))}))

(defn scenario-scope-exclusion-programming
  "HARD check #3: proposal touches programming/curatorial (out of scope)"
  []
  (let [st (store/seed-db)
        request {:venue-id "venue-1"
                 :test-scenario :programming}
        adv (advisor/out-of-scope-test-advisor)
        proposal (advisor/propose adv request {} st)
        gov-result (venueadminops.governor/check request {} proposal st)]
    {:scenario "scope-exclusion-programming"
     :ok? (:ok? gov-result)
     :violations (:violations gov-result)
     :expected-ok? false
     :pass? (not (:ok? gov-result))}))

(defn scenario-scope-exclusion-pricing
  "HARD check #3: proposal touches pricing policy (out of scope)"
  []
  (let [st (store/seed-db)
        request {:venue-id "venue-1"
                 :test-scenario :pricing}
        adv (advisor/out-of-scope-test-advisor)
        proposal (advisor/propose adv request {} st)
        gov-result (venueadminops.governor/check request {} proposal st)]
    {:scenario "scope-exclusion-pricing"
     :ok? (:ok? gov-result)
     :violations (:violations gov-result)
     :expected-ok? false
     :pass? (not (:ok? gov-result))}))

(defn scenario-safety-concern-escalates
  "Safety concerns always escalate (op :flag-safety-concern)"
  []
  (let [st (store/seed-db)
        request {:venue-id "venue-1"
                 :event-name "Performance"
                 :date "2026-08-15"}
        adv (advisor/mock-advisor)
        proposal (advisor/propose adv request {} st)
        safety-proposal (assoc proposal :op :flag-safety-concern
                                       :summary "Equipment rigging hazard detected"
                                       :confidence 0.95)
        result (op/run-operation request 3 st {} :mock)]
    ;; Manually run through governor to check escalation
    (let [gov-result (venueadminops.governor/check request {} safety-proposal st)]
      {:scenario "safety-concern-escalates"
       :op (:op safety-proposal)
       :escalate? (:escalate? gov-result)
       :expected-escalate? true
       :pass? (:escalate? gov-result)})))

(defn demo
  "Run all scenarios and print results."
  []
  (let [scenarios [scenario-happy-path-phase-1
                   scenario-happy-path-phase-3
                   scenario-unregistered-venue
                   scenario-effect-not-propose
                   scenario-scope-exclusion-casting
                   scenario-scope-exclusion-programming
                   scenario-scope-exclusion-pricing
                   scenario-safety-concern-escalates]
        results (mapv (fn [f] (f)) scenarios)
        passed (filter :pass? results)
        failed (filter #(not (:pass? %)) results)]
    (println (str "\n=== venueadminops Simulator ===\n"
                  "Scenarios: " (count results) "\n"
                  "Passed: " (count passed) "\n"
                  "Failed: " (count failed) "\n"))
    (doseq [r results]
      (println (str "  " (:scenario r) ": " (if (:pass? r) "PASS" "FAIL"))))
    (when (seq failed)
      (println "\nFailed scenarios:")
      (doseq [r failed]
        (println (str "  " (:scenario r)))
        (println (str "    " (dissoc r :scenario :pass?))))))
  nil)

#?(:clj
   (defn -main [& _args]
     (demo)))
