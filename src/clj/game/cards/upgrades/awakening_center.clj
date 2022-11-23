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

(defcard "Awakening Center"
  {:can-host (req (ice? target))
   :abilities [{:label "Host a piece of Bioroid ice"
                :cost [:click 1]
                :prompt "Choose a piece of Bioroid ice in HQ to host"
                :choices {:card #(and (ice? %)
                                      (has-subtype? % "Bioroid")
                                      (in-hand? %))}
                :msg "host a piece of Bioroid ice"
                :async true
                :effect (req (corp-install state side eid target card {:ignore-all-cost true}))}]
   :events [{:event :pass-all-ice
             :optional
             {:req (req (and this-server
                             (some #(can-pay? state side (assoc eid :source card :source-type :rez) % nil
                                              [:credit (rez-cost state side % {:cost-bonus -7})])
                                   (:hosted card))))
              :prompt "Rez and force the Runner to encounter a hosted piece of ice?"
              :waiting-prompt true
              :yes-ability
              {:async true
               :prompt "Choose a hosted piece of Bioroid ice to rez"
               :choices (req (:hosted card))
               :msg (msg "lower the rez cost of " (:title target) " by 7 [Credits] and force the Runner to encounter it")
               :effect (req (wait-for (rez state side target {:cost-bonus -7})
                                      (let [ice (:card async-result)]
                                        (register-events
                                          state side card
                                          [{:event :run-ends
                                            :duration :end-of-run
                                            :async true
                                            :req (req (get-card state ice))
                                            :effect (effect (trash eid (get-card state ice) {:cause-card card}))}])
                                        (force-ice-encounter state side eid ice))))}
              :no-ability
              {:effect (effect (system-msg "declines to use Awakening Center"))}}}]})
