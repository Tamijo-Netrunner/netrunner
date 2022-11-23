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

(defcard "Ob Superheavy Logistics: Extract. Export. Excel."
  ;; note - we ensure the card can be installed (asset/upgrade/ice) - condition counters (like patch)
  ;;   are very questionable, and somebody on rules would need to say something to convince me they
  ;;   would be valid targets --nbkelly
  ;; If you install a card with an additional cost, you can (or should be able to) refuse to pay it
  ;;  and have the card not be rezzed (additionally, you wouldn't need to reveal it)
  ;; A lot of abilities/cards will need to be adjusted (most of them don't have a cause when they
  ;;  call the (trash-cards) function - this makes it tough to tell who/what trashed the cards.)
  ;;  to update these cards, just add {:cause card} into the keys pass with (trash-card)
  ;;  At the moment, the source (or cause) of the trash must be a corp-card, a subroutine,
  ;;  or it must be an ability cost.
  (letfn [(resolve-install []
            (req
              (shuffle! state side :deck)
              (system-msg state side "shuffles R&D")
              ;; if it has an additional cost, the rez needs to be optional
              (if (= "No install" target)
                (effect-completed state side eid)
                (let [add-costs (rez-additional-cost-bonus state side target)
                      inst-target target]
                  (cond
                    (and (pos? (count add-costs))
                         (can-pay? state side (:title inst-target) add-costs))
                    (continue-ability
                      state side
                      {:optional
                       {:prompt (str "Rez " (:title inst-target) ", paying additional costs?")
                        :yes-ability {:msg (msg "rez " (:title inst-target)
                                                ", paying additional costs")
                                      :async true
                                      :effect (req (corp-install state side eid inst-target nil
                                                                 {:ignore-all-cost true
                                                                  :no-warning true
                                                                  :install-state :rezzed-no-rez-cost}))}
                        :no-ability {:msg "install a card ignoring all credit costs"
                                     :async true
                                     :effect (req (corp-install state side eid inst-target nil
                                                                {:ignore-all-cost true}))}}}
                      card nil)
                    ;; It might be worth having a fake prompt here - at the very least, this prevents
                    ;; the corp from accidentally revealing that they can't pay for the card they select
                    (pos? (count add-costs))
                    (continue-ability
                      state side
                      {:msg "install a card without paying additional costs to rez"
                       :async true
                       :effect (req (corp-install state side eid inst-target nil
                                                  {:ignore-all-cost true
                                                   :no-warning true}))}
                      card nil)
                    :else
                    (wait-for (reveal state side inst-target)
                              (corp-install state side eid (get-card state inst-target) nil
                                            {:ignore-all-cost true
                                             :no-warning true
                                             :install-state :rezzed-no-rez-cost})))))))
          ;; Identify that the card wasn't just dragged to the discard, and that it was trashed
          ;; by the corporation.
          ;; This requires that any (trash-cards ..) or (trash ..) fns use {:source card}
          ;; to be compatable. Updating those functions is quite quick, just make sure it actually
          ;; is the corporation doing it.
          (trash-cause [eid target]
            (let [cause (:cause target)
                  cause-card (:cause-card target)]
              (or
                (corp? (:source eid))
                (= :ability-cost cause)
                (= :subroutine cause)
                (and (corp? cause-card) (not= cause :opponent-trashes))
                (and (runner? cause-card) (= cause :forced-to-trash)))))
          ;; prompts to install an x-cost card (handles validation)
          (ob-ability [target-cost]
            {:optional
             {:prompt (if (>= target-cost 0)
                        (str "Install a " target-cost "-cost card from your deck?")
                        (str "Shuffle your deck (search for a " target-cost "-cost card from your deck?)"))
              :once :per-turn
              :yes-ability
              {:msg (msg "search R&D for a " (str target-cost) "-cost card")
               :async true
               :effect (req (if (>= target-cost 0)
                              (continue-ability
                                state side
                                {:prompt "Choose a card to install and rez"
                                 :choices (req (conj (filter #(and (= target-cost (:cost %))
                                                                   (or (asset? %)
                                                                       (upgrade? %)
                                                                       (ice? %)))
                                                             (vec (sort-by :title (:deck corp))))
                                                     "No install"))
                                 :async true
                                 :effect (resolve-install)}
                                card nil)
                              (continue-ability
                                state side
                                {:msg "shuffle R&D"
                                 :effect (effect (shuffle! :corp :deck))}
                                card nil)))}
              :no-ability
              {:effect (effect (system-msg "declines to use Ob Superheavy Logistics: Extract. Export. Excel."))}}})]
    {:events [{:event :corp-trash
               :req (req (and
                           (installed? (:card context))
                           (rezzed? (:card context))
                           (trash-cause eid target)
                           (not (used-this-turn? (:cid card) state))))
               :async true
               :interactive (req true)
               :waiting "Corp to make a decision"
               :effect (req (let [target-cost (dec (:cost (:card target)))]
                              (continue-ability
                                state side
                                (ob-ability target-cost)
                                card nil)))}]}))
