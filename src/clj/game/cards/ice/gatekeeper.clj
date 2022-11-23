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

(defcard "Gatekeeper"
  (let [draw-ab {:async true
                 :prompt "How many cards do you want to draw?"
                 :choices {:number (req 3)
                           :max (req 3)
                           :default (req 1)}
                 :msg (msg "draw " (quantify target "card"))
                 :effect (effect (draw eid target))}
        reveal-and-shuffle {:prompt "Reveal and shuffle up to 3 agendas into R&D"
                            :show-discard true
                            :choices {:card #(and (corp? %)
                                                  (or (in-hand? %)
                                                      (in-discard? %))
                                                  (agenda? %))
                                      :max (req 3)}
                            :async true
                            :effect (req (wait-for
                                           (reveal state side targets)
                                           (doseq [c targets]
                                             (move state :corp c :deck))
                                           (shuffle! state :corp :deck)
                                           (effect-completed state :corp eid)))
                            :cancel-effect (effect (shuffle! :deck)
                                                   (effect-completed eid))
                            :msg (msg
                                   "shuffle "
                                   (str/join
                                     " and "
                                     (filter identity
                                             [(when-let [h (->> targets
                                                                (filter in-hand?)
                                                                (map :title)
                                                                not-empty)]
                                                (str (enumerate-str h)
                                                     " from HQ"))
                                              (when-let [d (->> targets
                                                                (filter in-discard?)
                                                                (map :title)
                                                                not-empty)]
                                                (str (enumerate-str d)
                                                     " from Archives"))]))
                                   " into R&D")}
        draw-reveal-shuffle {:async true
                             :label "Draw cards, reveal and shuffle agendas"
                             :effect (req (wait-for (resolve-ability state side draw-ab card nil)
                                                    (continue-ability state side reveal-and-shuffle card nil)))}]
    {:constant-effects [(ice-strength-bonus (req (= :this-turn (:rezzed card))) 6)]
     :subroutines [draw-reveal-shuffle
                   end-the-run]}))
