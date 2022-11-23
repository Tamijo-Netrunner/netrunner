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

(defcard "Apocalypse"
  (let [corp-trash {:async true
                    :effect (req (let [ai (all-installed state :corp)
                                       onhost (filter #(= '(:onhost) (:zone %)) ai)
                                       unhosted (->> ai
                                                     (remove #(= '(:onhost) (:zone %)))
                                                     (sort-by #(vec (:zone %)))
                                                     (reverse))
                                       allcorp (concat onhost unhosted)]
                                   (trash-cards state :runner eid allcorp {:cause-card card})))}
        runner-facedown {:effect (req (let [installedcards (all-active-installed state :runner)
                                            ishosted (fn [c] (= '(:onhost) (get c :zone)))
                                            hostedcards (filter ishosted installedcards)
                                            nonhostedcards (remove ishosted installedcards)]
                                        (doseq [oc hostedcards
                                                :let [c (get-card state oc)]
                                                :when (not (condition-counter? c))]
                                          (flip-facedown state side c))
                                        (doseq [oc nonhostedcards
                                                :let [c (get-card state oc)]]
                                          (flip-facedown state side c))))}]
    {:on-play {:req (req (and (some #{:hq} (:successful-run runner-reg))
                              (some #{:rd} (:successful-run runner-reg))
                              (some #{:archives} (:successful-run runner-reg))))
               :async true
               ;; trash cards from right to left
               ;; otherwise, auto-killing servers would move the cards to the next server
               ;; so they could no longer be trashed in the same loop
               :msg "trash all installed Corp cards and turn all installed Runner cards facedown"
               :effect (req (wait-for
                              (resolve-ability state side corp-trash card nil)
                              (continue-ability state side runner-facedown card nil)))}}))
