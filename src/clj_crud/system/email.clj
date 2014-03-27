(ns clj-crud.system.email
  (:require [clojure.tools.logging :refer [info debug spy error]]
            [com.stuartsierra.component :as component]))

(defprotocol Emailer
  (send-email [this email content]))

(defrecord LogEmailer []
  component/Lifecycle
  (start [component]
    (info ";; Starting emailer")
    component)

  (stop [component]
    (info ";; Stopping emailer")
    component)
  Emailer
  (send-email [this email content]
    (debug "Pretending to send email to: " email "with content:" content)))

(defn log-emailer []
  (map->LogEmailer {}))
