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

(defcard "Progenitor"
  {:abilities [{:label "Install and host a virus program"
                :req (req (empty? (:hosted card)))
                :cost [:click 1]
                :prompt "Choose a virus program"
                :choices {:card #(and (program? %)
                                      (has-subtype? % "Virus")
                                      (in-hand? %))}
                :msg (msg "install and host " (:title target))
                :async true
                :effect (effect (runner-install eid target {:host-card card :no-mu true}))}
               {:label "Host an installed virus (manual)"
                :req (req (empty? (:hosted card)))
                :prompt "Choose an installed virus program"
                :choices {:card #(and (program? %)
                                      (has-subtype? % "Virus")
                                      (installed? %))}
                :msg (msg "host " (:title target))
                :effect (effect (host card target)
                                (unregister-effects-for-card target #(= :used-mu (:type %)))
                                (update-mu))}]
   :events [{:event :pre-purge
             :effect (req (when-let [c (first (:hosted card))]
                            (update! state side (assoc-in card [:special :numpurged] (get-counters c :virus)))))}
            {:event :purge
             :req (req (pos? (get-in card [:special :numpurged] 0)))
             :effect (req (when-let [c (first (:hosted card))]
                            (add-counter state side c :virus 1)))}]})
