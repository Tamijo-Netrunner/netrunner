(in-ns 'game.cards.programs)

(require
   '[clojure.java.io :as io]
   '[clojure.string :as str]
   '[game.core.access :refer [access-bonus max-access]]
   '[game.core.board :refer [all-active all-active-installed all-installed
                            card->server server->zone]]
   '[game.core.card :refer [agenda? asset? card-index corp? facedown?
                           get-advancement-requirement get-card get-counters
                           get-nested-host get-title get-zone hardware? has-subtype? ice? in-discard? in-hand?
                           installed? program? resource? rezzed? runner?]]
   '[game.core.card-defs :refer [card-def]]
   '[game.core.cost-fns :refer [all-stealth install-cost min-stealth rez-cost]]
   '[game.core.costs :refer [total-available-credits]]
   '[game.core.damage :refer [damage damage-prevent]]
   '[game.core.def-helpers :refer [breach-access-bonus defcard offer-jack-out]]
   '[game.core.drawing :refer [draw]]
   '[game.core.effects :refer [register-floating-effect
                              unregister-effects-for-card]]
   '[game.core.eid :refer [effect-completed make-eid]]
   '[game.core.engine :refer [ability-as-handler dissoc-req not-used-once? pay
                             print-msg register-events register-once
                             trigger-event trigger-event-simult unregister-events]]
   '[game.core.events :refer [first-event? first-installed-trash?
                             first-successful-run-on-server? turn-events]]
   '[game.core.expose :refer [expose]]
   '[game.core.finding :refer [find-cid]]
   '[game.core.flags :refer [can-host? card-flag? lock-zone release-zone
                            zone-locked?]]
   '[game.core.gaining :refer [gain-clicks gain-credits lose-credits]]
   '[game.core.hosting :refer [host]]
   '[game.core.ice :refer [all-subs-broken-by-card? all-subs-broken?
                          any-subs-broken-by-card? auto-icebreaker break-sub
                          break-subroutine! break-subroutines-msg breaker-strength-bonus dont-resolve-subroutine!
                          get-strength ice-strength pump pump-ice set-current-ice strength-pump
                          unbroken-subroutines-choice update-breaker-strength]]
   '[game.core.initializing :refer [ability-init card-init]]
   '[game.core.installing :refer [install-locked? runner-can-install?
                                 runner-install]]
   '[game.core.link :refer [get-link]]
   '[game.core.memory :refer [available-mu update-mu]]
   '[game.core.moving :refer [flip-facedown mill move swap-cards swap-ice trash
                             trash-prevent]]
   '[game.core.optional :refer [get-autoresolve set-autoresolve]]
   '[game.core.payment :refer [build-cost-label can-pay? cost-target cost-value]]
   '[game.core.prompts :refer [cancellable]]
   '[game.core.props :refer [add-counter add-icon remove-icon]]
   '[game.core.revealing :refer [reveal]]
   '[game.core.rezzing :refer [derez get-rez-cost rez]]
   '[game.core.runs :refer [active-encounter? bypass-ice continue
                           get-current-encounter make-run successful-run-replace-breach]]
   '[game.core.say :refer [system-msg]]
   '[game.core.servers :refer [is-central? is-remote? target-server zone->name]]
   '[game.core.shuffling :refer [shuffle!]]
   '[game.core.tags :refer [gain-tags lose-tags]]
   '[game.core.to-string :refer [card-str]]
   '[game.core.update :refer [update!]]
   '[game.core.virus :refer [get-virus-counters]]
   '[game.macros :refer [continue-ability effect msg req wait-for]]
   '[game.utils :refer :all]
   '[jinteki.utils :refer :all])

(defcard "Chisel"
  {:hosting {:card #(and (ice? %)
                         (can-host? %))}
   :constant-effects [{:type :ice-strength
                       :req (req (same-card? target (:host card)))
                       :value (req (- (get-virus-counters state card)))}]
   :events [{:event :encounter-ice
             :req (req (same-card? (:ice context) (:host card)))
             :async true
             :effect (req (if (pos? (ice-strength state side (:ice context)))
                            (do (system-msg state side "uses Chisel to place 1 virus counter on itself")
                                (add-counter state side card :virus 1)
                                (effect-completed state side eid))
                            (do (system-msg state side (str "uses Chisel to trash " (card-str state (:ice context))))
                                (trash state side eid (:ice context) {:cause-card card}))))}]})
