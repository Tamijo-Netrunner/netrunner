(in-ns 'game.cards.agendas)

(require
   '[clojure.java.io :as io]
   '[clojure.set :as set]
   '[clojure.string :as str]
   '[game.core.access :refer [steal-cost-bonus]]
   '[game.core.actions :refer [score]]
   '[game.core.agendas :refer [update-all-advancement-requirements
                              update-all-agenda-points]]
   '[game.core.bad-publicity :refer [gain-bad-publicity lose-bad-publicity]]
   '[game.core.board :refer [all-active-installed all-installed
                            all-installed-runner-type get-remote-names server->zone]]
   '[game.core.card :refer [agenda? asset? can-be-advanced?
                           corp-installable-type? corp? facedown? faceup? get-agenda-points
                           get-card get-counters get-title get-zone has-subtype? ice? in-discard? in-hand?
                           in-scored? installed? operation? program? resource? rezzed? runner? upgrade?]]
   '[game.core.card-defs :refer [card-def]]
   '[game.core.damage :refer [damage damage-bonus]]
   '[game.core.def-helpers :refer [corp-recur defcard do-net-damage
                                  offer-jack-out reorder-choice]]
   '[game.core.drawing :refer [draw]]
   '[game.core.effects :refer [register-floating-effect]]
   '[game.core.eid :refer [effect-completed make-eid]]
   '[game.core.engine :refer [pay register-events resolve-ability
                             unregister-events]]
   '[game.core.events :refer [first-event? no-event? turn-events]]
   '[game.core.finding :refer [find-latest]]
   '[game.core.flags :refer [in-runner-scored? is-scored? register-run-flag!
                            register-turn-flag! when-scored? zone-locked?]]
   '[game.core.gaining :refer [gain gain-clicks gain-credits lose lose-clicks
                              lose-credits]]
   '[game.core.hand-size :refer [corp-hand-size+ runner-hand-size+]]
   '[game.core.hosting :refer [host]]
   '[game.core.ice :refer [add-extra-sub! remove-sub! update-all-ice]]
   '[game.core.initializing :refer [card-init]]
   '[game.core.installing :refer [corp-install corp-install-list
                                 corp-install-msg]]
   '[game.core.moving :refer [forfeit mill move move-zone swap-cards swap-ice
                             trash trash-cards]]
   '[game.core.optional :refer [get-autoresolve set-autoresolve]]
   '[game.core.prompts :refer [cancellable clear-wait-prompt show-wait-prompt]]
   '[game.core.props :refer [add-counter add-prop]]
   '[game.core.purging :refer [purge]]
   '[game.core.revealing :refer [reveal]]
   '[game.core.rezzing :refer [derez rez]]
   '[game.core.runs :refer [end-run jack-out-prevent]]
   '[game.core.say :refer [system-msg]]
   '[game.core.servers :refer [is-remote? target-server zone->name]]
   '[game.core.shuffling :refer [shuffle! shuffle-into-deck
                                shuffle-into-rd-effect]]
   '[game.core.tags :refer [gain-tags]]
   '[game.core.to-string :refer [card-str]]
   '[game.core.toasts :refer [toast]]
   '[game.core.trace :refer [init-trace-bonus]]
   '[game.core.update :refer [update!]]
   '[game.core.winning :refer [check-win-by-agenda]]
   '[game.macros :refer [continue-ability effect msg req wait-for]]
   '[game.utils :refer :all]
   '[jinteki.utils :refer :all])

(defcard "Divested Trust"
  {:events
   [{:event :agenda-stolen
     :async true
     :interactive (req true)
     :effect (req (if (:winner @state)
                    (effect-completed state side eid)
                    (let [card (find-latest state card)
                          stolen-agenda (find-latest state (:card context))
                          title (get-title stolen-agenda)
                          prompt (str "Forfeit Divested Trust to add " title
                                      " to HQ and gain 5 [Credits]?")
                          message (str "add " title " to HQ and gain 5 [Credits]")
                          card-side (if (in-runner-scored? state side card)
                                      :runner :corp)]
                      (continue-ability
                        state side
                        {:optional
                         {:waiting-prompt true
                          :prompt prompt
                          :yes-ability
                          {:msg message
                           :async true
                           :effect (req (wait-for (forfeit state card-side (make-eid state eid) card)
                                                  (move state side stolen-agenda :hand)
                                                  (update-all-agenda-points state side)
                                                  (gain-credits state side eid 5)))}}}
                        card nil))))}]})
