(in-ns 'game.cards.assets)

(require
   '[clojure.java.io :as io]
   '[clojure.pprint :as pprint]
   '[clojure.set :as set]
   '[clojure.string :as str]
   '[game.core.access :refer [access-card installed-access-trigger]]
   '[game.core.actions :refer [score]]
   '[game.core.agendas :refer [update-all-advancement-requirements
                              update-all-agenda-points]]
   '[game.core.bad-publicity :refer [bad-publicity-prevent gain-bad-publicity
                                    lose-bad-publicity]]
   '[game.core.board :refer [all-active-installed all-installed
                            installable-servers]]
   '[game.core.card :refer [agenda? asset? can-be-advanced? corp? event?
                           faceup? fake-identity? get-advancement-requirement
                           get-agenda-points get-card get-counters get-zone hardware? has-subtype? ice? identity?
                           in-deck? in-discard? in-hand? in-server? installed? is-type? operation?
                           program? resource? rezzed? runner? upgrade?]]
   '[game.core.card-defs :refer [card-def]]
   '[game.core.damage :refer [damage damage-prevent]]
   '[game.core.def-helpers :refer [corp-recur corp-rez-toast defcard
                                  trash-on-empty]]
   '[game.core.drawing :refer [draw first-time-draw-bonus max-draw
                              remaining-draws]]
   '[game.core.effects :refer [register-floating-effect]]
   '[game.core.eid :refer [complete-with-result effect-completed make-eid]]
   '[game.core.engine :refer [pay register-events resolve-ability]]
   '[game.core.events :refer [first-event? no-event? turn-events]]
   '[game.core.expose :refer [expose-prevent]]
   '[game.core.flags :refer [lock-zone prevent-current prevent-draw
                            register-turn-flag! release-zone]]
   '[game.core.gaining :refer [gain gain-clicks gain-credits lose lose-clicks
                              lose-credits]]
   '[game.core.hand-size :refer [corp-hand-size+ runner-hand-size+]]
   '[game.core.hosting :refer [host]]
   '[game.core.ice :refer [add-extra-sub! remove-extra-subs! update-all-ice
                          update-ice-strength]]
   '[game.core.identities :refer [disable-card enable-card]]
   '[game.core.initializing :refer [card-init]]
   '[game.core.installing :refer [corp-install corp-install-msg]]
   '[game.core.link :refer [get-link]]
   '[game.core.moving :refer [as-agenda mill move remove-from-currently-drawing
                             swap-cards swap-installed trash trash-cards]]
   '[game.core.optional :refer [get-autoresolve set-autoresolve]]
   '[game.core.payment :refer [can-pay? cost-value]]
   '[game.core.play-instants :refer [play-instant]]
   '[game.core.prompts :refer [cancellable]]
   '[game.core.props :refer [add-counter add-icon add-prop remove-icon set-prop]]
   '[game.core.revealing :refer [reveal]]
   '[game.core.rezzing :refer [derez rez]]
   '[game.core.say :refer [system-msg]]
   '[game.core.servers :refer [is-remote? zone->name]]
   '[game.core.set-aside :refer [swap-set-aside-cards]]
   '[game.core.shuffling :refer [shuffle! shuffle-into-deck
                                shuffle-into-rd-effect]]
   '[game.core.tags :refer [gain-tags]]
   '[game.core.to-string :refer [card-str]]
   '[game.core.toasts :refer [toast]]
   '[game.core.update :refer [update!]]
   '[game.core.winning :refer [check-win-by-agenda]]
   '[game.macros :refer [continue-ability effect msg req wait-for]]
   '[game.utils :refer :all]
   '[jinteki.utils :refer :all])

(defcard "Jeeves Model Bioroids"
  (let [ability {:label "Gain [Click]"
                 :msg "gain [Click]"
                 :once :per-turn
                 :effect (effect (gain-clicks 1))}
        cleanup (effect (update! (dissoc card :seen-this-turn)))]
    {:abilities [ability]
     :leave-play cleanup
     :events [{:event :corp-spent-click
               :effect (req (let [cid (first target)
                                  ability-idx (:ability-idx (:source-info eid))
                                  bac-cid (get-in @state [:corp :basic-action-card :cid])
                                  cause (if (keyword? (first target))
                                          (case (first target)
                                            :play-instant [bac-cid 3]
                                            :corp-click-install [bac-cid 2]
                                            (first target)) ; in clojure there's: (= [1 2 3] '(1 2 3))
                                          [cid ability-idx])
                                  clicks-spent (+ (get-in card [:seen-this-turn cause] 0) (second targets))]
                              (update! state side (assoc-in card [:seen-this-turn cause] clicks-spent))
                              (when (>= clicks-spent 3) ; can be >= 3 because :once :per-turn on ability
                                (resolve-ability state side ability (get-card state card) nil))))}
              {:event :corp-turn-ends
               :effect cleanup}]}))
