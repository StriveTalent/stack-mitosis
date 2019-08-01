(ns stack-mitosis.operations-test
  (:require [stack-mitosis.operations :as op]
            [clojure.test :refer :all]))

(deftest blocking-operation
  (is (op/blocking-operation? (op/create-replica "bar" "foo")))
  (is (op/blocking-operation? (op/rename "rename-before" "rename-after")))
  (is (not (op/blocking-operation? (op/delete "foo")))))

(deftest completed?
  (let [not-found {:ErrorResponse
                   {:Error
                    {:Type "Sender",
                     :Code "DBInstanceNotFound",
                     :Message "DBInstance foo not found."},
                    :RequestId "e176cadd-9689-4119-9ea3-9762eddc965f"},
                   :ErrorResponseAttrs {:xmlns "http://rds.amazonaws.com/doc/2014-10-31/"},
                   :cognitect.anomalies/category :cognitect.anomalies/not-found}
        available {:DBInstances [{:DBInstanceStatus "available"}]}]
    (is (not (op/completed? not-found)))
    (is (op/completed? available))
    (is (not (op/missing? available)))
    (is (op/missing? not-found))))