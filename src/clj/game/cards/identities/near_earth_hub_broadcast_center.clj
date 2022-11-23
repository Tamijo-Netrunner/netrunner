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

(defcard "Near-Earth Hub: Broadcast Center"
  {:events [{:event :server-created
             :req (req (first-event? state :corp :server-created))
             :async true
             :msg "draw 1 card"
             :effect (req
                      (if-not (some #(= % :deck) (:zone target))
                        (draw state :corp eid 1)
                        (do
                          ;; Register the draw to go off when the card is finished installing -
                          ;;  this is after the checkpoint when it should go off, but is needed to
                          ;;  fix the interaction between architect (and any future install from R&D
                          ;;  cards) and NEH, where the card would get drawn before the install,
                          ;;  fizzling it in a confusing manner. Because we only do it in this
                          ;;  special case, there should be no gameplay implications. -nbkelly, 2022
                          (register-events
                           state side
                           card
                           [{:event :corp-install
                             :interactive (req true)
                             :duration (req true)
                             :unregister-once-resolved true
                             :async true
                             :effect (effect (draw :corp eid 1))}])
                          (effect-completed state side eid))))}]})
