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

(defcard "Anansi"
  (let [corp-draw {:optional
                   {:waiting-prompt true
                    :prompt "Draw 1 card?"
                    :yes-ability {:async true
                                  :msg "draw 1 card"
                                  :effect (effect (draw eid 1))}}}
        runner-draw {:player :runner
                     :optional
                     {:waiting-prompt true
                      :prompt "Pay 2 [Credits] to draw 1 card?"
                      :yes-ability
                      {:async true
                       :effect (req (wait-for (pay state :runner (make-eid state eid) card [:credit 2])
                                              (if (:cost-paid async-result)
                                                (do (system-msg state :runner "pays 2 [Credits] to draw 1 card")
                                                    (draw state :runner eid 1))
                                                (do (system-msg state :runner "does not draw 1 card")
                                                    (effect-completed state side eid)))))}
                      :no-ability {:effect (effect (system-msg :runner "does not draw 1 card"))}}}]
    {:subroutines [{:msg "rearrange the top 5 cards of R&D"
                    :async true
                    :waiting-prompt true
                    :effect (req (let [from (take 5 (:deck corp))]
                                   (continue-ability
                                     state side
                                     (when (pos? (count from))
                                       (reorder-choice :corp :runner from '() (count from) from))
                                     card nil)))}
                   {:label "Draw 1 card, runner draws 1 card"
                    :async true
                    :effect (req (wait-for (resolve-ability state side corp-draw card nil)
                                           (continue-ability state :runner runner-draw card nil)))}
                   (do-net-damage 1)]
     :events [(assoc (do-net-damage 3)
                     :event :end-of-encounter
                     :req (req (and (= (:ice context) card)
                                    (seq (remove :broken (:subroutines (:ice context)))))))]}))
