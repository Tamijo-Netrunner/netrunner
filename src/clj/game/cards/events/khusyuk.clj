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

(defcard "Khusyuk"
  (let [access-revealed (fn [revealed]
                          {:async true
                           :prompt "Choose a card to access"
                           :waiting-prompt true
                           :not-distinct true
                           :choices revealed
                           :req (req (not= (:max-access run) 0))
                           :effect (effect (access-card eid target))})
        select-install-cost (fn [state]
                              (let [current-values
                                    (->> (all-active-installed state :runner)
                                         (keep :cost)
                                         (remove zero?)
                                         frequencies
                                         (merge {1 0})
                                         (into (sorted-map)))]
                                {:async true
                                 :prompt "Choose an install cost from among your installed cards"
                                 ;; We don't want to generate 99 prompt buttons, so only add 99 at the end
                                 :choices (mapv str (for [x (->> current-values keys last inc (range 1) (#(concat % [99])))]
                                                      (str x " [Credit]: "
                                                           (quantify (get current-values x 0) "card"))))
                                 :effect (effect (complete-with-result
                                                   eid [(str->int (first (str/split target #" ")))
                                                        (min 6 (str->int (nth (str/split target #" ") 2)))]))}))
        access-effect
        {:async true
         :effect (req (wait-for
                        (resolve-ability state side (select-install-cost state) card nil)
                        (let [revealed (seq (take (second async-result) (:deck corp)))]
                          (system-msg state :runner (str "uses Khusyuk to choose an install cost of "
                                                         (first async-result)
                                                         " [Credit] and reveals "
                                                         (if revealed
                                                           (str "(top:) " (str/join ", " (map :title revealed))
                                                                " from the top of R&D")
                                                           "no cards")))
                          (wait-for
                            (resolve-ability
                              state side
                              (when revealed
                                {:async true
                                 :effect (effect (reveal eid revealed))})
                              card nil)
                            (wait-for
                              (resolve-ability state side (when (and revealed (not (get-only-card-to-access state)))
                                                            (access-revealed revealed))
                                               card nil)
                              (shuffle! state :corp :deck)
                              (system-msg state :runner "shuffles R&D")
                              (effect-completed state side eid))))))}]
    {:makes-run true
     :on-play {:req (req rd-runnable)
               :async true
               :effect (effect (make-run eid :rd card))}
     :events [(successful-run-replace-breach
                {:target-server :rd
                 :this-card-run true
                 :mandatory true
                 :ability access-effect})]}))
