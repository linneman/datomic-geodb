(ns datomic-geodb.core
  (:require [clojure.string :as str])
  (:use [datomic.api :only [q db] :as d]
        [clojure.pprint]))



(defn- line-not-empty? [l]
  (not (or (empty? l) (.startsWith l "#"))))


(defn- read-content-lines
  "read all lines from given cvs file which are not
   empty and which are no comment lines which start
   with the character #."
  [cvs-file-name]
  (with-open [rdr (clojure.java.io/reader cvs-file-name)]
    (doall (filter #(-> % .trim line-not-empty?) (line-seq rdr)))))


(defn- asciify-string
  "replaces Greman umlauts by its ascii substitution
   and blanks by underscore for deriving propoper key
   name e.g. for enumeration types."
  [s]
  (when s
    (-> s
        (str/replace #"Ä" "Ae")
        (str/replace #"Ö" "Oe")
        (str/replace #"Ü" "Ue")
        (str/replace #"ä" "ae")
        (str/replace #"ö" "oe")
        (str/replace #"ü" "ue")
        (str/replace #"ß" "ss")
        (str/replace #" " "_")
        (str/replace #"^([0-9]+)" "_$1")
        (str/replace #"[,\.:\(\)]" ""))))


(defn read-geo-db
  "read geo database given as plain text (DAT) format where
   fields are separated by tabs and return a list of community
   entities with the given keys for zip code, location, etc.
   For a detailed specification refer to:
   http://opengeodb.org/wiki/OpenGeoDB_-_Dateninhalt"
  [cvs-file-name]
  (let [lines (read-content-lines cvs-file-name)
        geo-keys [:geodb/loc_id :geodb/ags :geodb/ascii :geodb/name
                  :geodb/lat :geodb/lon :geodb/amt :geodb/plz
                  :geodb/vorwahl :geodb/einwohner :geodb/flaeche
                  :geodb/kz :geodb/typ :geodb/level :geodb/of
                  :geodb/invalid]
        line2hash #(apply hash-map (interleave geo-keys (str/split % #"\t")))
        asciify-key (fn [h k] (assoc h k (asciify-string (h k))))]
    (map
     #(-> %
       (line2hash)
       (asciify-key :geodb/typ))
     lines)))


(comment

  (def geo-db (read-geo-db "./init/DE.tab.txt"))
  (def all-communities (set (filter not-empty (map #(:geodb/typ %) geo-db))))

  (dorun (map #(println
                (str "[:db/add #db/id[:db.part/user] :db/ident :geodb.typ/" % "]"))
              all-communities))

  (count all-communities)
  (count geo-db)

  (take 1 (drop 5 geo-db))


  )



;; store database uri
(def uri "datomic:mem://geodb")

;; create database
(d/create-database uri)

;; connect to database
(def conn (d/connect uri))


(def schema-tx (read-string (slurp "./init/geo-db-schema.edn")))

;; display first statement
(first schema-tx)

;; submit schema transaction
@(d/transact conn schema-tx)
