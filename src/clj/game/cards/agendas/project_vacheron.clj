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

(defcard "Project Vacheron"
  {:flags {:has-events-when-stolen true}
   :agendapoints-runner (req (if (or (= (first (:previous-zone card)) :discard)
                                     (zero? (get-counters card :agenda))) 3 0))
   :move-zone (req (when (and (in-scored? card)
                              (= :runner (:scored-side card))
                              (not= (first (:previous-zone card)) :discard))
                     (system-msg state side "uses Project Vacheron to host 4 agenda counters on Project Vacheron")
                     (add-counter state side (get-card state card) :agenda 4)))
   :events [{:event :runner-turn-begins
             :req (req (pos? (get-counters card :agenda)))
             :msg (msg "remove 1 agenda token from " (:title card))
             :effect (req (when (pos? (get-counters card :agenda))
                            (add-counter state side card :agenda -1))
                          (update-all-agenda-points state side)
                          (let [card (get-card state card)]
                            (when (zero? (get-counters card :agenda))
                              (let [points (get-agenda-points card)]
                                (system-msg state :runner
                                            (str "gains " (quantify points "agenda point")
                                                 " from " (:title card))))))
                          (check-win-by-agenda state side))}]})
