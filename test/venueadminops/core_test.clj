(ns venueadminops.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [venueadminops.store :as store]
            [venueadminops.governor :as governor]
            [venueadminops.advisor :as advisor]
            [venueadminops.phase :as phase]
            [venueadminops.operation :as op]))

;; ====================== Store Tests ======================

(deftest test-store-mem-store
  (testing "MemStore protocol"
    (let [st (store/mem-store {"venue-1" {:venue-id "venue-1"
                                          :name "Test Venue"
                                          :registered? true
                                          :verified? true}}
                             {})]
      (is (= "Test Venue" (:name (store/venue st "venue-1"))))
      (is (nil? (store/venue st "nonexistent")))
      (is (= 1 (count (store/all-venues st)))))))

(deftest test-store-seed-db
  (testing "seed-db has demo data"
    (let [st (store/seed-db)]
      (is (= 3 (count (store/all-venues st))))
      (is (= 2 (count (store/all-bookings st)))))))

(deftest test-ledger-append
  (testing "ledger append-only"
    (let [st (store/seed-db)]
      (store/append-ledger! st {:type :test :data "fact-1"})
      (store/append-ledger! st {:type :test :data "fact-2"})
      (is (= 2 (count (store/ledger st)))))))

;; ====================== Governor Tests ======================

(deftest test-governor-venue-unverified
  (testing "venue-unverified violation when venue is not registered"
    (let [st (store/mem-store {} {})
          proposal {:op :schedule-venue-booking
                    :venue-id "nonexistent"
                    :effect :propose
                    :confidence 0.85}
          result (governor/check {:venue-id "nonexistent"} {} proposal st)]
      (is (not (:ok? result)))
      (is (some #(= (:rule %) :venue-unverified) (:violations result))))))

(deftest test-governor-venue-verified
  (testing "venue-verified passes when venue is registered and verified"
    (let [st (store/mem-store {"venue-1" {:venue-id "venue-1"
                                          :name "Test"
                                          :registered? true
                                          :verified? true}}
                             {})
          proposal {:op :schedule-venue-booking
                    :venue-id "venue-1"
                    :effect :propose
                    :confidence 0.85}
          result (governor/check {} {} proposal st)]
      (is (not (some #(= (:rule %) :venue-unverified) (:violations result)))))))

(deftest test-governor-effect-not-propose
  (testing "effect-not-propose violation when effect is not :propose"
    (let [st (store/seed-db)
          proposal {:op :schedule-venue-booking
                    :venue-id "venue-1"
                    :effect :commit  ;; wrong!
                    :confidence 0.85}
          result (governor/check {:venue-id "venue-1"} {} proposal st)]
      (is (not (:ok? result)))
      (is (some #(= (:rule %) :effect-not-propose) (:violations result))))))

(deftest test-governor-scope-exclusion-casting
  (testing "scope-exclusion when proposal mentions casting"
    (let [st (store/seed-db)
          proposal {:op :coordinate-performer-schedule-proposal
                    :venue-id "venue-1"
                    :summary "We need to select the best talent for this role"
                    :effect :propose
                    :confidence 0.85}
          result (governor/check {} {} proposal st)]
      (is (not (:ok? result)))
      (is (some #(= (:rule %) :scope-excluded) (:violations result))))))

(deftest test-governor-scope-exclusion-programming
  (testing "scope-exclusion when proposal mentions programming"
    (let [st (store/seed-db)
          proposal {:op :schedule-venue-booking
                    :venue-id "venue-1"
                    :summary "Let's focus on programming the venue with jazz performances"
                    :effect :propose
                    :confidence 0.85}
          result (governor/check {} {} proposal st)]
      (is (not (:ok? result)))
      (is (some #(= (:rule %) :scope-excluded) (:violations result))))))

(deftest test-governor-scope-exclusion-pricing
  (testing "scope-exclusion when proposal mentions pricing"
    (let [st (store/seed-db)
          proposal {:op :coordinate-ticketing-logistics
                    :venue-id "venue-1"
                    :summary "Adjust ticket pricing strategy"
                    :effect :propose
                    :confidence 0.85}
          result (governor/check {} {} proposal st)]
      (is (not (:ok? result)))
      (is (some #(= (:rule %) :scope-excluded) (:violations result))))))

(deftest test-governor-op-not-allowed
  (testing "op-not-allowed when op is not in allowlist"
    (let [st (store/seed-db)
          proposal {:op :unknown-operation
                    :venue-id "venue-1"
                    :effect :propose
                    :confidence 0.85}
          result (governor/check {} {} proposal st)]
      (is (not (:ok? result)))
      (is (some #(= (:rule %) :op-not-allowed) (:violations result))))))

(deftest test-governor-legitimate-safety-concern
  (testing "legitimate safety concern is not scope-excluded"
    (let [st (store/seed-db)
          proposal {:op :flag-safety-concern
                    :venue-id "venue-1"
                    :summary "Equipment rigging hazard detected"
                    :effect :propose
                    :confidence 0.95}
          result (governor/check {} {} proposal st)]
      ;; Should not have scope-exclusion violation
      (is (not (some #(= (:rule %) :scope-excluded) (:violations result)))))))

(deftest test-governor-escalate-safety-concern
  (testing "safety-concern always escalates"
    (let [st (store/seed-db)
          proposal {:op :flag-safety-concern
                    :venue-id "venue-1"
                    :summary "Crowd safety concern"
                    :effect :propose
                    :confidence 0.95}
          result (governor/check {} {} proposal st)]
      (is (:escalate? result)))))

(deftest test-governor-escalate-low-confidence
  (testing "low confidence (< 0.6) escalates"
    (let [st (store/seed-db)
          proposal {:op :schedule-venue-booking
                    :venue-id "venue-1"
                    :effect :propose
                    :confidence 0.45}  ;; below floor
          result (governor/check {} {} proposal st)]
      (is (:escalate? result)))))

;; ====================== Phase Tests ======================

(deftest test-phase-0-read-only
  (testing "phase 0 is read-only"
    (is (not (phase/can-operate? 0 :schedule-venue-booking)))
    (is (empty? (:auto (phase/current-phase 0))))))

(deftest test-phase-1-booking
  (testing "phase 1 allows venue booking but not auto-commit"
    (is (phase/can-operate? 1 :schedule-venue-booking))
    (is (not (phase/auto-commit? 1 :schedule-venue-booking)))))

(deftest test-phase-3-auto-commit
  (testing "phase 3 auto-commits non-safety ops"
    (is (phase/can-operate? 3 :schedule-venue-booking))
    (is (phase/auto-commit? 3 :schedule-venue-booking))
    (is (not (phase/auto-commit? 3 :flag-safety-concern)))))

(deftest test-phase-decision-commit
  (testing "phase-decision :commit when auto-commit applies"
    (let [gov-result {:ok? true :escalate? false :hard? false}]
      (is (= :commit (phase/phase-decision 3 :schedule-venue-booking gov-result))))))

(deftest test-phase-decision-hold
  (testing "phase-decision :hold when not auto-commit"
    (let [gov-result {:ok? true :escalate? false :hard? false}]
      (is (= :hold (phase/phase-decision 1 :schedule-venue-booking gov-result))))))

(deftest test-phase-decision-escalate
  (testing "phase-decision :escalate when governor says escalate"
    (let [gov-result {:ok? true :escalate? true :hard? false}]
      (is (= :escalate (phase/phase-decision 3 :schedule-venue-booking gov-result))))))

(deftest test-phase-decision-hard-hold
  (testing "phase-decision :hold when hard violations"
    (let [gov-result {:ok? false :escalate? false :hard? true :violations [{:rule :venue-unverified}]}]
      (is (= :hold (phase/phase-decision 3 :schedule-venue-booking gov-result))))))

;; ====================== Operation Tests ======================

(deftest test-operation-happy-path-phase-1-holds
  (testing "phase 1 approval-gates even clean proposals"
    (let [st (store/seed-db)
          request {:venue-id "venue-1"
                   :event-name "Jazz Quartet Performance"
                   :date "2026-08-20"
                   :performer-count 4}
          result (op/run-operation request 1 st {} :mock)]
      (is (= :held (:status result))))))

(deftest test-operation-happy-path-phase-3-commits
  (testing "phase 3 auto-commits clean non-safety ops"
    (let [st (store/seed-db)
          request {:venue-id "venue-1"
                   :event-name "Jazz Quartet Performance"
                   :date "2026-08-20"
                   :performer-count 4}
          result (op/run-operation request 3 st {} :mock)]
      (is (= :committed (:status result))))))

(deftest test-operation-unregistered-venue-holds
  (testing "unregistered venue results in hold"
    (let [st (store/seed-db)
          request {:venue-id "venue-3"  ;; unverified
                   :event-name "Summer Concert"
                   :date "2026-08-15"}
          result (op/run-operation request 3 st {} :mock)]
      (is (= :held (:status result))))))

(deftest test-operation-escalation-recorded
  (testing "escalated operations are recorded in ledger"
    (let [st (store/seed-db)
          request {:venue-id "venue-1"
                   :event-name "Performance"
                   :date "2026-08-15"}
          result (op/run-operation request 3 st {} :mock)]
      (is (seq (store/ledger st))))))
