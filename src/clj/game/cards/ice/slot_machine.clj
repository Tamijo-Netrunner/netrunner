(in-ns 'game.cards.ice)

(require
   '[clojure.java.io :as io]
   '[clojure.string :as str]
   '[cond-plus.core :refer [cond+]]
   '[game.core.access :refer [access-bonus access-card breach-server max-access]]
   '[game.core.bad-publicity :refer [gain-bad-publicity]]
   '[game.core.board :refer [all-active-installed all-installed card->server
                            get-all-cards get-all-installed server->zone]]
   '[game.core.card :refer [active? agenda? asset? can-be-advanced? card-index
                           corp? faceup? get-card get-counters get-zone
                           hardware? has-subtype? ice? in-discard? in-hand? installed? is-type? operation?
                           program? protecting-a-central? protecting-archives? protecting-hq? protecting-rd?
                           resource? rezzed? runner?]]
   '[game.core.card-defs :refer [card-def]]
   '[game.core.costs :refer [total-available-credits]]
   '[game.core.damage :refer [damage]]
   '[game.core.def-helpers :refer [combine-abilities corp-recur defcard
                                  do-brain-damage do-net-damage offer-jack-out
                                  reorder-choice]]
   '[game.core.drawing :refer [draw]]
   '[game.core.effects :refer [get-effects register-floating-effect
                              unregister-constant-effects]]
   '[game.core.eid :refer [complete-with-result effect-completed make-eid]]
   '[game.core.engine :refer [gather-events pay register-events resolve-ability
                             trigger-event unregister-events]]
   '[game.core.finding :refer [find-cid]]
   '[game.core.flags :refer [can-rez? card-flag? prevent-draw prevent-jack-out
                            register-run-flag! register-turn-flag!
                            zone-locked?]]
   '[game.core.gaining :refer [gain-credits lose-clicks lose-credits]]
   '[game.core.hand-size :refer [hand-size]]
   '[game.core.hosting :refer [host]]
   '[game.core.ice :refer [add-sub add-sub! break-sub ice-strength-bonus
                          remove-sub! remove-subs! resolve-subroutine
                          set-current-ice unbroken-subroutines-choice update-all-ice update-all-icebreakers
                          update-ice-strength]]
   '[game.core.initializing :refer [card-init]]
   '[game.core.installing :refer [corp-install corp-install-list
                                 corp-install-msg]]
   '[game.core.memory :refer [available-mu]]
   '[game.core.moving :refer [as-agenda mill move swap-ice swap-installed trash
                             trash-cards]]
   '[game.core.optional :refer [get-autoresolve set-autoresolve]]
   '[game.core.payment :refer [can-pay?]]
   '[game.core.prompts :refer [cancellable clear-wait-prompt]]
   '[game.core.props :refer [add-counter add-prop]]
   '[game.core.purging :refer [purge]]
   '[game.core.revealing :refer [reveal]]
   '[game.core.rezzing :refer [derez get-rez-cost rez]]
   '[game.core.runs :refer [bypass-ice encounter-ends end-run
                           force-ice-encounter get-current-encounter prevent-access
                           redirect-run set-next-phase]]
   '[game.core.say :refer [system-msg]]
   '[game.core.servers :refer [central->name protecting-same-server?
                              target-server zone->name]]
   '[game.core.shuffling :refer [shuffle!]]
   '[game.core.subtypes :refer [update-all-subtypes]]
   '[game.core.tags :refer [gain-tags lose-tags]]
   '[game.core.to-string :refer [card-str]]
   '[game.core.toasts :refer [toast]]
   '[game.core.update :refer [update!]]
   '[game.macros :refer [continue-ability effect msg req wait-for]]
   '[game.utils :refer :all]
   '[jinteki.utils :refer :all])

(defcard "Slot Machine"
  (letfn [(top-3 [state] (take 3 (get-in @state [:runner :deck])))
          (effect-type [card] (keyword (str "slot-machine-top-3-" (:cid card))))
          (name-builder [card] (str (:title card) " (" (:type card) ")"))
          (top-3-names [cards] (map name-builder cards))
          (top-3-types [state card et]
            (->> (get-effects state :corp card et)
                 first
                 (keep :type)
                 (into #{})
                 count))
          (ability []
            {:label "Encounter ability (manual)"
             :async true
             :effect (req (move state :runner (first (:deck runner)) :deck)
                          (let [t3 (top-3 state)
                                effect-type (effect-type card)]
                            (register-floating-effect
                              state side card
                              {:type effect-type
                               :duration :end-of-encounter
                               :value t3})
                            (system-msg state side
                                        (str "uses Slot Machine to put the top card of the stack to the bottom,"
                                             " then reveal the top 3 cards in the stack: "
                                             (str/join ", " (top-3-names t3))))
                            (reveal state side eid t3)))})]
    {:on-encounter (ability)
     :abilities [(ability)]
     :subroutines [{:label "Runner loses 3 [Credits]"
                    :msg "force the Runner to lose 3 [Credits]"
                    :async true
                    :effect (effect (lose-credits :runner eid 3))}
                   {:label "Gain 3 [Credits]"
                    :async true
                    :effect (req (let [et (effect-type card)
                                       unique-types (top-3-types state card et)]
                                   ;; When there are 3 cards in the deck, sub needs 2 or fewer unique types
                                   ;; When there are 2 cards in the deck, sub needs 1 unique type
                                   (if (or (and (<= unique-types 2)
                                                  (= 3 (count (first (get-effects state :corp card et)))))
                                             (and (= unique-types 1)
                                                  (= 2 (count (first (get-effects state :corp card et))))))
                                     (do (system-msg state :corp (str "uses Slot Machine to gain 3 [Credits]"))
                                         (gain-credits state :corp eid 3))
                                     (effect-completed state side eid))))}
                   {:label "Place 3 advancement tokens"
                    :async true
                    :effect (effect
                              (continue-ability
                                (let [et (effect-type card)
                                      unique-types (top-3-types state card et)]
                                  (when (and (= 3 (count (first (get-effects state :corp card et))))
                                             (= 1 unique-types))
                                    {:choices {:card installed?}
                                     :prompt "Choose an installed card"
                                     :msg (msg "place 3 advancement tokens on "
                                               (card-str state target))
                                     :effect (effect (add-prop target :advance-counter 3 {:placed true}))}))
                                card nil))}]}))
