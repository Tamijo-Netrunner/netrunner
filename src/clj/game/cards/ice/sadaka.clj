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

(defcard "Sadaka"
  (let [maybe-draw-effect
        {:optional
         {:player :corp
          :waiting-prompt true
          :prompt "Draw 1 card?"
          :yes-ability
          {:async true
           :effect (effect (draw eid 1))
           :msg "draw 1 card"}}}]
    {:subroutines [{:label "Look at the top 3 cards of R&D"
                    :req (req (not-empty (:deck corp)))
                    :async true
                    :effect
                    (effect (continue-ability
                              (let [top-cards (take 3 (:deck corp))
                                    top-names (map :title top-cards)]
                                {:waiting-prompt true
                                 :prompt (str "The top cards of R&D are: " (str/join ", " top-names))
                                 :choices ["Arrange cards" "Shuffle R&D"]
                                 :async true
                                 :effect
                                 (req (if (= target "Arrange cards")
                                        (wait-for
                                          (resolve-ability state side (reorder-choice :corp top-cards) card nil)
                                          (system-msg state :corp (str "rearranges the top "
                                                                       (quantify (count top-cards) "card")
                                                                       " of R&D"))
                                          (continue-ability state side maybe-draw-effect card nil))
                                        (do
                                          (shuffle! state :corp :deck)
                                          (system-msg state :corp (str "shuffles R&D"))
                                          (continue-ability state side maybe-draw-effect card nil))))})
                              card nil))}
                   {:label "Trash 1 card in HQ"
                    :async true
                    :effect
                    (req (wait-for
                           (resolve-ability
                             state side
                             {:waiting-prompt true
                              :prompt "Choose a card in HQ to trash"
                              :choices (req (cancellable (:hand corp) :sorted))
                              :async true
                              :cancel-effect (effect (system-msg "declines to use Sadaka to trash a card from HQ")
                                                     (effect-completed eid))
                              :effect (req (wait-for
                                             (trash state :corp target {:cause :subroutine})
                                             (system-msg state :corp "trashes a card from HQ")
                                             (continue-ability state side trash-resource-sub card nil)))}
                             card nil)
                           (wait-for (trash state :corp (make-eid state eid) card {:cause-card card})
                                     (system-msg state :corp "uses Sadaka to trash itself")
                                     (encounter-ends state side eid))))}]}))
