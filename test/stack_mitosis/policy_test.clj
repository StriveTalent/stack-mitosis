(ns stack-mitosis.policy-test
  (:require [clojure.test :as t :refer [deftest is]]
            [stack-mitosis.operations :as op]
            [stack-mitosis.policy :as sut]
            [stack-mitosis.planner :as plan]))

(defn fake-arn [name]
  (str "arn:aws:rds:us-east-1:1234567:db:" name))

(defn fake-snapshot-arn [name]
  (str "arn:aws:rds:us-east-1:1234567:snapshot:" name))

(defn example-instances []
  [{:DBInstanceIdentifier "production" :ReadReplicaDBInstanceIdentifiers ["production-replica"]
    :DBInstanceArn (fake-arn "production")}
   {:DBInstanceIdentifier "production-replica" :ReadReplicaSourceDBInstanceIdentifier "production"
    :DBInstanceArn (fake-arn "production-replica")}
   {:DBInstanceIdentifier "staging" :ReadReplicaDBInstanceIdentifiers ["staging-replica"]
    :DBInstanceArn (fake-arn "staging")}
   {:DBInstanceIdentifier "staging-replica" :ReadReplicaSourceDBInstanceIdentifier "staging"
    :DBInstanceArn (fake-arn "staging-replica")}])

(deftest make-arn
  (is (= "arn:aws:rds:*:*:db:production"
         (sut/make-arn "production")))
  (is (= "arn:aws:rds:*:*:og:*"
         (sut/make-arn "*" :type "og")))
  (is (= "arn:aws:rds:us-east-1:*:db:*"
         (sut/make-arn "*" :region "us-east-1")))
  (is (= "arn:aws:rds:*:1234:db:*"
         (sut/make-arn "*" :account-id "1234"))))

(deftest permissions
  (let [instance {:DBInstanceIdentifier "foo" :DBInstanceArn (fake-arn "foo")}]
    (is (= [{:op :DeleteDBInstance :arn (fake-arn "foo")}]
           (sut/permissions [instance] (op/delete "foo")))
        "only operation if instance is not found")
    (is (= [{:op :DeleteDBInstance :arn (fake-arn "foo")}]
           (sut/permissions [instance] (op/delete "foo")))
        "operation and arn if instance is found")
    (is (= [{:op :DescribeDBInstances}]
           (sut/permissions [] (op/describe)))
        "operation only if no database identifier in request")
    (is (= [{:op :CreateDBInstanceReadReplica
             :arn ["arn:aws:rds:*:*:og:*"
                   "arn:aws:rds:*:*:pg:*"
                   "arn:aws:rds:*:*:subgrp:*"
                   "arn:aws:rds:us-east-1:1234567:db:foo"
                   "arn:aws:rds:us-east-1:1234567:db:bar"]}
            {:op :AddTagsToResource
             :arn "arn:aws:rds:us-east-1:1234567:db:bar"}]
           (sut/permissions [instance] (op/create-replica "foo" "bar"))))
    (is (= [{:op :RestoreDBInstanceFromDBSnapshot
             :arn ["arn:aws:rds:*:*:og:*"
                   "arn:aws:rds:*:*:pg:*"
                   "arn:aws:rds:*:*:subgrp:*"
                   "arn:aws:rds:us-east-1:1234567:snapshot:*"
                   "arn:aws:rds:us-east-1:1234567:db:bar"]}
            {:op :AddTagsToResource
             :arn "arn:aws:rds:us-east-1:1234567:db:bar"}
            {:op :DescribeDBSnapshots :arn "*"}]
           (sut/permissions [instance] (op/restore-snapshot "foo" instance "bar"))))
    (is (= [{:op :ModifyDBInstance
             :arn ["arn:aws:rds:*:*:og:*"
                   "arn:aws:rds:*:*:pg:*"
                   "arn:aws:rds:*:*:secgrp:*"
                   "arn:aws:rds:*:*:subgrp:*"
                   "arn:aws:rds:us-east-1:1234567:db:foo"]}
            {:op :ModifyDBInstance
             :arn "arn:aws:rds:us-east-1:1234567:db:bar"}
            {:op :RebootDBInstance
             :arn "arn:aws:rds:us-east-1:1234567:db:foo"}]
           (sut/permissions [instance] (op/rename "foo" "bar"))))
    (is (= [{:op :ModifyDBInstance
             :arn ["arn:aws:rds:*:*:og:*"
                   "arn:aws:rds:*:*:pg:*"
                   "arn:aws:rds:*:*:secgrp:*"
                   "arn:aws:rds:*:*:subgrp:*"
                   "arn:aws:rds:us-east-1:1234567:db:foo"]}
            {:op :RebootDBInstance
             :arn "arn:aws:rds:us-east-1:1234567:db:foo"}]
           (sut/permissions [instance] (op/modify "foo" {}))))))

(deftest from-plan
  (is (= {:Version "2012-10-17"
          :Statement
          [(sut/allow [:DescribeDBInstances :ListTagsForResource]
                      ["arn:aws:rds:*:*:db:*"])
           (sut/allow [:CreateDBInstanceReadReplica]
                      (into ["arn:aws:rds:*:*:og:*"
                             "arn:aws:rds:*:*:pg:*"
                             "arn:aws:rds:*:*:subgrp:*"]
                            (mapv fake-arn ["production" "temp-staging" "temp-staging-replica"])))
           (sut/allow [:AddTagsToResource]
                      (mapv fake-arn ["temp-staging" "temp-staging-replica"]))
           (sut/allow [:PromoteReadReplica]
                      [(fake-arn "temp-staging")])
           (sut/allow [:ModifyDBInstance]
                      (into ["arn:aws:rds:*:*:og:*"
                             "arn:aws:rds:*:*:pg:*"
                             "arn:aws:rds:*:*:secgrp:*"
                             "arn:aws:rds:*:*:subgrp:*"]
                            (mapv fake-arn ["temp-staging" "temp-staging-replica"
                                            "staging-replica" "old-staging-replica"
                                            "staging" "old-staging"])))
           (sut/allow [:RebootDBInstance]
                      (mapv fake-arn ["temp-staging" "temp-staging-replica" "staging-replica" "staging"]))
           (sut/allow [:DeleteDBInstance]
                      (mapv fake-arn ["old-staging-replica" "old-staging"]))]}
         (sut/from-plan (example-instances)
                        (plan/replace-tree (example-instances) "production" "staging"))))
  (is (= {:Version "2012-10-17"
          :Statement
          [(sut/allow [:DescribeDBInstances :ListTagsForResource]
                      ["arn:aws:rds:*:*:db:*"])
           (sut/allow [:RestoreDBInstanceFromDBSnapshot]
                      ["arn:aws:rds:*:*:og:*"
                       "arn:aws:rds:*:*:pg:*"
                       "arn:aws:rds:*:*:subgrp:*"
                       (fake-snapshot-arn "*")
                       (fake-arn "temp-staging")])
           (sut/allow [:AddTagsToResource]
                      (mapv fake-arn ["temp-staging" "temp-staging-replica"]))
           (sut/allow [:DescribeDBSnapshots] ["*"])
           (sut/allow [:ModifyDBInstance]
                      (into ["arn:aws:rds:*:*:og:*"
                             "arn:aws:rds:*:*:pg:*"
                             "arn:aws:rds:*:*:secgrp:*"
                             "arn:aws:rds:*:*:subgrp:*"]
                            (mapv fake-arn ["temp-staging" "temp-staging-replica"
                                            "staging-replica" "old-staging-replica"
                                            "staging" "old-staging"])))
           (sut/allow [:RebootDBInstance]
                      (mapv fake-arn ["temp-staging" "temp-staging-replica" "staging-replica" "staging"]))
           (sut/allow [:CreateDBInstanceReadReplica]
                      (into ["arn:aws:rds:*:*:og:*"
                             "arn:aws:rds:*:*:pg:*"
                             "arn:aws:rds:*:*:subgrp:*"]
                            (mapv fake-arn ["temp-staging" "temp-staging-replica"])))
           (sut/allow [:DeleteDBInstance]
                      (mapv fake-arn ["old-staging-replica" "old-staging"]))]}
         (sut/from-plan (example-instances)
                        (plan/replace-tree (example-instances) "production" "staging"
                                           :source-snapshot "snapshot-id")))))
