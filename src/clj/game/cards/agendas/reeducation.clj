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

(defcard "Reeducation"
  (letfn [(corp-final [chosen original]
            {:prompt (str "The bottom cards of R&D will be " (str/join  ", " (map :title chosen)))
             :choices ["Done" "Start over"]
             :async true
             :msg (req (let [n (count chosen)]
                         (str "add " (quantify n "card") " from HQ to the bottom of R&D and draw " (quantify n "card")
                              ". The Runner randomly adds " (quantify (min n (count (:hand runner))) "card")
                              " from their Grip to the bottom of the Stack")))
             :effect (req (let [n (count chosen)]
                            (if (= target "Done")
                              (do (doseq [c (reverse chosen)] (move state :corp c :deck))
                                  (wait-for (draw state :corp n)
                                            ; if corp chooses more cards than runner's hand, don't shuffle runner hand back into Stack
                                            (when (<= n (count (:hand runner)))
                                              (doseq [r (take n (shuffle (:hand runner)))] (move state :runner r :deck)))
                                            (effect-completed state side eid)))
                              (continue-ability state side (corp-choice original '() original) card nil))))})
          (corp-choice [remaining chosen original] ; Corp chooses cards until they press 'Done'
            {:prompt "Choose a card to move to bottom of R&D"
             :choices (conj (vec remaining) "Done")
             :async true
             :effect (req (let [chosen (cons target chosen)]
                            (if (not= target "Done")
                              (continue-ability
                                state side
                                (corp-choice (remove-once #(= target %) remaining) chosen original)
                                card nil)
                              (if (pos? (count (remove #(= % "Done") chosen)))
                                (continue-ability state side (corp-final (remove #(= % "Done") chosen) original) card nil)
                                (do (system-msg state side "does not add any cards from HQ to bottom of R&D")
                                    (effect-completed state side eid))))))})]
    {:on-score {:async true
                :waiting-prompt true
                :effect (req (let [from (get-in @state [:corp :hand])]
                               (if (pos? (count from))
                                 (continue-ability state :corp (corp-choice from '() from) card nil)
                                 (do (system-msg state side "does not add any cards from HQ to bottom of R&D")
                                     (effect-completed state side eid)))))}}))
