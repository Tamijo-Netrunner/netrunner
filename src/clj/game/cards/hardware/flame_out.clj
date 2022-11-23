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

(defcard "Flame-out"
  (let [register-flame-effect
        (fn [state card]
          (update! state :runner (assoc-in (get-card state card) [:special :flame-out-trigger] true)))
        maybe-turn-end {:async true
                        :req (req (:flame-out-trigger (:special (get-card state card))))
                        :effect (req (update! state side (assoc-in (get-card state card) [:special :flame-out-trigger] nil))
                                     (if-let [hosted (first (:hosted card))]
                                       (do (system-msg state :runner (str "trashes " (:title hosted) " from Flame-out"))
                                           (trash state side eid hosted {:cause-card card}))
                                       (effect-completed state side eid)))}]
    {:implementation "Credit usage restriction not enforced"
     :data {:counter {:credit 9}}
     :abilities [{:label "Take 1 hosted [Credits]"
                  :req (req (and (not-empty (:hosted card))
                                 (pos? (get-counters card :credit))))
                  :async true
                  :effect (req (add-counter state side card :credit -1)
                               (system-msg state :runner "takes 1 hosted [Credits] from Flame-out")
                               (register-flame-effect state card)
                               (gain-credits state :runner eid 1))}
                 {:label "Take all hosted [Credits]"
                  :req (req (and (not-empty (:hosted card))
                                 (pos? (get-counters card :credit))))
                  :async true
                  :effect (req (let [credits (get-counters card :credit)]
                                 (update! state :runner (dissoc-in card [:counter :credit]))
                                 (system-msg state :runner (str "takes " credits " hosted [Credits] from Flame-out"))
                                 (register-flame-effect state card)
                                 (gain-credits state :runner eid credits)))}
                 {:label "Install and host a program"
                  :req (req (empty? (:hosted card)))
                  :cost [:click 1]
                  :prompt "Choose a program in the grip"
                  :choices {:card #(and (program? %)
                                        (in-hand? %))}
                  :async true
                  :effect (effect (update! (assoc-in (get-card state card) [:special :flame-out] (:cid target)))
                                  (runner-install eid target {:host-card card}))}
                 {:label "Host an installed program (manual)"
                  :req (req (empty? (:hosted card)))
                  :prompt "Choose an installed program"
                  :choices {:card #(and (program? %)
                                        (installed? %))}
                  :msg (msg "host " (:title target))
                  :effect (req (->> target
                                    (get-card state)
                                    (host state side card))
                               (update! state side (assoc-in (get-card state card) [:special :flame-out] (:cid target))))}]
     :events [{:event :card-moved
               :req (req (= (:cid target) (get-in (get-card state card) [:special :flame-out])))
               :effect (effect (update! (dissoc-in card [:special :flame-out])))}
              (assoc maybe-turn-end :event :runner-turn-ends)
              (assoc maybe-turn-end :event :corp-turn-ends)]
     :interactions {:pay-credits {:req (req (and (= :ability (:source-type eid))
                                                 (same-card? card (:host target))
                                                 (pos? (get-counters card :credit))))
                                  :custom-amount 1
                                  :custom (req (add-counter state side card :credit -1)
                                               (register-flame-effect state card)
                                               (effect-completed state side (make-result eid 1)))
                                  :type :custom}}}))
