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

(defcard "Manegarm Skunkworks"
  {:events [{:event :approach-server
             :interactive (req true)
             :player :runner
             :prompt "Choose one"
             :waiting-prompt true
             :req (req this-server)
             :choices (req [(when (can-pay? state :runner eid card nil [:click 2])
                              "Spend [Click][Click]")
                            (when (can-pay? state :runner eid card nil [:credit 5])
                              "Pay 5 [Credits]")
                            "End the run"])
             :async true
             :effect (req (cond+
                            [(and (= target "Spend [Click][Click]")
                                  (can-pay? state :runner eid card nil [:click 2]))
                             (wait-for (pay state side (make-eid state eid) card :click 2)
                                       (system-msg state side (:msg async-result))
                                       (effect-completed state :runner eid))]
                            [(and (= target "Pay 5 [Credits]")
                                  (can-pay? state :runner eid card nil [:credit 5]))
                             (wait-for (pay state side (make-eid state eid) card :credit 5)
                                       (system-msg state side (:msg async-result))
                                       (effect-completed state :runner eid))]
                            [:else
                             (system-msg state :corp "uses Manegarm Skunkworks to end the run")
                             (end-run state :corp eid card)]))}]})
