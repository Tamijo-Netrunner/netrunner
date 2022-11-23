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

(defcard "Light the Fire!"
  (letfn [(eligible? [_state card server]
            (let [zone (:zone card)] (and (some #{:content} zone) (some #{server} zone))))
          (select-targets [state server]
            (filter #(eligible? state % server) (all-installed state :corp)))
          (disable-server [state _side server]
            (doseq [c (select-targets state server)]
              (disable-card state :corp c)))
          (enable-server [state _side server]
            (doseq [c (select-targets state server)]
              (enable-card state :corp c)))]
    (let [successful-run-trigger {:event :successful-run
                                  :duration :end-of-run
                                  :async true
                                  :req (req (is-remote? (:server run)))
                                  :effect (effect (trash-cards eid (:content run-server)))
                                  :msg "trash all cards in the server for no cost"}
          pre-redirect-trigger {:event :pre-redirect-server
                                :duration :end-of-run
                                :effect (effect (enable-server (first target))
                                                (disable-server (second (second targets))))}
          ;post-redirect-trigger {:event :redirect-server
          ;                       :duration :end-of-run
          ;                       :async true
          ;                       :effect (effect (disable-server (first (:server run)))
          ;                                       (effect-completed eid))}
          corp-install-trigger {:event :corp-install
                                :duration :end-of-run
                                :effect (req (disable-server state side (first (:server run))))}
          swap-trigger {:event :swap
                        :duration :end-of-run
                        :effect (req (let [first-card (first targets)
                                           second-card (second targets)
                                           server (first (:server run))]
                                       ;; disable cards that have moved into the server
                                       (when (and (some #{:content} (:zone first-card))
                                                  (some #{server} (:zone first-card)))
                                         (disable-card state :corp first-card))
                                       (when (and (some #{:content} (:zone second-card))
                                                  (some #{server} (:zone second-card)))
                                         (disable-card state :corp second-card))
                                       ;; enable cards that have left the server
                                       (when (and (some #{:content} (:zone first-card))
                                                  (not (some #{server} (:zone first-card)))
                                                  (some #{server} (:zone second-card)))
                                         (enable-card state :corp first-card))
                                       (when (and (some #{:content} (:zone second-card))
                                                  (not (some #{server} (:zone second-card)))
                                                  (some #{server} (:zone first-card)))
                                         (enable-card state :corp second-card))))}
          run-end-trigger {:event :run-ends
                           :duration :end-of-run
                           :effect (effect (enable-server (first (:server target))))}]
      {:abilities [{:label "Run a remote server"
                    :cost [:trash-can :click 1 :brain 1]
                    :prompt "Choose a remote server"
                    :choices (req (cancellable (filter #(can-run-server? state %) remotes)))
                    :msg (msg "make a run on " target " during which cards in the root of the attacked server lose all abilities")
                    :makes-run true
                    :async true
                    :effect (effect (register-events card [successful-run-trigger
                                                           run-end-trigger
                                                           pre-redirect-trigger
                                                           ;post-redirect-trigger
                                                           corp-install-trigger
                                                           swap-trigger])
                                    (make-run eid target card)
                                    (disable-server (second (server->zone state target))))}]})))
