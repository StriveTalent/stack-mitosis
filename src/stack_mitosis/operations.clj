(ns stack-mitosis.operations)

(defn delete
  [id]
  {:op :DeleteDBInstance
   :request {:DBInstanceIdentifier id
             :SkipFinalSnapshot true}})

(defn modify
  [id options]
  {:op :ModifyDBInstance
   :request
   (merge {:DBInstanceIdentifier id
           :ApplyImmediately true}
          options)})

(defn rename
  [old new]
  (modify old {:NewDBInstanceIdentifier new}))

(defn enable-backups
  [id]
  (modify id {:BackupRetentionPeriod 1}))

(defn create
  [options]
  {:op :CreateDBInstance
   :request options})

(defn create-replica
  [source replica]
  {:op :CreateDBInstanceReadReplica
   :request {:SourceDBInstanceIdentifier source
             :DBInstanceIdentifier replica}})

(defn promote
  [id]
  {:op :PromoteReadReplica
   :request {:DBInstanceIdentifier id}})

(defn describe [id]
  {:op :DescribeDBInstances
   :request {:DBInstanceIdentifier id}})

(defn tags [db-arn]
  {:op :ListTagsForResource
   :request {:ResourceName db-arn}})

(defn add-tags [db-arn tags]
  {:op :AddTagsToResource
   :request {:ResourceName db-arn
             :Tags tags}})

(defn shell-command [cmd]
  {:op :shell-command
   :request {:cmd cmd}})

(defn blocking-operation?
  [action]
  (contains? #{:CreateDBInstance :CreateDBInstanceReadReplica
               :PromoteReadReplica :ModifyDBInstance} (:op action)))

(defn transition-to
  "Maps current rds status to in-progress, failed or done

  From https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Overview.DBInstance.Status.html"
  [state]
  (condp contains? (get state :DBInstanceStatus)
    #{"backing-up" "backtracking" "configuring-enhanced-monitoring"
      "configuring-iam-database-auth" "configuring-log-exports"
      "converting-to-vpc" "creating" "deleting" "maintenance" "modifying"
      "moving-to-vpc" "rebooting" "renaming" "resetting-master-credentials"
      "starting" "stopping" "storage-optimization" "upgrading"}
    :in-progress
    #{"failed" "inaccessible-encryption-credentials" "incompatible-credentials"
      "incompatible-network" "incompatible-option-group"
      "incompatible-parameters" "incompatible-restore" "restore-error"
      "storage-full"}
    :failed
    #{"stopped" "available"}
    :done
    ;; unknown or missing
    nil
    :failed
    ))

(defn completed?
  [described-instances]
  (if (:ErrorResponse described-instances)
    false
    (contains? #{:done :failed} (transition-to (first (:DBInstances described-instances))))))

(defn missing?
  [described-instances]
  (= :cognitect.anomalies/not-found (:cognitect.anomalies/category described-instances)))
