(in-ns 'game.cards.ice)

(require
   '[clojure.java.io :as io]
   '[clojure.string :as str]
   '[cond-plus.core :refer [cond+]]
   '[game.core.access :refer [access-bonus access-card breach-server max-access]]
   '[game.core.bad-publicity :refer [gain-bad-publicity]]
   '[game.core.board :refer [all-active-installed all-installed card->server
                            get-all-cards get-all-installed server->zone]]
   '[game.core.card :refer [active? agenda? asset? can-be-advanced? card-index
                           corp? faceup? get-card get-counters get-zone
                           hardware? has-subtype? ice? in-discard? in-hand? installed? is-type? operation?
                           program? protecting-a-central? protecting-archives? protecting-hq? protecting-rd?
                           resource? rezzed? runner?]]
   '[game.core.card-defs :refer [card-def]]
   '[game.core.costs :refer [total-available-credits]]
   '[game.core.damage :refer [damage]]
   '[game.core.def-helpers :refer [combine-abilities corp-recur defcard
                                  do-brain-damage do-net-damage offer-jack-out
                                  reorder-choice]]
   '[game.core.drawing :refer [draw]]
   '[game.core.effects :refer [get-effects register-floating-effect
                              unregister-constant-effects]]
   '[game.core.eid :refer [complete-with-result effect-completed make-eid]]
   '[game.core.engine :refer [gather-events pay register-events resolve-ability
                             trigger-event unregister-events]]
   '[game.core.finding :refer [find-cid]]
   '[game.core.flags :refer [can-rez? card-flag? prevent-draw prevent-jack-out
                            register-run-flag! register-turn-flag!
                            zone-locked?]]
   '[game.core.gaining :refer [gain-credits lose-clicks lose-credits]]
   '[game.core.hand-size :refer [hand-size]]
   '[game.core.hosting :refer [host]]
   '[game.core.ice :refer [add-sub add-sub! break-sub ice-strength-bonus
                          remove-sub! remove-subs! resolve-subroutine
                          set-current-ice unbroken-subroutines-choice update-all-ice update-all-icebreakers
                          update-ice-strength]]
   '[game.core.initializing :refer [card-init]]
   '[game.core.installing :refer [corp-install corp-install-list
                                 corp-install-msg]]
   '[game.core.memory :refer [available-mu]]
   '[game.core.moving :refer [as-agenda mill move swap-ice swap-installed trash
                             trash-cards]]
   '[game.core.optional :refer [get-autoresolve set-autoresolve]]
   '[game.core.payment :refer [can-pay?]]
   '[game.core.prompts :refer [cancellable clear-wait-prompt]]
   '[game.core.props :refer [add-counter add-prop]]
   '[game.core.purging :refer [purge]]
   '[game.core.revealing :refer [reveal]]
   '[game.core.rezzing :refer [derez get-rez-cost rez]]
   '[game.core.runs :refer [bypass-ice encounter-ends end-run
                           force-ice-encounter get-current-encounter prevent-access
                           redirect-run set-next-phase]]
   '[game.core.say :refer [system-msg]]
   '[game.core.servers :refer [central->name protecting-same-server?
                              target-server zone->name]]
   '[game.core.shuffling :refer [shuffle!]]
   '[game.core.subtypes :refer [update-all-subtypes]]
   '[game.core.tags :refer [gain-tags lose-tags]]
   '[game.core.to-string :refer [card-str]]
   '[game.core.toasts :refer [toast]]
   '[game.core.update :refer [update!]]
   '[game.macros :refer [continue-ability effect msg req wait-for]]
   '[game.utils :refer :all]
   '[jinteki.utils :refer :all])

(defcard "Kamali 1.0"
  (letfn [(better-name [kind] (if (= "hardware" kind) "piece of hardware" kind))
          (runner-trash [kind]
            {:prompt (str "Choose an installed " (better-name kind) " to trash")
             :label (str "Trash an installed " (better-name kind))
             :msg (msg "trash " (:title target))
             :async true
             :choices {:card #(and (installed? %)
                                   (is-type? % (capitalize kind)))}
             :cancel-effect (effect (system-msg (str "fails to trash an installed " (better-name kind)))
                                    (effect-completed eid))
             :effect (effect (trash eid target {:cause :subroutine}))})
          (sub-map [kind]
            {:player :runner
             :async true
             :waiting-prompt true
             :prompt "Choose one"
             :choices ["Take 1 brain damage" (str "Trash an installed " (better-name kind))]
             :msg (msg (if (= target "Take 1 brain damage")
                         "do 1 brain damage"
                         (str "force the runner to " (decapitalize target))))
             :effect (req (if (= target "Take 1 brain damage")
                            (damage state :runner eid :brain 1 {:card card})
                            (continue-ability state :runner (runner-trash kind) card nil)))})
          (brain-trash [kind]
            {:label (str "Force the Runner to take 1 brain damage or trash an installed " (better-name kind))
             :async true
             :effect (req (wait-for (resolve-ability state side (sub-map kind) card nil)
                                    (clear-wait-prompt state :corp)))})]
    {:subroutines [(brain-trash "resource")
                   (brain-trash "hardware")
                   (brain-trash "program")]
     :runner-abilities [(bioroid-break 1 1)]}))
