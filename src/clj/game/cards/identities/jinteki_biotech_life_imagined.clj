(in-ns 'game.cards.identities)

(require
   '[clojure.java.io :as io]
   '[clojure.string :as str]
   '[game.core.access :refer [access-bonus access-cost-bonus access-non-agenda]]
   '[game.core.bad-publicity :refer [gain-bad-publicity]]
   '[game.core.board :refer [all-active-installed all-installed card->server
                            get-remote-names get-remotes server->zone]]
   '[game.core.card :refer [agenda? asset? can-be-advanced?
                           corp-installable-type? corp? faceup? get-advancement-requirement
                           get-agenda-points get-card get-counters get-title get-zone hardware? has-subtype?
                           ice? in-discard? in-hand? in-play-area? installed? is-type? operation? program?
                           resource? rezzed? runner? upgrade?]]
   '[game.core.charge :refer [charge-ability]]
   '[game.core.cost-fns :refer [install-cost play-cost
                               rez-additional-cost-bonus rez-cost]]
   '[game.core.damage :refer [chosen-damage corp-can-choose-damage? damage
                             enable-corp-damage-choice]]
   '[game.core.def-helpers :refer [corp-recur defcard offer-jack-out]]
   '[game.core.drawing :refer [draw]]
   '[game.core.effects :refer [register-floating-effect]]
   '[game.core.eid :refer [effect-completed make-eid]]
   '[game.core.engine :refer [pay register-events resolve-ability trigger-event]]
   '[game.core.events :refer [event-count first-event?
                             first-successful-run-on-server? no-event? not-last-turn? turn-events]]
   '[game.core.expose :refer [expose]]
   '[game.core.finding :refer [find-latest]]
   '[game.core.flags :refer [card-flag? clear-persistent-flag!
                            enable-run-on-server prevent-run-on-server
                            register-persistent-flag! register-turn-flag! zone-locked?]]
   '[game.core.gaining :refer [gain gain-clicks gain-credits lose lose-credits]]
   '[game.core.hand-size :refer [corp-hand-size+ hand-size+]]
   '[game.core.hosting :refer [host]]
   '[game.core.ice :refer [break-sub update-all-ice update-all-icebreakers]]
   '[game.core.initializing :refer [make-card]]
   '[game.core.installing :refer [corp-install runner-install]]
   '[game.core.link :refer [link+ update-link]]
   '[game.core.mark :refer [identify-mark-ability]]
   '[game.core.memory :refer [mu+]]
   '[game.core.moving :refer [mill move swap-ice trash trash-cards]]
   '[game.core.optional :refer [get-autoresolve never? set-autoresolve]]
   '[game.core.payment :refer [can-pay? cost-name merge-costs]]
   '[game.core.pick-counters :refer [pick-virus-counters-to-spend]]
   '[game.core.play-instants :refer [play-instant]]
   '[game.core.prompts :refer [cancellable clear-wait-prompt]]
   '[game.core.props :refer [add-counter add-prop]]
   '[game.core.revealing :refer [conceal-hand reveal reveal-hand]]
   '[game.core.rezzing :refer [rez]]
   '[game.core.runs :refer [get-current-encounter make-run redirect-run
                           set-next-phase start-next-phase total-cards-accessed]]
   '[game.core.sabotage :refer [sabotage-ability]]
   '[game.core.say :refer [system-msg]]
   '[game.core.servers :refer [central->name is-central? is-remote? name-zone
                              target-server zone->name]]
   '[game.core.shuffling :refer [shuffle! shuffle-into-deck]]
   '[game.core.tags :refer [gain-tags lose-tags tag-prevent]]
   '[game.core.to-string :refer [card-str]]
   '[game.core.toasts :refer [toast]]
   '[game.core.update :refer [update!]]
   '[game.core.virus :refer [number-of-runner-virus-counters]]
   '[game.macros :refer [continue-ability effect msg req wait-for]]
   '[game.utils :refer :all]
   '[jinteki.utils :refer :all])

(defcard "Jinteki Biotech: Life Imagined"
  {:events [{:event :pre-first-turn
             :req (req (= side :corp))
             :prompt "Choose a copy of Jinteki Biotech to use this game"
             :choices ["The Brewery" "The Tank" "The Greenhouse"]
             :effect (effect (update! (assoc card :biotech-target target :face :front))
                             (system-msg (str "has chosen a copy of Jinteki Biotech for this game")))}]
   :abilities [{:label "Check chosen flip identity"
                :effect (req (case (:biotech-target card)
                               "The Brewery"
                               (toast state :corp "Flip to: The Brewery (Do 2 net damage)" "info")
                               "The Tank"
                               (toast state :corp "Flip to: The Tank (Shuffle Archives into R&D)" "info")
                               "The Greenhouse"
                               (toast state :corp "Flip to: The Greenhouse (Place 4 advancement tokens on a card)" "info")
                               ;; default case
                               (toast state :corp "No flip identity specified" "info")))}
               {:cost [:click 3]
                :req (req (not (:biotech-used card)))
                :label "Flip this identity"
                :async true
                :effect (req (let [flip (:biotech-target card)]
                               (update! state side (assoc (get-card state card) :biotech-used true))
                               (case flip
                                 "The Brewery"
                                 (do (system-msg state side "uses The Brewery to do 2 net damage")
                                     (update! state side (assoc card :code "brewery" :face :brewery))
                                     (damage state side eid :net 2 {:card card}))
                                 "The Tank"
                                 (do (system-msg state side "uses The Tank to shuffle Archives into R&D")
                                     (shuffle-into-deck state side :discard)
                                     (update! state side (assoc card :code "tank" :face :tank))
                                     (effect-completed state side eid))
                                 "The Greenhouse"
                                 (do (system-msg state side (str "uses The Greenhouse to place 4 advancement tokens "
                                                                 "on a card that can be advanced"))
                                     (update! state side (assoc card :code "greenhouse" :face :greenhouse))
                                     (continue-ability
                                       state side
                                       {:prompt "Choose a card that can be advanced"
                                        :choices {:card can-be-advanced?}
                                        :effect (effect (add-prop target :advance-counter 4 {:placed true}))}
                                       card nil))
                                 (toast state :corp (str "Unknown Jinteki Biotech: Life Imagined card: " flip) "error"))))}]})
