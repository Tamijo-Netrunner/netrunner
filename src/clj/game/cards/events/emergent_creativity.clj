(in-ns 'game.cards.events)

(require
   '[clojure.java.io :as io]
   '[clojure.set :as set]
   '[clojure.string :as str]
   '[game.core.access :refer [access-card breach-server get-only-card-to-access
                             num-cards-to-access]]
   '[game.core.actions :refer [get-runnable-zones]]
   '[game.core.agendas :refer [update-all-agenda-points]]
   '[game.core.bad-publicity :refer [gain-bad-publicity]]
   '[game.core.board :refer [all-active-installed all-installed server->zone]]
   '[game.core.card :refer [agenda? asset? card-index condition-counter? corp?
                           event? facedown? get-card get-counters
                           get-nested-host get-title get-zone hardware? has-subtype? ice? in-discard? in-hand?
                           installed? is-type? operation? program? resource? rezzed? runner? upgrade?]]
   '[game.core.charge :refer [can-charge charge-ability charge-card]]
   '[game.core.cost-fns :refer [install-cost play-cost rez-cost]]
   '[game.core.damage :refer [damage damage-prevent]]
   '[game.core.def-helpers :refer [breach-access-bonus defcard offer-jack-out
                                  reorder-choice]]
   '[game.core.drawing :refer [draw]]
   '[game.core.effects :refer [register-floating-effect]]
   '[game.core.eid :refer [complete-with-result effect-completed make-eid
                          make-result]]
   '[game.core.engine :refer [not-used-once? pay register-events
                             resolve-ability trigger-event trigger-event-simult
                             unregister-events unregister-floating-events]]
   '[game.core.events :refer [first-event? first-run-event? run-events
                             turn-events]]
   '[game.core.expose :refer [expose]]
   '[game.core.finding :refer [find-cid find-latest]]
   '[game.core.flags :refer [any-flag-fn? can-rez? can-run-server?
                            clear-all-flags-for-card! clear-run-flag! clear-turn-flag!
                            in-corp-scored? prevent-run-on-server register-run-flag! register-turn-flag!
                            zone-locked?]]
   '[game.core.gaining :refer [gain gain-clicks gain-credits lose lose-clicks
                              lose-credits]]
   '[game.core.hand-size :refer [corp-hand-size+ hand-size]]
   '[game.core.hosting :refer [host]]
   '[game.core.ice :refer [get-strength pump pump-all-icebreakers
                          update-all-ice update-breaker-strength]]
   '[game.core.identities :refer [disable-card disable-identity enable-card
                                 enable-identity]]
   '[game.core.initializing :refer [card-init make-card]]
   '[game.core.installing :refer [install-as-condition-counter
                                 runner-can-install? runner-install]]
   '[game.core.link :refer [get-link]]
   '[game.core.mark :refer [identify-mark-ability]]
   '[game.core.memory :refer [available-mu]]
   '[game.core.moving :refer [as-agenda flip-facedown forfeit mill move
                             swap-ice trash trash-cards]]
   '[game.core.payment :refer [can-pay?]]
   '[game.core.play-instants :refer [play-instant]]
   '[game.core.prompts :refer [cancellable clear-wait-prompt]]
   '[game.core.props :refer [add-counter add-icon add-prop remove-icon]]
   '[game.core.revealing :refer [reveal]]
   '[game.core.rezzing :refer [derez get-rez-cost rez]]
   '[game.core.runs :refer [bypass-ice gain-next-run-credits make-run
                           prevent-access successful-run-replace-breach
                           total-cards-accessed]]
   '[game.core.sabotage :refer [sabotage-ability]]
   '[game.core.say :refer [system-msg]]
   '[game.core.servers :refer [is-remote? target-server zone->name
                              zones->sorted-names]]
   '[game.core.set-aside :refer [get-set-aside set-aside]]
   '[game.core.shuffling :refer [shuffle! shuffle-into-deck]]
   '[game.core.tags :refer [gain-tags lose-tags tag-prevent]]
   '[game.core.to-string :refer [card-str]]
   '[game.core.toasts :refer [toast]]
   '[game.core.update :refer [update!]]
   '[game.core.virus :refer [get-virus-counters]]
   '[game.macros :refer [continue-ability effect msg req wait-for]]
   '[game.utils :refer :all]
   '[jinteki.utils :refer :all]
   '[jinteki.validator :refer [legal?]])

(defcard "Emergent Creativity"
  (letfn [(ec [trash-cost to-trash]
            {:async true
             :prompt "Choose a piece of hardware or program to install"
             :msg (msg "trash " (if (empty? to-trash) "no cards" (str/join ", " (map :title to-trash)))
                       " and install " (:title target)
                       " lowering the cost by " trash-cost)
             :choices (req (cancellable (filter #(and (or (program? %)
                                                          (hardware? %))
                                                      (can-pay? state side (assoc eid :source card :source-type :runner-install) % nil
                                                                [:credit (install-cost state side % {:cost-bonus (- trash-cost)})]))
                                                (:deck runner)) :sorted))
             :effect (req (trigger-event state side :searched-stack nil)
                          (shuffle! state side :deck)
                          (runner-install state side (assoc eid :source card :source-type :runner-install)
                                          target {:cost-bonus (- trash-cost)}))})]
    {:on-play
     {:prompt "Choose pieces of hardware and/or programs to trash from your Grip"
      :choices {:card #(and (or (hardware? %)
                                (program? %))
                         (in-hand? %))
                :max (req (count (:hand runner)))}
      :cancel-effect (effect (continue-ability (ec 0 []) card nil))
      :async true
      :effect (req (let [trash-cost (reduce + (keep :cost targets))
                         to-trash targets]
                     (wait-for (trash-cards state side to-trash {:unpreventable true :cause-card card})
                               (continue-ability state side (ec trash-cost to-trash) card nil))))}}))
