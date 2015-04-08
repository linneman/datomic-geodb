;;; my first experiments with datomic
;;; using the German geo database as
;;; sample data ()
;;;
;;; The use and distribution terms for this software are covered by
;;; the GNU General Public License

(ns datomic-geodb.core
  (:require [clojure.string :as str])
  (:use [datomic.api :only [q db] :as d]
        [clojure.pprint]))


(defn- line-not-empty? [l]
  (not (or (empty? l) (.startsWith l "#") (< (count (str/split l #"\t")) 15))))


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


(defn- str2int [x]
  (when-let [x (and (instance? String x) (.trim x))]
    (when-not (empty? x)
      (.intValue (Integer. x)))))

(defn- str2double [x]
  (when-let [x (and (instance? String x) (.trim x))]
   (when-not (empty? x)
     (.doubleValue (Double. x)))))

(defn- tempid []
  (d/tempid :db.part/user))

(def ^:private geodb-keys
  [:geodb/id :geodb/ags :geodb/ascii :geodb/name
   :geodb/lat :geodb/lon :geodb/amt :geodb/plz
   :geodb/vorwahl :geodb/einwohner :geodb/flaeche
   :geodb/kz :geodb/typ :geodb/level :geodb/of
   :geodb/invalid])

(defn read-geo-db
  "read geo database given as plain text (DAT) format where
  fields are separated by tabs and return a list of community
  entities with the given keys for zip code, location, etc.
  For a detailed specification refer to:
  http://opengeodb.org/wiki/OpenGeoDB_-_Dateninhalt"
  [cvs-file-name]
  (let [lines (read-content-lines cvs-file-name)
        line2hash #(apply hash-map (interleave geodb-keys (str/split % #"\t")))
        asciify (fn [h k] (if (empty? (h k)) (dissoc h k) (assoc h k (asciify-string (h k)))))
        ascii2key-typ (fn [h] (let [k :geodb/typ] (if (empty? (h k))
                                                    (dissoc h k)
                                                    (assoc h k (keyword (str "geodb.typ/" (h k)))))))
        dbid (fn [h k] (assoc h k (tempid)))
        locid (fn [h k] (assoc h k (str2int (h k))))
        refid (fn [h k] (if (empty? (h k)) (dissoc h k) (assoc h k (str2int (h k)))))
        coord (fn [h] (let [lat :geodb/lat lon :geodb/lon
                            latv (str2double (h lat)) lonv (str2double (h lon))]
                        (if (and latv lonv)
                          (-> h (assoc lat latv) (assoc lon lonv))
                          (-> h (dissoc lat) (dissoc lon)))))
        area (fn [h] (let [k :geodb/flaeche] (if (empty? (h k)) (dissoc h k) (assoc h k (str2double (h k))))))
        level (fn [h] (let [k :geodb/level] (if (empty? (h k)) (dissoc h k) (assoc h k (str2int (h k))))))
        pop (fn [h] (let [k :geodb/einwohner] (if (empty? (h k)) (dissoc h k) (assoc h k (str2int (h k))))))
        invalid (fn [h] (let [k :geodb/invalid] (assoc h k (= "1" (h k)))))]
    (def lines lines)
    (map
     #(-> %
          (line2hash)
          (asciify :geodb/typ)
          (ascii2key-typ)
          (dbid :db/id)
          (locid :geodb/id)
          (coord)
          (refid :geodb/of)
          (area)
          (level)
          (pop)
          (invalid))
     lines)))


(comment

  (def geo-db (read-geo-db "./init/DE.tab.txt"))
  (def all-communities (set (filter identity (map #(:geodb/typ %) geo-db))))


  (dorun (map #(println
                (str "[:db/add #db/id[:db.part/user] :db/ident :geodb.typ/" % "]"))
              all-communities))

  (count all-communities)
  (count geo-db)

  (take 1 (drop 500 geo-db))
  (take 1 (drop 278 geo-db))
  (take 1 geo-db)


  ;; store database uri
  (def uri "datomic:mem://geodb")

  ;; create database
  (d/create-database uri)
  (d/delete-database uri)

  ;; connect to database
  (def conn (d/connect uri))


  (def schema-tx (read-string (slurp "./init/geo-db-schema.edn")))

  ;; submit schema transaction
  @(d/transact conn schema-tx)




  ;; find all communities, return entity ids
  (do
    (def results (q '[:find  ?c ?n
                      :in $
                      :where [?c :geodb/name ?n]]
                    (db geodb-conn)))
    (count results)))



(defn- find-locid
  "find atomic primary kez for given geodb location id"
  [conn id]
  (q '[:find  [?c ...]
       :in $ ?locid
       :where
       [?c :geodb/id ?locid]]
     (db conn)
     id))


(defn- update-db-refs
  "update datomic references (part of)"
  [conn h]
  (doseq [[no c] (map vector (iterate inc 0) h)]
    (dorun
     (let [[id] (find-locid conn (:geodb/id c))]
       (when-let [of (:geodb/of c)]
         (let [[ofid] (find-locid conn of)]
           (when ofid
             (when (= 0 (mod no 100)) (println (str no " transactions processed ...")))
             @(d/transact conn [{:db/id id :geodb/of ofid}]))))))))


(defn init-datomic-geobdb
  "initialize datomic database via given connection from tab file"
  [geodb-tab-file geodb-datomic-schema-file conn]
  (let [geo-db (read-geo-db geodb-tab-file)
        schema-tx (read-string (slurp geodb-datomic-schema-file))]
    (println "submit geodb schema to database ...")
    (d/transact conn schema-tx)
    (println "submit geodb data ...")
    (doseq [[no t] (map vector (iterate inc 0) geo-db)]
      (when (= 0 (mod no 100)) (println (str no " transactions processed ...")))
      (d/transact conn (list (dissoc t :geodb/of))))
    (println "update references ...")
    (update-db-refs conn geo-db)))



;; store database uri
;(def geodb-uri "datomic:mem://geodb")
;(def geodb-uri "datomic:free://localhost:4334/geodb")


;; create database
;(d/create-database geodb-uri)
;(d/delete-database geodb-uri)


;; connect to database
;(def geodb-conn (d/connect geodb-uri))
;(d/release geodb-conn)

;(init-datomic-geobdb "./init/DE.tab.txt" "./init/geo-db-schema.edn" geodb-conn)

;(d/get-database-names "datomic:free://localhost:4334/*")

(comment
  (def s (first (q '[:find [?c]
                     :in $ ?n
                     :where
                     [?c :geodb/name ?n]
                     [?c :geodb/level ?l]
                     [?c :geodb/level 3]
                     ]
                   (db geodb-conn)
                   "Sachsen")))
  )




(defn resolve-communities
  "resolves  hierachical all  communities  which  refer to  the  given entitiy.

  In example the districts 'Chemnitz' 'Leipzig' 'aufgelöste Kreise' 'Dresden' all
  refer to the  German state 'Sachsen' (Saxony). Since  datomic handles references
  bidirectionally the  districts which belong to  Saxony can be retrieved  via the
  tag :geodb/_of. The  functional does this recursively until  communities with no
  further references are resovled."
  [e]
  (map
   #(if (empty? (:geodb/_of %)) (:geodb/name %) {(:geodb/name %) (resolve-communities %)})
   (:geodb/_of e)))


(defn- flatten-map [coll]
  (lazy-seq
    (when-let [s  (seq coll)]
      (if (coll? (first s))
        (concat (flatten-map (first s)) (flatten-map (rest s)))
        (cons (first s) (flatten-map (rest s)))))))


(defn find-subordinated-communities
  "finds all communities suboridnated to a given major community or state
   and returns the result as flat list"
  [conn major-community-or-area-name]
  (let [s (first (q '[:find [?c]
                      :in $ ?n
                      :where
                      [?c :geodb/name ?n]
                      [?c :geodb/level ?l]
                      [?c :geodb/level 3]
                      ]
                    (db conn)
                    major-community-or-area-name))
        e (d/entity (db conn) s)]
    (flatten-map (resolve-communities e))))



(comment

  (def e (d/entity (db geodb-conn) s))
  (println (map :geodb/name (:geodb/_of e)))
  (println (resolve-communities e))

  )


(defn dist
  "distance computation with given pair of latitude/longitude coordinates
   ref: http://www.kompf.de/gps/distcalc.html"
  [lat1 lon1 lat2 lon2]
  (let [grad2rad (/ Math/PI 180)
        [lat1 lon1 lat2 lon2] (map #(* grad2rad %) [lat1 lon1 lat2 lon2])]
    (* 6378.388 (Math/acos
                 (+ (* (Math/sin lat1) (Math/sin lat2))
                    (* (Math/cos lat1) (Math/cos lat2) (Math/cos (- lon2 lon1))))))))


(comment
  (dist 49.9917 8.41321 50.0049 8.42182)
  (datomic-geodb.core/dist 49.9917 8.41321 50.0049 8.42182)
  )

(defn find-in-vicinity-to-coord
  "find location in the vicinity to given latitude/longitude"
  [conn lat lon distance]
  (q '[:find [?n ...]
       :in $ ?max-distance ?orig-lat ?orig-lon
       :where
       [?c :geodb/name ?n]
       [?c :geodb/lat ?lat]
       [?c :geodb/lon ?lon]
       [(datomic-geodb.core/dist ?orig-lat ?orig-lon ?lat ?lon) ?d]
       [(< ?d ?max-distance)]]
     (db conn)
     distance lat lon))

(comment
  (find-in-vicinity-to-coord geodb-conn 49.9917 8.41321 2)
  (find-in-vicinity-to-coord geodb-conn 51.9917 8.41321 2)
  )

(defn find-in-vicinity-to-name
  "find location in the vicinity to given location name"
  [conn name dist]
  (let [[lat lon]
        (q '[:find [?lat ?lon]
             :in $ ?n
             :where
             [?c :geodb/name ?n]
             [?c :geodb/lat ?lat]
             [?c :geodb/lon ?lon]]
           (db conn)
           name)]
    (when (and lat lon)
      (find-in-vicinity-to-coord conn lat lon dist))))

(comment
  (find-in-vicinity-to-name geodb-conn "Dresden" 20)
  (find-in-vicinity-to-name geodb-conn "Frankfurt am Main" 20)
  (find-in-vicinity-to-name geodb-conn "Gießen" 10)
  (find-in-vicinity-to-name geodb-conn "Leihgestern" 50)
  )
