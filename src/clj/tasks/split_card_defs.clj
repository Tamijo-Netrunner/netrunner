(ns clj.tasks.split-card-defs
  (:require [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn slugify
  "Slightly different from the one in jinteki.utils lol"
  ([string] (slugify string "-"))
  ([string sep]
   (if-not (string? string) ""
     (as-> string $
       (java.text.Normalizer/normalize $ java.text.Normalizer$Form/NFD)
       (str/replace $ #"[^\x00-\x7F]+" "")
       ;; this line is the change: remove ' and " from strings before splitting.
       ;; makes names with apostrophes look better, i think
       (str/replace $ #"[\"']" "")
       (str/lower-case $)
       (str/trim $)
       (str/split $ #"[ \t\n\x0B\f\r!\"#$%&'()*+,-./:;<=>?@\\\[\]^_`{|}~]+")
       (filter seq $)
       (str/join sep $)))))

(defn split-card-defs [card-type]
  (let [zloc (z/of-file (format "src/clj/game/cards/%s.clj" card-type))
        ns-form (-> zloc
                    (z/find-depth-first #(= :require (z/sexpr %)))
                    (z/edit symbol)
                    z/up
                    (z/subedit->
                      (z/postwalk
                        (fn select [zloc]
                          (and (vector? (z/sexpr zloc))
                               (= 'require
                                  (-> zloc
                                      z/up
                                      z/next
                                      z/sexpr))))
                        (fn visit [zloc] (->> (n/quote-node [(z/node zloc)])
                                              (z/replace zloc))))))]

    (loop [zloc zloc]
      (when-not (z/end? zloc)
        (let [form (z/next zloc)]
          (when (= 'defcard (z/sexpr form))
            (let [card-name (-> form z/next z/sexpr)
                  filename (str (slugify card-name "_") ".clj")]
              (spit (format "src/clj/game/cards/%s/%s" card-type filename)
                    (n/string (n/forms-node
                                [(p/parse-string-all (format "(in-ns 'game.cards.%s)"
                                                             card-type))
                                 (n/newlines 2)
                                 (z/node ns-form)
                                 (n/newlines 2)
                                 (z/node (z/up form))
                                 (n/newlines 1)])))))
          (recur (z/right zloc)))))))

(defn perform-splits []
  (doseq [card-type ["agendas" "assets" "events" "hardware" "ice" "identities"
                     "operations" "programs" "resources" "upgrades"]]
    (-> "src/clj/game/cards/%s"
        (format card-type)
        io/file
        (io/delete-file true))
    (split-card-defs card-type)))

(comment
  (perform-splits))
