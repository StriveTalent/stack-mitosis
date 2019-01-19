(ns stack-mitosis.predict-test
  (:require [stack-mitosis.predict :as p]
            [clojure.test :refer :all]))

(deftest position
  (let [instances [{:DBInstanceIdentifier "a"}
                   {:DBInstanceIdentifier "b"}
                   {:DBInstanceIdentifier "c"}]]
    (is (= 0
           (p/position instances
                       {:request {:DBInstanceIdentifier "a"}})))
    (is (= 1
           (p/position instances
                       {:request {:DBInstanceIdentifier "b"}})))
    (is (= nil
           (p/position instances
                       {:request {:DBInstanceIdentifier "missing"}})))))

(deftest change
  (let [db {:DBInstanceIdentifier "a"}]
    (is (= {:DBInstanceIdentifier "new-name"}
           (p/change db {:op :ModifyDBInstance
                         :request {:DBInstanceIdentifier "a"
                                   :NewDBInstanceIdentifier "new-name"}})))))

