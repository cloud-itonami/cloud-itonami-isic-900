(ns venueadminops.governor
  "VenueAdminGovernor -- the independent compliance layer that earns the
  VenueAdminAdvisor the right to commit. The advisor has no notion of
  whether a venue is actually registered and verified, whether its own
  proposed `:effect` secretly claims a direct actuation instead of a mere
  proposal, or whether it has silently drifted into a permanently
  out-of-scope decision area, so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- COORDINATION ONLY
  (venue/rehearsal-space booking scheduling, performer schedule coordination,
  supply coordination, ticketing logistics, safety-concern flagging).
  It NEVER performs or authorizes:
    - artistic direction or creative decisions
    - casting or talent selection decisions
    - programming or curatorial choices
    - ticket pricing or revenue policy decisions
    - safety-authority overrides (complaint investigation, license enforcement)

  Three HARD checks, ALL permanent, un-overridable by any human approval:

    1. Venue unverified       -- the target venue record must exist AND
                                be independently confirmed `:registered?`/
                                `:verified?` in the store before ANY
                                proposal for it may commit or even escalate.
                                Never trusts a proposal's own claim about
                                the venue -- re-derived from the venue's own
                                store record, the same 'ground truth, not
                                self-report' discipline every sibling actor's
                                governor uses.
    2. Effect not :propose   -- every proposal's `:effect` MUST be
                                `:propose`. Any other effect value is, by
                                construction, a claim to directly
                                actuate/commit outside governance -- HARD
                                block, not merely low-confidence.
    3. Scope exclusion       -- ANY proposal (regardless of op) whose op,
                                rationale, summary, citations or draft
                                value touches artistic/creative/casting/
                                programming/pricing/safety-authority
                                territory is a HARD, PERMANENT block -- this
                                actor's charter excludes that territory
                                structurally, not as a rollout milestone.
                                Evaluated UNCONDITIONALLY on every proposal.
                                An op outside the closed five-op allowlist
                                is the SAME failure mode (an advisor proposing
                                something it was never authorized to propose)
                                and is folded into this same check.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-safety-concern` -- ALWAYS escalates to a human, regardless
  of confidence, regardless of how clean the proposal otherwise is.
  `venueadminops.phase` independently agrees: `:flag-safety-concern` is
  never a member of any phase's `:auto` set either -- two layers, not one."
  (:require [kotoba.lang.text :as str]
            [venueadminops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:schedule-venue-booking :coordinate-performer-schedule-proposal
    :coordinate-supply-request :coordinate-ticketing-logistics
    :flag-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- artistic/creative direction,
  casting, programming/curatorial, pricing, or safety-authority enforcement.
  Scanned across the proposal's op/summary/rationale/cites/value, never
  trusting the advisor's own framing of its intent."
  ["artistic" "artistic direction" "創造的" "創作"
   "casting" "talent selection" "talent" "キャスティング" "出演者選"
   "programming" "curatorial" "プログラミング" "キュレーション"
   "pricing" "ticket price" "revenue" "料金" "チケット価格" "売上"
   "creative decision" "creative-decision" "創作判断"
   "safety authority" "safety-authority" "safety enforcement"
   "license" "compliance enforcement" "compliance-enforcement"
   "investigat" "complaint" "違反" "通報"])

;; ----------------------------- checks -----------------------------

(defn- venue-unverified-violations
  "The target venue must exist AND be independently `:registered?`/`:verified?`
  in the store -- never trust the proposal's own `:venue-id` claim without a
  store lookup."
  [{:keys [venue-id]} st]
  (let [v (store/venue st venue-id)]
    (when-not (and v (:registered? v) (:verified? v))
      [{:rule :venue-unverified
        :detail (str venue-id " は未登録または未検証の会場 -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim to
  directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one lower-cased
  blob the scope-exclusion scan checks."
  [proposal]
  (str/lower (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist, or one
  whose content touches artistic/casting/programming/pricing/safety-authority
  territory, regardless of confidence or how clean every other check is.
  Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "創作/キャスティング/キュレーション/料金設定/安全当局の判断領域に触れる提案は永久に禁止"}])))

(defn check
  "Censors a VenueAdminAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [venue-id (or (:venue-id proposal) (:venue-id request))
        hard (into []
                   (concat (venue-unverified-violations {:venue-id venue-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        escalate-op (contains? always-escalate-ops (:op proposal))
        low-confidence (< conf confidence-floor)
        ok? (empty? hard)
        escalate? (and ok? (or escalate-op low-confidence))]
    {:ok? ok?
     :violations hard
     :confidence conf
     :escalate? escalate?
     :high-stakes? (not ok?)
     :hard? (not ok?)}))
