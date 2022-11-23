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

(defcard "Endless EULA"
  (let [sub (end-the-run-unless-runner-pays 1)]
    (letfn [(break-fn [unbroken-subs total]
              {:async true
               :effect
               (req (if (seq unbroken-subs)
                      (wait-for (pay state :runner (make-eid state (assoc eid :source-type :subroutine)) card [:credit 1])
                                (system-msg state :runner (:msg async-result))
                                (continue-ability
                                  state side
                                  (break-fn (rest unbroken-subs) (inc total))
                                  card nil))
                      (let [msgs (when (pos? total)
                                   (str "resolves " (quantify total "unbroken subroutine")
                                        " on Endless EULA"
                                        " (\"[subroutine] "
                                                          (:label sub) "\")"))]
                        (when (pos? total)
                          (system-msg state side msgs))
                        (effect-completed state side eid))))})]
      {:subroutines [sub sub
                     sub sub
                     sub sub]
       :runner-abilities [{:req (req (<= (count (remove #(or (:broken %) (= false (:resolve %))) (:subroutines card)))
                                         (total-available-credits state :runner eid card)))
                           :async true
                           :label "Pay for all unbroken subs"
                           :effect (req (let [unbroken-subs (remove #(or (:broken %) (= false (:resolve %))) (:subroutines card))
                                              eid (assoc eid :source-type :subroutine)]
                                          (->> unbroken-subs
                                               (reduce resolve-subroutine card)
                                               (update! state side))
                                          (continue-ability
                                            state side
                                            (break-fn unbroken-subs 0)
                                            card nil)))}]})))
