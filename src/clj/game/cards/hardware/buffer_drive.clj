(in-ns 'game.cards.hardware)

(require
   '[clojure.java.io :as io]
   '[clojure.set :as set]
   '[clojure.string :as str]
   '[game.core.access :refer [access-bonus access-card breach-server
                             get-only-card-to-access]]
   '[game.core.actions :refer [play-ability]]
   '[game.core.board :refer [all-active all-active-installed all-installed]]
   '[game.core.card :refer [corp? event? facedown? faceup? get-card
                           get-counters get-zone hardware? has-subtype? ice? in-deck?
                           in-discard? in-hand? installed? program? resource? rezzed? runner? virus-program?]]
   '[game.core.card-defs :refer [card-def]]
   '[game.core.cost-fns :refer [all-stealth install-cost
                               rez-additional-cost-bonus rez-cost trash-cost]]
   '[game.core.damage :refer [chosen-damage damage damage-prevent
                             enable-runner-damage-choice runner-can-choose-damage?]]
   '[game.core.def-helpers :refer [breach-access-bonus defcard offer-jack-out
                                  reorder-choice trash-on-empty]]
   '[game.core.drawing :refer [draw]]
   '[game.core.effects :refer [register-floating-effect
                              unregister-effects-for-card unregister-floating-effects]]
   '[game.core.eid :refer [effect-completed make-eid make-result]]
   '[game.core.engine :refer [can-trigger? register-events register-once
                             resolve-ability trigger-event
                             unregister-floating-events]]
   '[game.core.events :refer [event-count first-event? first-trash? no-event?
                             run-events]]
   '[game.core.expose :refer [expose]]
   '[game.core.finding :refer [find-card]]
   '[game.core.flags :refer [card-flag? in-corp-scored? register-run-flag!
                            zone-locked?]]
   '[game.core.gaining :refer [gain-clicks gain-credits lose-clicks
                              lose-credits]]
   '[game.core.hand-size :refer [hand-size runner-hand-size+]]
   '[game.core.hosting :refer [host]]
   '[game.core.ice :refer [all-subs-broken? break-sub pump reset-all-ice
                          update-all-ice update-all-icebreakers
                          update-breaker-strength]]
   '[game.core.installing :refer [install-locked? runner-can-install?
                                 runner-can-pay-and-install? runner-install]]
   '[game.core.link :refer [get-link link+]]
   '[game.core.mark :refer [identify-mark-ability]]
   '[game.core.memory :refer [caissa-mu+ mu+ update-mu virus-mu+]]
   '[game.core.moving :refer [mill move swap-agendas trash trash-cards]]
   '[game.core.optional :refer [get-autoresolve never? set-autoresolve]]
   '[game.core.payment :refer [build-cost-string can-pay? cost-value]]
   '[game.core.play-instants :refer [play-instant]]
   '[game.core.prompts :refer [cancellable clear-wait-prompt]]
   '[game.core.props :refer [add-counter add-icon remove-icon]]
   '[game.core.revealing :refer [reveal]]
   '[game.core.rezzing :refer [derez rez]]
   '[game.core.runs :refer [bypass-ice end-run end-run-prevent
                           get-current-encounter jack-out make-run
                           successful-run-replace-breach total-cards-accessed]]
   '[game.core.sabotage :refer [sabotage-ability]]
   '[game.core.say :refer [system-msg]]
   '[game.core.servers :refer [target-server]]
   '[game.core.set-aside :refer [get-set-aside set-aside]]
   '[game.core.shuffling :refer [shuffle!]]
   '[game.core.tags :refer [gain-tags lose-tags tag-prevent]]
   '[game.core.to-string :refer [card-str]]
   '[game.core.toasts :refer [toast]]
   '[game.core.update :refer [update!]]
   '[game.core.virus :refer [count-virus-programs number-of-virus-counters]]
   '[game.macros :refer [continue-ability effect msg req wait-for]]
   '[game.utils :refer :all]
   '[jinteki.utils :refer :all])

(defcard "Buffer Drive"
  (let [grip-or-stack-trash?
        (fn [targets]
          (some #(and (runner? (:card %))
                      (or (in-hand? (:card %))
                          (in-deck? (:card %))))
                targets))
        triggered-ability
        {:once-per-instance true
         :req (req (and (grip-or-stack-trash? targets)
                        (first-trash? state grip-or-stack-trash?)))
         :prompt "Add a trashed card to the bottom of the stack"
         :choices (req (conj (sort (map :title (map :card targets))) "No action"))
         :async true
         :effect (req (if (= "No action" target)
                        (effect-completed state side eid)
                        (do (system-msg state side
                                        (str "uses Buffer Drive to add " target
                                             " to the bottom of the stack"))
                            (move state side (find-card target (:discard (:runner @state))) :deck)
                            (effect-completed state side eid))))}]
    {:events [(assoc triggered-ability :event :runner-trash)
              (assoc triggered-ability :event :corp-trash)]
     :abilities [{:req (req (not (zone-locked? state :runner :discard)))
                  :label "Add a card from the heap to the top of the stack"
                  :cost [:remove-from-game]
                  :show-discard true
                  :choices {:card #(and (runner? %)
                                        (in-discard? %))}
                  :msg (msg "add " (:title target) " to the top of the stack")
                  :effect (effect (move target :deck {:front true}))}]}))
