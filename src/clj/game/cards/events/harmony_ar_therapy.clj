(in-ns 'game.cards.events)

(require
   '[clojure.java.io :as io]
   '[clojure.set :as set]
   '[clojure.string :as str]
   '[game.core.access :refer [access-card breach-server get-only-card-to-access
                             num-cards-to-access]]
   '[game.core.actions :refer [get-runnable-zones]]
   '[game.core.agendas :refer [update-all-agenda-points]]
   '[game.core.bad-publicity :refer [gain-bad-publicity]]
   '[game.core.board :refer [all-active-installed all-installed server->zone]]
   '[game.core.card :refer [agenda? asset? card-index condition-counter? corp?
                           event? facedown? get-card get-counters
                           get-nested-host get-title get-zone hardware? has-subtype? ice? in-discard? in-hand?
                           installed? is-type? operation? program? resource? rezzed? runner? upgrade?]]
   '[game.core.charge :refer [can-charge charge-ability charge-card]]
   '[game.core.cost-fns :refer [install-cost play-cost rez-cost]]
   '[game.core.damage :refer [damage damage-prevent]]
   '[game.core.def-helpers :refer [breach-access-bonus defcard offer-jack-out
                                  reorder-choice]]
   '[game.core.drawing :refer [draw]]
   '[game.core.effects :refer [register-floating-effect]]
   '[game.core.eid :refer [complete-with-result effect-completed make-eid
                          make-result]]
   '[game.core.engine :refer [not-used-once? pay register-events
                             resolve-ability trigger-event trigger-event-simult
                             unregister-events unregister-floating-events]]
   '[game.core.events :refer [first-event? first-run-event? run-events
                             turn-events]]
   '[game.core.expose :refer [expose]]
   '[game.core.finding :refer [find-cid find-latest]]
   '[game.core.flags :refer [any-flag-fn? can-rez? can-run-server?
                            clear-all-flags-for-card! clear-run-flag! clear-turn-flag!
                            in-corp-scored? prevent-run-on-server register-run-flag! register-turn-flag!
                            zone-locked?]]
   '[game.core.gaining :refer [gain gain-clicks gain-credits lose lose-clicks
                              lose-credits]]
   '[game.core.hand-size :refer [corp-hand-size+ hand-size]]
   '[game.core.hosting :refer [host]]
   '[game.core.ice :refer [get-strength pump pump-all-icebreakers
                          update-all-ice update-breaker-strength]]
   '[game.core.identities :refer [disable-card disable-identity enable-card
                                 enable-identity]]
   '[game.core.initializing :refer [card-init make-card]]
   '[game.core.installing :refer [install-as-condition-counter
                                 runner-can-install? runner-install]]
   '[game.core.link :refer [get-link]]
   '[game.core.mark :refer [identify-mark-ability]]
   '[game.core.memory :refer [available-mu]]
   '[game.core.moving :refer [as-agenda flip-facedown forfeit mill move
                             swap-ice trash trash-cards]]
   '[game.core.payment :refer [can-pay?]]
   '[game.core.play-instants :refer [play-instant]]
   '[game.core.prompts :refer [cancellable clear-wait-prompt]]
   '[game.core.props :refer [add-counter add-icon add-prop remove-icon]]
   '[game.core.revealing :refer [reveal]]
   '[game.core.rezzing :refer [derez get-rez-cost rez]]
   '[game.core.runs :refer [bypass-ice gain-next-run-credits make-run
                           prevent-access successful-run-replace-breach
                           total-cards-accessed]]
   '[game.core.sabotage :refer [sabotage-ability]]
   '[game.core.say :refer [system-msg]]
   '[game.core.servers :refer [is-remote? target-server zone->name
                              zones->sorted-names]]
   '[game.core.set-aside :refer [get-set-aside set-aside]]
   '[game.core.shuffling :refer [shuffle! shuffle-into-deck]]
   '[game.core.tags :refer [gain-tags lose-tags tag-prevent]]
   '[game.core.to-string :refer [card-str]]
   '[game.core.toasts :refer [toast]]
   '[game.core.update :refer [update!]]
   '[game.core.virus :refer [get-virus-counters]]
   '[game.macros :refer [continue-ability effect msg req wait-for]]
   '[game.utils :refer :all]
   '[jinteki.utils :refer :all]
   '[jinteki.validator :refer [legal?]])

(defcard "Harmony AR Therapy"
  (letfn [(choose-end [to-shuffle]
            (let [to-shuffle (sort to-shuffle)]
              {:msg (msg "shuffle " (quantify (count to-shuffle) "card") " back into the stack: " (str/join ", " to-shuffle))
               :effect (req (doseq [c-title to-shuffle]
                              (let [c (some #(when (= (:title %) c-title) %) (:discard runner))]
                                (move state side c :deck)))
                            (shuffle! state side :deck))}))
          (choose-next [to-shuffle target remaining]
            (let [remaining (if (= "Done" target)
                              remaining
                              (remove #(= % target) remaining))
                  to-shuffle (if (= "Done" target)
                               to-shuffle
                               (if target
                                 (concat to-shuffle [target])
                                 []))
                  remaining-choices (- 5 (count to-shuffle))
                  finished? (or (= "Done" target)
                                (= 0 remaining-choices)
                                (empty? remaining))]
              {:prompt (msg (if finished?
                              (str "Shuffling: " (str/join ", " to-shuffle))
                              (str "Choose up to " remaining-choices
                                   (when (not-empty to-shuffle)
                                     " more")
                                   " cards."
                                   (when (not-empty to-shuffle)
                                     (str "[br]Shuffling: " (str/join ", " to-shuffle))))))
               :async true
               :choices (req (if finished?
                               ["OK" "Start over"]
                               (concat remaining (when (not-empty to-shuffle) ["Done"]))))
               :effect (req (if finished?
                              (if (= "OK" target)
                                (continue-ability state side (choose-end to-shuffle) card nil)
                                (continue-ability state side (choose-next '() nil (distinct (map :title (:discard runner)))) card nil))
                              (continue-ability state side (choose-next to-shuffle target remaining) card nil)))}))]
    {:on-play
     {:rfg-instead-of-trashing true
      :waiting-prompt true
      :async true
      :effect (req (if (and (not (zone-locked? state :runner :discard))
                            (pos? (count (:discard runner))))
                     (continue-ability state side (choose-next '() nil (sort (distinct (map :title (:discard runner))))) card nil)
                     (do (system-msg state :runner (str "uses " (:title card) " to shuffle their Stack"))
                         (shuffle! state :runner :deck)
                         (effect-completed state side eid))))}}))
