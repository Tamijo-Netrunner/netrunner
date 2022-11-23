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

(defcard "Accelerated Beta Test"
  (letfn [(abt [titles choices]
            {:async true
             :prompt (str "The top 3 cards of R&D: " titles)
             :choices (concat (filter ice? choices) ["Done"])
             :effect (req (if (= target "Done")
                            (do (unregister-events state side card)
                                (trash-cards state side eid choices {:unpreventable true :cause-card card}))
                            (wait-for (corp-install state side target nil
                                                    {:ignore-all-cost true
                                                     :install-state :rezzed-no-cost})
                                      (let [choices (remove-once #(= target %) choices)]
                                        (cond
                                          ;; Shuffle ends the ability
                                          (get-in (get-card state card) [:special :shuffle-occurred])
                                          (do (unregister-events state side card)
                                              (trash-cards state side eid choices {:unpreventable true :cause-card card}))
                                          ;; There are still ice left
                                          (seq (filter ice? choices))
                                          (continue-ability
                                            state side (abt titles choices) card nil)
                                          ;; Trash what's left
                                          :else
                                          (do (unregister-events state side card)
                                              (trash-cards state side eid choices {:unpreventable true :cause-card card})))))))})
          (suffer [titles choices]
            {:prompt (str "The top 3 cards of R&D: " titles
                          ". None are ice. Say goodbye!")
             :choices ["I have no regrets"]
             :async true
             :effect (effect (system-msg (str "trashes " (quantify (count choices) "card")))
                             (trash-cards eid choices {:unpreventable true :cause-card card}))})]
    {:on-score
     {:interactive (req true)
      :optional
      {:prompt "Look at the top 3 cards of R&D?"
       :yes-ability
       {:async true
        :msg "look at the top 3 cards of R&D"
        :effect (req (register-events
                       state side card
                       [{:event :corp-shuffle-deck
                         :effect (effect (update! (assoc-in card [:special :shuffle-occurred] true)))}])
                  (let [choices (take 3 (:deck corp))
                        titles (str/join ", " (map :title choices))]
                    (continue-ability
                      state side
                      (if (seq (filter ice? choices))
                        (abt titles choices)
                        (suffer titles choices))
                      card nil)))}}}}))
