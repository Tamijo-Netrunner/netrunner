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

(defcard "Customized Secretary"
  (letfn [(custsec-host [cards]
            (if (empty? (filter program? cards))
              {:msg "shuffle the stack"
               :effect (effect (shuffle! :deck))}
              {:prompt "Choose a program to host"
               :choices (concat (filterv program? cards) ["Done"])
               :async true
               :effect (req (if (= target "Done")
                              (do (shuffle! state side :deck)
                                  (system-msg state side "shuffles the stack")
                                  (effect-completed state side eid))
                              (do (host state side (get-card state card) target)
                                  (system-msg state side (str "hosts " (:title target) " on Customized Secretary"))
                                  (continue-ability state side (custsec-host (remove-once #(= % target) cards))
                                                    card nil))))}))]
    {:on-install {:async true
                  :interactive (req (some #(card-flag? % :runner-install-draw true) (all-active state :runner)))
                  :msg (msg "reveal the top cards of the stack: " (str/join ", " (map :title (take 5 (:deck runner)))))
                  :waiting-prompt true
                  :effect (req (let [from (take 5 (:deck runner))]
                                 (wait-for (reveal state side from)
                                           (continue-ability state side (custsec-host from) card nil))))}
     :abilities [{:cost [:click 1]
                  :keep-menu-open :while-clicks-left
                  :label "Install a hosted program"
                  :prompt "Choose a program to install"
                  :choices (req (cancellable (filter #(can-pay? state side (assoc eid :source card :source-type :runner-install)
                                                                % nil [:credit (install-cost state side %)])
                                                     (:hosted card))))
                  :msg (msg "install " (:title target))
                  :async true
                  :effect (effect (runner-install (assoc eid :source card :source-type :runner-install) target nil))}]}))
