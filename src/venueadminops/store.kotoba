(ns venueadminops.store
  "SSoT for the ISIC-900 performing-arts-venue COORDINATION actor,
  behind a `Store` protocol so the backend is a swap, not a rewrite -- the
  same seam every `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of a performing-arts
  venue or production company: venue/rehearsal-space booking scheduling,
  administrative performer schedule coordination, non-creative consumables
  supply coordination, box-office ticketing logistics, and safety-concern
  flagging (equipment/rigging hazards, crowd-safety issues).
  It never touches artistic direction, casting, programming/curatorial
  decisions, pricing policy, or safety-authority overrides -- see
  `venueadminops.governor`'s `scope-exclusion-violations`, a HARD,
  permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `venues` directory keyed by `:venue-id` STRING (never a
  keyword -- consistent keying from the start, avoiding the silent-miss bug).

  A registered/verified venue record must exist before ANY proposal for that
  venue may ever commit or escalate -- `venueadminops.governor`'s
  `venue-unverified-violations` re-derives this from the venue's own
  `:registered?`/`:verified?` fields, never from proposal self-report, the SAME
  'ground truth, not self-report' discipline every sibling actor's own governor uses.

  The ledger stays append-only: which venue a proposal targeted, which operation,
  on what basis, committed/held/escalated and approved by whom is always a query
  over an immutable log.")

(defprotocol Store
  (venue [s venue-id] "Registered venue record, or nil.
    Venue map: {:venue-id .. :name .. :registered? bool :verified? bool}.")
  (all-venues [s])
  (booking [s booking-id] "Booking record, or nil.")
  (all-bookings [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-venues [s venues] "replace/seed the venue directory")
  (with-bookings [s bookings] "replace/seed the booking directory"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained venue and booking directory covering both the
  happy path and the governor's own hard checks, so the actor + tests run offline."
  []
  {:venues
   {"venue-1" {:venue-id "venue-1" :name "Apollo Theater"
               :registered? true :verified? true
               :address "New York, NY" :capacity 1500}
    "venue-2" {:venue-id "venue-2" :name "Kennedy Center"
               :registered? true :verified? true
               :address "Washington, DC" :capacity 2500}
    "venue-3" {:venue-id "venue-3" :name "Local Studio (in intake)"
               :registered? true :verified? false
               :address "Springfield, IL" :capacity 200}}
   :bookings
   {"booking-1" {:booking-id "booking-1" :venue-id "venue-1"
                 :event-name "Summer Jazz Festival"
                 :date "2026-08-15" :status "confirmed"}
    "booking-2" {:booking-id "booking-2" :venue-id "venue-2"
                 :event-name "Chamber Orchestra Series"
                 :date "2026-09-01" :status "tentative"}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (venue [_ venue-id] (get-in @a [:venues venue-id]))
  (all-venues [_] (sort-by :venue-id (vals (:venues @a))))
  (booking [_ booking-id] (get-in @a [:bookings booking-id]))
  (all-bookings [_] (sort-by :booking-id (vals (:bookings @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-venues [s venues] (when (seq venues) (swap! a assoc :venues venues)) s)
  (with-bookings [s bookings] (when (seq bookings) (swap! a assoc :bookings bookings)) s))

(defn seed-db
  "A MemStore seeded with the demo venue/booking directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with explicit `venues` and `bookings` maps. The primary
  test/dev entry point. Both may be empty (an unregistered-everywhere store)."
  [venues bookings]
  (->MemStore (atom {:venues (or venues {})
                     :bookings (or bookings {})
                     :ledger []
                     :coordination-log []})))
