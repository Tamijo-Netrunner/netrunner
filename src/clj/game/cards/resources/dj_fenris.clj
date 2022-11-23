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

(defcard "DJ Fenris"
  (let [is-draft-id? #(str/starts-with? (:code %) "00")
        sorted-id-list (fn [runner format] (->> (server-cards)
                                                (filter #(and (identity? %)
                                                              (has-subtype? % "G-mod")
                                                              (not= (-> runner :identity :faction)
                                                                    (:faction %))
                                                              (not (is-draft-id? %))
                                                              (or (= :casual format)
                                                                  (legal? format :legal %))))
                                                (sort-by :title)))
        fenris-effect {:async true
                       :waiting-prompt true
                       :prompt "Choose a g-mod identity to host"
                       :choices (req (sorted-id-list runner (:format @state)))
                       :msg (msg "host " (:title target))
                       :effect (req (let [card (assoc-host-zones card)
                                          ;; Work around for get-card and update!
                                          c (assoc target :type "Fake-Identity")
                                          c (make-card c)
                                          c (assoc c
                                                   :host (dissoc card :hosted)
                                                   :zone [:onhost]
                                                   ;; semi hack to get deactivate to work
                                                   :installed true)]
                                      ;; Manually host id on card
                                      (update! state side (assoc card :hosted [c]))
                                      (card-init state :runner c)
                                      ;; Clean-up
                                      (effect-completed state side eid)))}]
    {:on-install {:async true
                  :effect (effect (continue-ability fenris-effect card nil))}
     ;; Handle Dr. Lovegood / Malia
     :disable {:effect (req (doseq [hosted (:hosted card)]
                              (disable-card state side hosted)))}
     :reactivate {:effect (req (doseq [hosted (:hosted card)]
                                 (enable-card state side hosted)))}}))
