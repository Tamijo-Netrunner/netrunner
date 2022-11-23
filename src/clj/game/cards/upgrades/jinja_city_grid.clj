(in-ns 'game.cards.upgrades)

(require
   '[clojure.java.io :as io]
   '[clojure.string :as str]
   '[cond-plus.core :refer [cond+]]
   '[game.core.access :refer [access-bonus installed-access-trigger
                             set-only-card-to-access steal-cost-bonus]]
   '[game.core.bad-publicity :refer [lose-bad-publicity]]
   '[game.core.board :refer [all-active-installed all-installed card->server
                            get-remotes server->zone server-list]]
   '[game.core.card :refer [agenda? asset? can-be-advanced?
                           corp-installable-type? corp? get-card get-counters get-zone
                           has-subtype? ice? in-discard? in-hand? installed? operation? program? resource? rezzed?
                           runner? upgrade?]]
   '[game.core.cost-fns :refer [install-cost rez-cost]]
   '[game.core.costs :refer [total-available-credits]]
   '[game.core.damage :refer [damage]]
   '[game.core.def-helpers :refer [corp-rez-toast defcard offer-jack-out
                                  reorder-choice]]
   '[game.core.drawing :refer [draw]]
   '[game.core.effects :refer [register-floating-effect]]
   '[game.core.eid :refer [effect-completed make-eid]]
   '[game.core.engine :refer [dissoc-req pay register-default-events
                             register-events resolve-ability unregister-events]]
   '[game.core.events :refer [first-event? first-run-event? turn-events]]
   '[game.core.expose :refer [expose-prevent]]
   '[game.core.finding :refer [find-cid find-latest]]
   '[game.core.flags :refer [clear-persistent-flag! enable-run-on-server
                            is-scored? prevent-run-on-server
                            register-persistent-flag! register-run-flag!]]
   '[game.core.gaining :refer [gain-credits lose-clicks lose-credits]]
   '[game.core.hand-size :refer [corp-hand-size+]]
   '[game.core.ice :refer [all-subs-broken? get-run-ices pump-ice
                          unbroken-subroutines-choice update-all-ice update-all-icebreakers]]
   '[game.core.installing :refer [corp-install]]
   '[game.core.moving :refer [mill move remove-from-currently-drawing
                             swap-cards swap-ice trash trash-cards]]
   '[game.core.optional :refer [get-autoresolve set-autoresolve]]
   '[game.core.payment :refer [can-pay? cost-value]]
   '[game.core.play-instants :refer [play-instant]]
   '[game.core.prompts :refer [cancellable clear-wait-prompt]]
   '[game.core.props :refer [add-counter add-prop set-prop]]
   '[game.core.purging :refer [purge]]
   '[game.core.revealing :refer [reveal]]
   '[game.core.rezzing :refer [rez]]
   '[game.core.runs :refer [end-run force-ice-encounter jack-out redirect-run
                           set-next-phase start-next-phase]]
   '[game.core.say :refer [system-msg]]
   '[game.core.servers :refer [central->zone from-same-server? in-same-server?
                              is-central? protecting-same-server? same-server?
                              target-server unknown->kw zone->name]]
   '[game.core.shuffling :refer [shuffle!]]
   '[game.core.tags :refer [gain-tags]]
   '[game.core.to-string :refer [card-str]]
   '[game.core.toasts :refer [toast]]
   '[game.core.trace :refer [init-trace-bonus]]
   '[game.core.update :refer [update!]]
   '[game.macros :refer [continue-ability effect msg req wait-for]]
   '[game.utils :refer :all]
   '[jinteki.utils :refer :all])

(defcard "Jinja City Grid"
  (letfn [(install-ice [ice ices grids server]
            (let [remaining (remove-once #(same-card? % ice) ices)]
              {:async true
               :effect (req (if (= "None" server)
                              (continue-ability state side (choose-ice remaining grids) card nil)
                              (wait-for
                                (reveal state side ice)
                                (system-msg state side (str "reveals that they drew " (:title ice)))
                                (wait-for (corp-install state side ice server {:cost-bonus -4})
                                          (remove-from-currently-drawing state side ice)
                                          (continue-ability
                                            state side
                                            (when-not (= 1 (count ices))
                                              (choose-ice remaining grids))
                                            card nil)))))}))
          (choose-grid [ice ices grids]
            (if (= 1 (count grids))
              (install-ice ice ices grids (-> (first grids) :zone second zone->name))
              {:async true
               :prompt (str "Choose a server to install " (:title ice))
               :choices (conj (mapv #(-> % :zone second zone->name) grids) "None")
               :effect (effect (continue-ability (install-ice ice ices grids target) card nil))}))
          (choose-ice [ices grids]
            (when (seq ices)
              {:async true
               :prompt "Choose an ice to reveal and install"
               :choices (conj (mapv :title ices) "None")
               :effect
               (effect (continue-ability
                         (when-not (= "None" target)
                           (choose-grid (some #(when (= target (:title %)) %) ices) ices grids))
                         card nil))}))]
    {:events [{:event :corp-draw
               ;; This prevents multiple Jinja from showing the "choose a server to install into" sequence
               :once :per-turn
               :once-key :jinja-city-grid-draw
               :async true
               :waiting-prompt true
               :req (req (not (find-cid (:cid card) (flatten (vals (get-in @state [:trash :trash-list]))))))
               :effect (req (cond
                              ;; if ice were drawn, do the full routine
                              (some ice? corp-currently-drawing)
                              (let [ices (filter #(and (ice? %)
                                                       (get-card state %))
                                                 corp-currently-drawing)
                                    grids (filterv #(= "Jinja City Grid" (:title %))
                                                   (all-active-installed state :corp))]
                                (continue-ability
                                  state side
                                  (when (not-empty ices)
                                    (choose-ice ices grids))
                                  card nil))
                              ;; else show a fake prompt so the runner can't infer that ice weren't drawn
                              :else
                              (continue-ability
                                state :corp
                                {:prompt "You did not draw any ice"
                                 :choices ["Carry on!"]
                                 :prompt-type :bogus}
                                card nil)))}
              {:event :post-corp-draw
               :effect (req (swap! state dissoc-in [:per-turn :jinja-city-grid-draw]))}]}))
