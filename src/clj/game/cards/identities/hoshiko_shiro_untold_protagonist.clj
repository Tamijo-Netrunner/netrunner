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

(defcard "Hoshiko Shiro: Untold Protagonist"
  (let [flip-effect (req (update! state side (if (:flipped card)
                                               (assoc card
                                                      :flipped false
                                                      :face :front
                                                      :code (subs (:code card) 0 5)
                                                      :subtype "Natural")
                                               (assoc card
                                                      :flipped true
                                                      :face :back
                                                      :code (str (subs (:code card) 0 5) "flip")
                                                      :subtype "Digital")))
                         (update-link state))]
    {:constant-effects [(link+ (req (:flipped card)) 1)
                        {:type :gain-subtype
                         :req (req (and (same-card? card target) (:flipped card)))
                         :value "Digital"}
                        {:type :lose-subtype
                         :req (req (and (same-card? card target) (:flipped card)))
                         :value "Natural"}]
     :events [{:event :pre-first-turn
               :req (req (= side :runner))
               :effect (effect (update! (assoc card :flipped false :face :front)))}
              {:event :runner-turn-ends
               :interactive (req true)
               :async true
               :effect (req (cond
                              (and (:flipped card)
                                   (not (:accessed-cards runner-reg)))
                              (do (system-msg state :runner "flips their identity to Hoshiko Shiro: Untold Protagonist")
                                  (continue-ability state :runner {:effect flip-effect} card nil))

                              (and (not (:flipped card))
                                   (:accessed-cards runner-reg))
                              (wait-for (gain-credits state :runner 2)
                                        (system-msg state :runner "gains 2 [Credits] and flips their identity to Hoshiko Shiro: Mahou Shoujo")
                                        (continue-ability state :runner {:effect flip-effect} card nil))

                              :else
                              (effect-completed state side eid)))}
              {:event :runner-turn-begins
               :req (req (:flipped card))
               :async true
               :effect (req (wait-for (draw state :runner 1)
                                      (wait-for (lose-credits state :runner (make-eid state eid) 1)
                                                (system-msg state :runner "uses Hoshiko Shiro: Mahou Shoujo to draw 1 card and lose 1 [Credits]")
                                                (effect-completed state side eid))))}]
     :abilities [{:label "flip identity"
                  :msg "flip their identity manually"
                  :effect flip-effect}]}))
