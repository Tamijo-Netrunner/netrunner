(in-ns 'game.cards.operations)

(require
   '[clojure.java.io :as io]
   '[clojure.set :as set]
   '[clojure.string :as str]
   '[game.core.access :refer [access-card steal-cost-bonus]]
   '[game.core.actions :refer [advance score]]
   '[game.core.bad-publicity :refer [gain-bad-publicity lose-bad-publicity]]
   '[game.core.board :refer [all-active-installed all-installed
                            get-all-installed get-remote-names get-remotes
                            installable-servers server->zone]]
   '[game.core.card :refer [agenda? asset? can-be-advanced? card-index
                           corp-installable-type? corp? event? facedown? faceup?
                           get-advancement-requirement get-card get-counters get-zone hardware? has-subtype?
                           ice? identity? in-discard? in-hand? installed? is-type? operation? program?
                           resource? rezzed? runner? upgrade?]]
   '[game.core.card-defs :refer [card-def]]
   '[game.core.cost-fns :refer [play-cost trash-cost]]
   '[game.core.costs :refer [total-available-credits]]
   '[game.core.damage :refer [damage damage-bonus]]
   '[game.core.def-helpers :refer [corp-recur defcard do-brain-damage
                                  reorder-choice]]
   '[game.core.drawing :refer [draw]]
   '[game.core.effects :refer [register-floating-effect]]
   '[game.core.eid :refer [effect-completed make-eid make-result]]
   '[game.core.engine :refer [pay register-events resolve-ability]]
   '[game.core.events :refer [first-event? last-turn? no-event? not-last-turn?
                             turn-events]]
   '[game.core.flags :refer [can-score? clear-persistent-flag! in-corp-scored?
                            in-runner-scored? is-scored? prevent-jack-out
                            register-persistent-flag! register-turn-flag! when-scored? zone-locked?]]
   '[game.core.gaining :refer [gain-clicks gain-credits lose-clicks
                              lose-credits]]
   '[game.core.hand-size :refer [runner-hand-size+]]
   '[game.core.ice :refer [add-extra-sub! remove-extra-subs! update-all-ice]]
   '[game.core.identities :refer [disable-identity enable-identity]]
   '[game.core.initializing :refer [ability-init card-init]]
   '[game.core.installing :refer [corp-install corp-install-list
                                 corp-install-msg install-as-condition-counter]]
   '[game.core.memory :refer [mu+ update-mu]]
   '[game.core.moving :refer [as-agenda mill move swap-agendas swap-ice trash
                             trash-cards]]
   '[game.core.payment :refer [can-pay? cost-target]]
   '[game.core.play-instants :refer [play-instant]]
   '[game.core.prompts :refer [cancellable clear-wait-prompt show-wait-prompt]]
   '[game.core.props :refer [add-counter add-prop]]
   '[game.core.purging :refer [purge]]
   '[game.core.revealing :refer [reveal]]
   '[game.core.rezzing :refer [derez rez]]
   '[game.core.runs :refer [end-run make-run]]
   '[game.core.say :refer [system-msg]]
   '[game.core.servers :refer [is-remote? remote->name zone->name]]
   '[game.core.shuffling :refer [shuffle! shuffle-into-deck
                                shuffle-into-rd-effect]]
   '[game.core.tags :refer [gain-tags]]
   '[game.core.to-string :refer [card-str]]
   '[game.core.toasts :refer [toast]]
   '[game.core.update :refer [update!]]
   '[game.core.virus :refer [number-of-virus-counters]]
   '[game.macros :refer [continue-ability effect msg req wait-for]]
   '[game.utils :refer :all]
   '[jinteki.utils :refer :all])

(defcard "Mushin No Shin"
  {:on-play
   {:prompt "Choose a card to install from HQ"
    :choices {:card #(and (not (operation? %))
                          (corp? %)
                          (in-hand? %))}
    :async true
    :effect (req (wait-for (corp-install state side target "New remote" nil)
                           (let [installed-card async-result]
                             (add-prop state side installed-card :advance-counter 3 {:placed true})
                             (register-persistent-flag!
                               state side
                               installed-card :can-rez
                               (fn [state _ card]
                                 (if (same-card? card installed-card)
                                   ((constantly false) (toast state :corp "Cannot rez due to Mushin No Shin." "warning"))
                                   true)))
                             (register-turn-flag!
                               state side
                               installed-card :can-score
                               (fn [state _ card]
                                 (if (same-card? card installed-card)
                                   ((constantly false) (toast state :corp "Cannot score due to Mushin No Shin." "warning"))
                                   true)))
                             (register-events
                              state side installed-card
                              [{:event :corp-turn-begins
                                :duration :until-corp-turn-begins
                                :unregister-once-resolved true
                                :async true
                                :effect (req
                                         (clear-persistent-flag! state :corp installed-card :can-rez)
                                         (effect-completed state side eid))}])
                             (effect-completed state side eid))))}})
