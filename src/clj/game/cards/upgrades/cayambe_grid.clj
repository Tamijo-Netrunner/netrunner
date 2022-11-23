(in-ns 'game.cards.upgrades)

(require
   '[clojure.java.io :as io]
   '[clojure.string :as str]
   '[cond-plus.core :refer [cond+]]
   '[game.core.access :refer [access-bonus installed-access-trigger
                             set-only-card-to-access steal-cost-bonus]]
   '[game.core.bad-publicity :refer [lose-bad-publicity]]
   '[game.core.board :refer [all-active-installed all-installed card->server
                            get-remotes server->zone server-list]]
   '[game.core.card :refer [agenda? asset? can-be-advanced?
                           corp-installable-type? corp? get-card get-counters get-zone
                           has-subtype? ice? in-discard? in-hand? installed? operation? program? resource? rezzed?
                           runner? upgrade?]]
   '[game.core.cost-fns :refer [install-cost rez-cost]]
   '[game.core.costs :refer [total-available-credits]]
   '[game.core.damage :refer [damage]]
   '[game.core.def-helpers :refer [corp-rez-toast defcard offer-jack-out
                                  reorder-choice]]
   '[game.core.drawing :refer [draw]]
   '[game.core.effects :refer [register-floating-effect]]
   '[game.core.eid :refer [effect-completed make-eid]]
   '[game.core.engine :refer [dissoc-req pay register-default-events
                             register-events resolve-ability unregister-events]]
   '[game.core.events :refer [first-event? first-run-event? turn-events]]
   '[game.core.expose :refer [expose-prevent]]
   '[game.core.finding :refer [find-cid find-latest]]
   '[game.core.flags :refer [clear-persistent-flag! enable-run-on-server
                            is-scored? prevent-run-on-server
                            register-persistent-flag! register-run-flag!]]
   '[game.core.gaining :refer [gain-credits lose-clicks lose-credits]]
   '[game.core.hand-size :refer [corp-hand-size+]]
   '[game.core.ice :refer [all-subs-broken? get-run-ices pump-ice
                          unbroken-subroutines-choice update-all-ice update-all-icebreakers]]
   '[game.core.installing :refer [corp-install]]
   '[game.core.moving :refer [mill move remove-from-currently-drawing
                             swap-cards swap-ice trash trash-cards]]
   '[game.core.optional :refer [get-autoresolve set-autoresolve]]
   '[game.core.payment :refer [can-pay? cost-value]]
   '[game.core.play-instants :refer [play-instant]]
   '[game.core.prompts :refer [cancellable clear-wait-prompt]]
   '[game.core.props :refer [add-counter add-prop set-prop]]
   '[game.core.purging :refer [purge]]
   '[game.core.revealing :refer [reveal]]
   '[game.core.rezzing :refer [rez]]
   '[game.core.runs :refer [end-run force-ice-encounter jack-out redirect-run
                           set-next-phase start-next-phase]]
   '[game.core.say :refer [system-msg]]
   '[game.core.servers :refer [central->zone from-same-server? in-same-server?
                              is-central? protecting-same-server? same-server?
                              target-server unknown->kw zone->name]]
   '[game.core.shuffling :refer [shuffle!]]
   '[game.core.tags :refer [gain-tags]]
   '[game.core.to-string :refer [card-str]]
   '[game.core.toasts :refer [toast]]
   '[game.core.trace :refer [init-trace-bonus]]
   '[game.core.update :refer [update!]]
   '[game.macros :refer [continue-ability effect msg req wait-for]]
   '[game.utils :refer :all]
   '[jinteki.utils :refer :all])

(defcard "Cayambe Grid"
  (let [ability {:interactive (req (->> (all-installed state :corp)
                                        (filter #(and (ice? %)
                                                      (same-server? card %)))
                                        count
                                        pos?))
                 :label "place 1 advancement counter (start of turn)"
                 :async true
                 :effect
                 (effect
                   (continue-ability
                     (when (->> (all-installed state :corp)
                                (filter #(and (ice? %)
                                              (same-server? card %)))
                                count
                                pos?)
                       {:prompt (str "Place 1 advancement counter on an ice protecting " (zone->name (second (get-zone card))))
                        :choices {:card #(and (ice? %)
                                              (same-server? % card))}
                        :msg (msg "place 1 advancement counter on " (card-str state target))
                        :effect (effect (add-prop target :advance-counter 1 {:placed true}))})
                     card nil))}]
    {:events [(assoc ability :event :corp-turn-begins)
              {:event :approach-server
               :interactive (req true)
               :req (req this-server)
               :async true
               :effect
               (effect
                 (continue-ability
                   (let [cost (->> (get-run-ices state)
                                   (filter #(pos? (get-counters % :advancement)))
                                   count
                                   (* 2))]
                     {:async true
                      :player :runner
                      :waiting-prompt true
                      :prompt "Choose one"
                      :choices [(when (can-pay? state :runner (assoc eid :source card :source-type :ability) card nil [:credit cost])
                                  (str "Pay " cost " [Credits]"))
                                "End the run"]
                      :msg (msg (if (= target "End the run")
                                  (decapitalize target)
                                  (str "force the Runner to " (decapitalize target))))
                      :effect (req (if (= target "End the run")
                                     (end-run state side eid card)
                                     (wait-for (pay state :runner (make-eid state eid) card :credit cost)
                                               (system-msg state :runner (:msg async-result))
                                               (effect-completed state side eid))))})
                   card nil))}]
     :abilities [ability]}))
