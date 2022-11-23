(in-ns 'game.cards.hardware)

(require
   '[clojure.java.io :as io]
   '[clojure.set :as set]
   '[clojure.string :as str]
   '[game.core.access :refer [access-bonus access-card breach-server
                             get-only-card-to-access]]
   '[game.core.actions :refer [play-ability]]
   '[game.core.board :refer [all-active all-active-installed all-installed]]
   '[game.core.card :refer [corp? event? facedown? faceup? get-card
                           get-counters get-zone hardware? has-subtype? ice? in-deck?
                           in-discard? in-hand? installed? program? resource? rezzed? runner? virus-program?]]
   '[game.core.card-defs :refer [card-def]]
   '[game.core.cost-fns :refer [all-stealth install-cost
                               rez-additional-cost-bonus rez-cost trash-cost]]
   '[game.core.damage :refer [chosen-damage damage damage-prevent
                             enable-runner-damage-choice runner-can-choose-damage?]]
   '[game.core.def-helpers :refer [breach-access-bonus defcard offer-jack-out
                                  reorder-choice trash-on-empty]]
   '[game.core.drawing :refer [draw]]
   '[game.core.effects :refer [register-floating-effect
                              unregister-effects-for-card unregister-floating-effects]]
   '[game.core.eid :refer [effect-completed make-eid make-result]]
   '[game.core.engine :refer [can-trigger? register-events register-once
                             resolve-ability trigger-event
                             unregister-floating-events]]
   '[game.core.events :refer [event-count first-event? first-trash? no-event?
                             run-events]]
   '[game.core.expose :refer [expose]]
   '[game.core.finding :refer [find-card]]
   '[game.core.flags :refer [card-flag? in-corp-scored? register-run-flag!
                            zone-locked?]]
   '[game.core.gaining :refer [gain-clicks gain-credits lose-clicks
                              lose-credits]]
   '[game.core.hand-size :refer [hand-size runner-hand-size+]]
   '[game.core.hosting :refer [host]]
   '[game.core.ice :refer [all-subs-broken? break-sub pump reset-all-ice
                          update-all-ice update-all-icebreakers
                          update-breaker-strength]]
   '[game.core.installing :refer [install-locked? runner-can-install?
                                 runner-can-pay-and-install? runner-install]]
   '[game.core.link :refer [get-link link+]]
   '[game.core.mark :refer [identify-mark-ability]]
   '[game.core.memory :refer [caissa-mu+ mu+ update-mu virus-mu+]]
   '[game.core.moving :refer [mill move swap-agendas trash trash-cards]]
   '[game.core.optional :refer [get-autoresolve never? set-autoresolve]]
   '[game.core.payment :refer [build-cost-string can-pay? cost-value]]
   '[game.core.play-instants :refer [play-instant]]
   '[game.core.prompts :refer [cancellable clear-wait-prompt]]
   '[game.core.props :refer [add-counter add-icon remove-icon]]
   '[game.core.revealing :refer [reveal]]
   '[game.core.rezzing :refer [derez rez]]
   '[game.core.runs :refer [bypass-ice end-run end-run-prevent
                           get-current-encounter jack-out make-run
                           successful-run-replace-breach total-cards-accessed]]
   '[game.core.sabotage :refer [sabotage-ability]]
   '[game.core.say :refer [system-msg]]
   '[game.core.servers :refer [target-server]]
   '[game.core.set-aside :refer [get-set-aside set-aside]]
   '[game.core.shuffling :refer [shuffle!]]
   '[game.core.tags :refer [gain-tags lose-tags tag-prevent]]
   '[game.core.to-string :refer [card-str]]
   '[game.core.toasts :refer [toast]]
   '[game.core.update :refer [update!]]
   '[game.core.virus :refer [count-virus-programs number-of-virus-counters]]
   '[game.macros :refer [continue-ability effect msg req wait-for]]
   '[game.utils :refer :all]
   '[jinteki.utils :refer :all])

(defcard "Gachapon"
  (letfn [(shuffle-end [remove-from-game shuffle-back]
            {:msg (msg "shuffle " (str/join ", " (map :title shuffle-back)) " into the stack"
                       " and remove " (str/join ", " (map :title remove-from-game)) " from the game")
             :effect (req
                       (doseq [c remove-from-game]
                         (move state side c :rfg))
                       (doseq [c shuffle-back]
                         (move state side c :deck))
                       (shuffle! state side :deck))})
          (shuffle-next [set-aside-cards target to-shuffle]
            (let [set-aside-cards (remove-once #(= % target) set-aside-cards)
                  to-shuffle (if target
                               (concat to-shuffle [target])
                               [])
                  finished? (or (= 3 (count to-shuffle))
                                (empty? set-aside-cards))]
              {:prompt (msg (if finished?
                              (str "Removing: " (if (not-empty set-aside-cards)
                                                  (str/join ", " (map :title set-aside-cards))
                                                  "nothing")
                                   "[br]Shuffling: " (if (not-empty to-shuffle)
                                                       (str/join ", " (map :title to-shuffle))
                                                       "nothing"))
                              (str "Choose " (- 3 (count to-shuffle)) " more cards to shuffle back."
                                   (when (not-empty to-shuffle)
                                     (str "[br]Currently shuffling back: " (str/join ", " (map :title to-shuffle)))))))
               :async true
               :not-distinct true ; show cards separately
               :choices (req (if finished?
                               ["Done" "Start over"]
                               (seq set-aside-cards)))
               :effect (req (if finished?
                              (if (= "Done" target)
                                (continue-ability state side
                                                  (shuffle-end set-aside-cards to-shuffle)
                                                  card nil)
                                (continue-ability state side
                                                  (shuffle-next (sort-by :title (concat set-aside-cards to-shuffle)) nil nil)
                                                  card nil))
                              (continue-ability state side
                                                (shuffle-next set-aside-cards target to-shuffle)
                                                card nil)))}))]
    {:abilities [{:label "Install a card from the top of the stack"
                  :cost [:trash-can]
                  :msg "install a card from the top of the stack"
                  :async true
                  :waiting-prompt true
                  :effect (req (set-aside state side eid (take 6 (:deck runner)))
                               (let [set-aside-cards (sort-by :title (get-set-aside state side eid))]
                                 (wait-for (resolve-ability state side
                                                            {:async true
                                                             :prompt (msg "The set aside cards are: " (str/join ", " (map :title set-aside-cards)))
                                                             :choices ["OK"]}
                                                            card nil)
                                           (continue-ability
                                             state side
                                             {:prompt "Choose a card to install"
                                              :async true
                                              :choices (req (concat
                                                              (filter #(and (or (program? %)
                                                                                (and (resource? %)
                                                                                     (has-subtype? % "Virtual")))
                                                                            (can-pay? state side
                                                                                      (assoc eid :source card :source-type :runner-install)
                                                                                      % nil [:credit (install-cost state side % {:cost-bonus -2})]))
                                                                      set-aside-cards)
                                                              ["No install"]))
                                              :cancel-effect (effect (continue-ability (shuffle-next set-aside-cards nil nil) card nil))
                                              :effect (req (if (= "No install" target)
                                                             (continue-ability state side (shuffle-next set-aside-cards nil nil) card nil)
                                                             (let [set-aside-cards (remove-once #(= % target) set-aside-cards)
                                                                   new-eid (assoc eid :source card :source-type :runner-install)]
                                                               (wait-for (runner-install state side new-eid target {:cost-bonus -2})
                                                                         (continue-ability state side (shuffle-next set-aside-cards nil nil) card nil)))))}
                                             card nil))))}]}))
