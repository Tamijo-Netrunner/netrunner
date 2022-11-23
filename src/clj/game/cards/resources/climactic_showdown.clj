(in-ns 'game.cards.resources)

(require
   '[clojure.java.io :as io]
   '[clojure.pprint :as pprint]
   '[clojure.string :as str]
   '[game.core.access :refer [access-bonus access-n-cards breach-server steal
                             steal-cost-bonus]]
   '[game.core.actions :refer [get-runnable-zones]]
   '[game.core.agendas :refer [update-all-advancement-requirements
                              update-all-agenda-points]]
   '[game.core.bad-publicity :refer [gain-bad-publicity]]
   '[game.core.board :refer [all-active all-active-installed all-installed
                            server->zone]]
   '[game.core.card :refer [agenda? asset? assoc-host-zones card-index corp?
                           event? facedown? get-agenda-points get-card
                           get-counters get-zone hardware? has-subtype? ice? identity? in-discard? in-hand?
                           installed? is-type? program? resource? rezzed? runner? upgrade? virus-program?]]
   '[game.core.card-defs :refer [card-def]]
   '[game.core.charge :refer [can-charge charge-ability]]
   '[game.core.cost-fns :refer [has-trash-ability? install-cost rez-cost
                               trash-cost]]
   '[game.core.costs :refer [total-available-credits]]
   '[game.core.damage :refer [damage damage-prevent]]
   '[game.core.def-helpers :refer [breach-access-bonus defcard offer-jack-out
                                  reorder-choice trash-on-empty]]
   '[game.core.drawing :refer [draw draw-bonus first-time-draw-bonus]]
   '[game.core.effects :refer [register-floating-effect]]
   '[game.core.eid :refer [complete-with-result effect-completed make-eid]]
   '[game.core.engine :refer [checkpoint not-used-once? pay register-events
                             register-once register-suppress resolve-ability
                             trigger-event trigger-event-sync unregister-events unregister-suppress-by-uuid]]
   '[game.core.events :refer [event-count first-event?
                             first-installed-trash-own? first-run-event?
                             first-successful-run-on-server? get-turn-damage no-event? second-event? turn-events]]
   '[game.core.expose :refer [expose]]
   '[game.core.flags :refer [can-run-server? card-flag? clear-persistent-flag!
                            has-flag? in-corp-scored?
                            register-persistent-flag! register-turn-flag! zone-locked?]]
   '[game.core.gaining :refer [gain gain-clicks gain-credits lose lose-clicks
                              lose-credits]]
   '[game.core.hand-size :refer [corp-hand-size+ hand-size runner-hand-size+]]
   '[game.core.hosting :refer [host]]
   '[game.core.ice :refer [break-sub break-subroutine! get-strength pump
                          unbroken-subroutines-choice update-all-ice
                          update-all-icebreakers update-breaker-strength]]
   '[game.core.identities :refer [disable-card enable-card]]
   '[game.core.initializing :refer [card-init make-card]]
   '[game.core.installing :refer [install-locked? runner-can-install?
                                 runner-can-pay-and-install? runner-install]]
   '[game.core.link :refer [get-link link+]]
   '[game.core.mark :refer [identify-mark-ability]]
   '[game.core.memory :refer [available-mu]]
   '[game.core.moving :refer [as-agenda flip-faceup forfeit mill move
                             remove-from-currently-drawing trash trash-cards
                             trash-prevent]]
   '[game.core.optional :refer [get-autoresolve never? set-autoresolve]]
   '[game.core.payment :refer [build-spend-msg can-pay?]]
   '[game.core.pick-counters :refer [pick-virus-counters-to-spend]]
   '[game.core.play-instants :refer [play-instant]]
   '[game.core.prompts :refer [cancellable]]
   '[game.core.props :refer [add-counter]]
   '[game.core.revealing :refer [reveal]]
   '[game.core.rezzing :refer [derez rez]]
   '[game.core.runs :refer [bypass-ice gain-run-credits get-current-encounter
                           make-run set-next-phase
                           successful-run-replace-breach total-cards-accessed]]
   '[game.core.sabotage :refer [sabotage-ability]]
   '[game.core.say :refer [system-msg]]
   '[game.core.servers :refer [central->name is-central? is-remote?
                              protecting-same-server? target-server unknown->kw
                              zone->name zones->sorted-names]]
   '[game.core.set-aside :refer [get-set-aside set-aside]]
   '[game.core.shuffling :refer [shuffle!]]
   '[game.core.tags :refer [gain-tags lose-tags tag-prevent]]
   '[game.core.to-string :refer [card-str]]
   '[game.core.toasts :refer [toast]]
   '[game.core.update :refer [update!]]
   '[game.core.virus :refer [get-virus-counters number-of-runner-virus-counters]]
   '[game.core.winning :refer [check-win-by-agenda]]
   '[game.macros :refer [continue-ability effect msg req wait-for]]
   '[game.utils :refer :all]
   '[jinteki.utils :refer :all]
   '[jinteki.validator :refer [legal?]]
   '[medley.core :refer [find-first]])

(defcard "Climactic Showdown"
  (letfn [(iced-servers [state side eid card]
            (filter #(-> (get-in @state (cons :corp (server->zone state %))) :ices count pos?)
                    (zones->sorted-names (get-runnable-zones state side eid card nil))))
          (trash-or-bonus [chosen-server]
            {:player :corp
             :prompt "Choose a piece of ice to trash"
             :choices {:card #(and (= (last (get-zone %)) :ices)
                                   (= chosen-server (rest (butlast (get-zone %)))))}
             :async true
             :effect (effect (system-msg (str "trashes " (card-str state target)))
                             (trash :corp eid target {:unpreventable true :cause-card card :cause :forced-to-trash}))
             :cancel-effect (effect (system-msg (str "declines to trash a piece of ice protecting " (zone->name chosen-server)))
                                    (register-events
                                      :runner card
                                      [{:event :breach-server
                                        :duration :until-runner-turn-ends
                                        :req (req (#{:hq :rd} target))
                                        :once :per-turn
                                        :msg (msg "access 2 additional cards from " (zone->name target))
                                        :effect (effect (access-bonus :runner target 2))}]))})]
    {:events [{:event :runner-turn-begins
               :async true
               :effect (req (let [card (move state side card :rfg)]
                              (continue-ability
                                state side
                                (if (pos? (count (iced-servers state side eid card)))
                                  {:prompt "Choose a server"
                                   :choices (req (iced-servers state side eid card))
                                   :msg (msg "choose " (zone->name (unknown->kw target))
                                             " and remove itself from the game")
                                   :effect (effect (continue-ability
                                                     :corp
                                                     (trash-or-bonus (rest (server->zone state target)))
                                                     card nil))}
                                  {:msg "remove itself from the game"})
                                card nil)))}]}))
