(ns datomic-geodb.core-test
  (:require [clojure.test :refer :all]
            [datomic-geodb.core :refer :all])
  (:use [datomic.api :only [q db] :as d]
        [clojure.pprint]))


(comment

  (deftest init-datomic-geodb-test
    (testing "initializing datomic in memory database with geo-db tabular ..."
      (let [uri "datomic:mem://geodb"]
        (when (d/create-database uri)
          (let [conn (d/connect uri)]
            (init-datomic-geobdb "./init/DE.tab.txt" "./init/geo-db-schema.edn" conn)
            (is (= 61110 (count (q '[:find  ?c ?n
                                     :in $
                                     :where [?c :geodb/name ?n]]
                                   (db conn))))))))))
  )



(deftest find-subordinated-communities-test
  (testing "testing search for subordinated community for German state Saxony ..."
    (let [uri "datomic:free://localhost:4334/geodb"]
      (when-let [conn (d/connect uri)]
        (let [s (find-subordinated-communities conn "Sachsen")]
          (is (= (count s) 932)))))))



(deftest find-in-vicinity-to-name-test
  (testing "find communities in vicinity to Dresden ..."
    (let [uri "datomic:free://localhost:4334/geodb"]
      (when-let [conn (d/connect uri)]
        (let [v (find-in-vicinity-to-name conn "Dresden" 20)]
          (is (= (count v) 76)))))))
