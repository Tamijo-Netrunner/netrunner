(in-ns 'game.cards.operations)

(require
   '[clojure.java.io :as io]
   '[clojure.set :as set]
   '[clojure.string :as str]
   '[game.core.access :refer [access-card steal-cost-bonus]]
   '[game.core.actions :refer [advance score]]
   '[game.core.bad-publicity :refer [gain-bad-publicity lose-bad-publicity]]
   '[game.core.board :refer [all-active-installed all-installed
                            get-all-installed get-remote-names get-remotes
                            installable-servers server->zone]]
   '[game.core.card :refer [agenda? asset? can-be-advanced? card-index
                           corp-installable-type? corp? event? facedown? faceup?
                           get-advancement-requirement get-card get-counters get-zone hardware? has-subtype?
                           ice? identity? in-discard? in-hand? installed? is-type? operation? program?
                           resource? rezzed? runner? upgrade?]]
   '[game.core.card-defs :refer [card-def]]
   '[game.core.cost-fns :refer [play-cost trash-cost]]
   '[game.core.costs :refer [total-available-credits]]
   '[game.core.damage :refer [damage damage-bonus]]
   '[game.core.def-helpers :refer [corp-recur defcard do-brain-damage
                                  reorder-choice]]
   '[game.core.drawing :refer [draw]]
   '[game.core.effects :refer [register-floating-effect]]
   '[game.core.eid :refer [effect-completed make-eid make-result]]
   '[game.core.engine :refer [pay register-events resolve-ability]]
   '[game.core.events :refer [first-event? last-turn? no-event? not-last-turn?
                             turn-events]]
   '[game.core.flags :refer [can-score? clear-persistent-flag! in-corp-scored?
                            in-runner-scored? is-scored? prevent-jack-out
                            register-persistent-flag! register-turn-flag! when-scored? zone-locked?]]
   '[game.core.gaining :refer [gain-clicks gain-credits lose-clicks
                              lose-credits]]
   '[game.core.hand-size :refer [runner-hand-size+]]
   '[game.core.ice :refer [add-extra-sub! remove-extra-subs! update-all-ice]]
   '[game.core.identities :refer [disable-identity enable-identity]]
   '[game.core.initializing :refer [ability-init card-init]]
   '[game.core.installing :refer [corp-install corp-install-list
                                 corp-install-msg install-as-condition-counter]]
   '[game.core.memory :refer [mu+ update-mu]]
   '[game.core.moving :refer [as-agenda mill move swap-agendas swap-ice trash
                             trash-cards]]
   '[game.core.payment :refer [can-pay? cost-target]]
   '[game.core.play-instants :refer [play-instant]]
   '[game.core.prompts :refer [cancellable clear-wait-prompt show-wait-prompt]]
   '[game.core.props :refer [add-counter add-prop]]
   '[game.core.purging :refer [purge]]
   '[game.core.revealing :refer [reveal]]
   '[game.core.rezzing :refer [derez rez]]
   '[game.core.runs :refer [end-run make-run]]
   '[game.core.say :refer [system-msg]]
   '[game.core.servers :refer [is-remote? remote->name zone->name]]
   '[game.core.shuffling :refer [shuffle! shuffle-into-deck
                                shuffle-into-rd-effect]]
   '[game.core.tags :refer [gain-tags]]
   '[game.core.to-string :refer [card-str]]
   '[game.core.toasts :refer [toast]]
   '[game.core.update :refer [update!]]
   '[game.core.virus :refer [number-of-virus-counters]]
   '[game.macros :refer [continue-ability effect msg req wait-for]]
   '[game.utils :refer :all]
   '[jinteki.utils :refer :all])

(defcard "Trust Operation"
  (let [ability {:prompt "Choose a card to install from Archives"
                 :show-discard true
                 :choices {:card #(and (corp? %)
                                       (in-discard? %))}
                 :msg (msg "install and rez " (:title target) ", ignoring all costs")
                 :async true
                 :cancel-effect (effect (system-msg "declines to use Trust Operation to install a card")
                                        (effect-completed eid))
                 :effect (effect (corp-install eid target nil {:ignore-all-cost true
                                                               :install-state :rezzed-no-cost}))}]
    {:on-play {:req (req tagged)
               :msg (msg "trash " (:title target))
               :prompt "Choose a resource to trash"
               :choices {:card #(and (installed? %)
                                     (resource? %))}
               :async true
               :cancel-effect (effect
                               (system-msg "declines to use Trust Operation to trash a resource")
                               (continue-ability ability card nil))
               :effect (req (wait-for (trash state side target {:cause-card card})
                                      (continue-ability state side ability card nil)))}}))
