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

(defcard "Moon Pool"
  (letfn [(moon-pool-place-advancements [x]
            {:async true
             :prompt (msg "Choose an installed card to place advancement counters on (" x " remaining)")
             :choices {:card #(installed? %)}
             :msg (msg "place 1 advancement counter on " (card-str state target))
             :effect (req (wait-for (add-prop state side target :advance-counter 1 {:placed true})
                                    (if (> x 1)
                                      (continue-ability
                                        state side
                                        (moon-pool-place-advancements (dec x))
                                        card nil)
                                      (effect-completed state side eid))))
             :cancel-effect (effect (system-msg "declines to use Moon Pool to place advancement counters")
                                   (effect-completed eid))})]
    (let [moon-pool-reveal-ability
          {:prompt "Reveal up to 2 facedown cards from Archives and shuffle them into R&D"
           :async true
           :choices {:card #(and (corp? %)
                                 (in-discard? %)
                                 (not (faceup? %)))
                     :max 2}
           :msg (msg "reveal " (str/join " and " (map :title targets)) " from Archives and shuffle them into R&D")
           :effect (req (wait-for (reveal state side targets)
                                  (doseq [c targets]
                                    (move state side c :deck))
                                  (shuffle! state side :deck)
                                  (let [agenda-count (count (filter agenda? targets))]
                                    (if (pos? agenda-count)
                                      (continue-ability
                                        state side
                                        (moon-pool-place-advancements agenda-count)
                                        card nil)
                                      (effect-completed state side eid)))))
           :cancel-effect (effect (system-msg "declines to use Moon Pool to reveal any cards in Archives")
                                  (effect-completed eid))}
          moon-pool-discard-ability
            {:prompt "Trash up to 2 cards from HQ"
             :choices {:card #(and (corp? %)
                                   (in-hand? %))
                       :max 2}
             :async true
             :msg (msg "trash " (quantify (count targets) "card") " from HQ")
             :effect (req (wait-for (trash-cards state :corp targets {:cause-card card})
                                    (continue-ability
                                      state side
                                      moon-pool-reveal-ability
                                      card nil)))
             :cancel-effect (effect (system-msg "declines to use Moon Pool to trash any cards from HQ")
                                    (continue-ability moon-pool-reveal-ability card nil))}]
      {:abilities [{:label "Trash up to 2 cards from HQ. Shuffle up to 2 cards from Archives into R&D"
                    :cost [:remove-from-game]
                    :async true
                    :effect (effect (continue-ability
                                      moon-pool-discard-ability
                                      card nil))}]})))
