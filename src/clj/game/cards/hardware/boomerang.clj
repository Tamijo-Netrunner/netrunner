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

(defcard "Boomerang"
  {:on-install {:prompt "Choose an installed piece of ice"
                :msg (msg "target " (card-str state target))
                :choices {:card #(and (installed? %)
                                      (ice? %))}
                :effect (effect (add-icon card target "B" "blue")
                                (update! (assoc-in (get-card state card) [:special :boomerang-target] target)))}
   :leave-play (effect (remove-icon card))
   :abilities [(assoc
                 (break-sub
                   [:trash-can] 2 "All"
                   {:req (req (if-let [boomerang-target (get-in card [:special :boomerang-target])]
                                (some #(same-card? boomerang-target (:ice %)) (:encounters @state))
                                true))}) ; When eg. flipped by Assimilator
                 :effect
                 (req (wait-for
                        (trash state side (make-eid state eid) card {:cause :ability-cost
                                                                     :cause-card card
                                                                     :unpreventable true})
                        (continue-ability
                          state :runner
                          (when-let [[boomerang] async-result]
                            (break-sub
                              nil 2 "All"
                              {:additional-ability
                               {:effect
                                (effect
                                  (register-events
                                    boomerang
                                    [{:event :run-ends
                                      :duration :end-of-run
                                      :optional
                                      {:req (req (and (:successful target)
                                                      (not (zone-locked? state :runner :discard))
                                                      (some #(= "Boomerang" (:title %)) (:discard runner))))
                                       :once :per-run
                                       :prompt (msg "Shuffle a copy of " (:title card) " back into the Stack?")
                                       :yes-ability
                                       {:msg (msg "shuffle a copy of " (:title card) " back into the Stack")
                                        :effect (effect (move (some #(when (= "Boomerang" (:title %)) %)
                                                                    (:discard runner))
                                                              :deck)
                                                        (shuffle! :deck))}}}]))}}))
                          card nil))))]})
