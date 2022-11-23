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

(defcard "Digital Rights Management"
  {:on-play
   {:req (req (and (< 1 (:turn @state))
                   (not (some #{:hq} (:successful-run runner-reg-last)))))
    :prompt "Choose an Agenda"
    :choices (req (conj (vec (filter agenda? (:deck corp))) "None"))
    :msg (msg (if (= "None" target)
                "shuffle R&D"
                (str "add " (:title target) " to HQ and shuffle R&D")))
    :effect (let [end-effect (req (system-msg state side "can not score agendas for the remainder of the turn")
                                  (swap! state assoc-in [:corp :register :cannot-score]
                                         (filter agenda? (all-installed state :corp)))
                                  (register-events
                                    state side card
                                    [{:event :corp-install
                                      :duration :until-corp-turn-begins
                                      :async true
                                      :req (req (agenda? (:card context)))
                                      :effect (req
                                                (register-turn-flag!
                                                  state side
                                                  (:card context) :can-score
                                                  (fn [state _ card]
                                                    (if (same-card? card (:card context))
                                                      ((constantly false) (toast state :corp "Cannot score due to Digital Rights Management." "warning"))
                                                      true)))
                                                (effect-completed state side eid))}])
                                  (effect-completed state side eid))]
              (req (wait-for
                     (resolve-ability
                       state side
                       (when-not (= "None" target)
                         {:async true
                          :effect (req (wait-for (reveal state side target)
                                                 (move state side target :hand)
                                                 (effect-completed state side eid)))})
                       card targets)
                     (shuffle! state side :deck)
                     (continue-ability
                       state side
                       {:prompt "Choose a card in HQ to install"
                        :choices {:card #(and (in-hand? %)
                                              (corp? %)
                                              (not (operation? %)))}
                        :effect (req (wait-for (resolve-ability
                                                 state side
                                                 (let [card-to-install target]
                                                   {:prompt "Choose a server"
                                                    :choices (remove #{"HQ" "R&D" "Archives"} (corp-install-list state card-to-install))
                                                    :async true
                                                    :effect (effect (corp-install eid card-to-install target nil))})
                                                 target nil)
                                               (end-effect state side eid card targets)))
                        :cancel-effect (effect (system-msg "declines to use Digital Rights Management to install a card")
                                               (end-effect eid card targets))}
                       card nil))))}})
