(ns commiteth.scheduler
  (:require [commiteth.eth.core :as eth]
            [commiteth.eth.multisig-wallet :as multisig]
            [commiteth.eth.token-data :as token-data]
            [commiteth.github.core :as github]
            [commiteth.db.issues :as issues]
            [taoensso.tufte :as tufte :refer (defnp p profiled profile)]
            [commiteth.db.bounties :as db-bounties]
            [commiteth.bounties :as bounties]
            [commiteth.util.crypto-fiat-value :as fiat-util]
            [commiteth.util.util :refer [eth-decimal->str]]
            [clojure.tools.logging :as log]
            [mount.core :as mount]
            [clj-time.core :as t]
            [clj-time.coerce :as time-coerce]
            [clj-time.periodic :refer [periodic-seq]]
            [chime :refer [chime-at]]))

(tufte/add-basic-println-handler! {})
(tufte/add-handler! :file (fn [{stats :stats}]
                            (log/info "Profiling stats:" stats)))

(comment

  (profile {} (update-issue-contract-address))
  (profile {} (deploy-pending-contracts))
  (profile {} (self-sign-bounty))
  (profile {} (update-confirm-hash))
  (profile {} (update-watch-hash))
  (profile {} (update-payout-receipt))
  (profile {} (update-contract-internal-balances))
  (profile {} (update-open-issue-usd-values))
  (profile {} (update-balances))
  (profile {}
           (doseq [i (range 5)]
             (update-contract-internal-balances) 
             (update-open-issue-usd-values) 
             (update-balances)))

  )

(defn update-issue-contract-address
  "For each pending deployment: gets transaction receipt, updates db
  state (contract-address, comment-id) and posts github comment"
  []
  (log/info "In update-issue-contract-address")
  (p :update-issue-contract-address
     (doseq [{issue-id         :issue_id
              transaction-hash :transaction_hash} (issues/list-pending-deployments)]
       (log/infof "issue %s: pending deployment: %s" issue-id transaction-hash)
       (try 
         (when-let [receipt (eth/get-transaction-receipt transaction-hash)]
           (log/infof "issue %s: update-issue-contract-address: tx receipt: %s" issue-id receipt)
           (if-let [contract-address (multisig/find-created-multisig-address receipt)]
             (let [issue   (issues/update-contract-address issue-id contract-address)
                   {owner        :owner
                    repo         :repo
                    comment-id   :comment_id
                    issue-number :issue_number} issue
                   balance-eth-str (eth/get-balance-eth contract-address 6)
                   balance-eth (read-string balance-eth-str)]
               (log/infof "issue %s: Updating comment image" issue-id)
               (bounties/update-bounty-comment-image issue-id
                                                     owner
                                                     repo
                                                     issue-number
                                                     contract-address
                                                     balance-eth
                                                     balance-eth-str
                                                     {})
               (log/infof "issue %s: Updating comment" issue-id)
               (github/update-comment owner
                                      repo
                                      comment-id
                                      issue-number
                                      contract-address
                                      balance-eth
                                      balance-eth-str
                                      {}))
             (log/errorf "issue %s: Failed to find contract address in tx logs" issue-id)))
         (catch Throwable ex
           (log/errorf ex "issue %s: update-issue-contract-address exception:" issue-id)))))
  (log/info "Exit update-issue-contract-address"))


(defn deploy-pending-contracts
  "Under high-concurrency circumstances or in case geth is in defunct state, a bounty contract may not deploy successfully when the bounty label is addded to an issue. This function deploys such contracts."
  []
  (p :deploy-pending-contracts
     (doseq [{issue-id :issue_id
              issue-number :issue_number
              owner :owner
              owner-address :owner_address
              repo :repo} (db-bounties/pending-contracts)]
       (log/infof "issue %s: Trying to re-deploy failed bounty contract deployment" issue-id)
       (bounties/deploy-contract owner owner-address repo issue-id issue-number))))

(defn self-sign-bounty
  "Walks through all issues eligible for bounty payout and signs corresponding transaction"
  []
  (log/info "In self-sign-bounty")
  (p :self-sign-bounty
     (doseq [{contract-address :contract_address
           issue-id         :issue_id
           payout-address   :payout_address
           repo :repo
           owner :owner
           comment-id :comment_id
           issue-number :issue_number
           balance-eth :balance_eth
           tokens :tokens
              winner-login :winner_login} (db-bounties/pending-bounties)]
       (try
         (let [value (eth/get-balance-hex contract-address)]
           (if (empty? payout-address)
             (do
               (log/warn "issue %s: Cannot sign pending bounty - winner has no payout address" issue-id)
               (github/update-merged-issue-comment owner
                                                   repo
                                                   comment-id
                                                   contract-address
                                                   (eth-decimal->str balance-eth)
                                                   tokens
                                                   winner-login
                                                   true))
             (let [execute-hash (multisig/send-all contract-address payout-address)]
               (log/infof "issue %s: Payout self-signed, called sign-all(%s) tx: %s" issue-id contract-address payout-address execute-hash)
               (db-bounties/update-execute-hash issue-id execute-hash)
               (db-bounties/update-winner-login issue-id winner-login)
               (github/update-merged-issue-comment owner
                                                   repo
                                                   comment-id
                                                   contract-address
                                                   (eth-decimal->str balance-eth)
                                                   tokens
                                                   winner-login
                                                   false))))
         (catch Throwable ex
           (log/error ex "issue %s: self-sign-bounty exception" issue-id)))))
  (log/info "Exit self-sign-bounty"))

(defn update-confirm-hash
  "Gets transaction receipt for each pending payout and updates DB confirm_hash with tranaction ID of commiteth bot account's confirmation."
  []
  (log/info "In update-confirm-hash")
  (p :update-confirm-hash
     (doseq [{issue-id     :issue_id
              execute-hash :execute_hash} (db-bounties/pending-payouts)]
       (log/infof "issue %s: pending payout: %s" issue-id execute-hash)
       (when-let [receipt (eth/get-transaction-receipt execute-hash)]
         (log/infof "issue %s: execution receipt for issue " issue-id receipt)
         (when-let [confirm-hash (multisig/find-confirmation-tx-id receipt)]
           (log/infof "issue %s: confirm hash:" issue-id confirm-hash)
           (db-bounties/update-confirm-hash issue-id confirm-hash)))))
  (log/info "Exit update-confirm-hash"))


(defn update-watch-hash
  "Sets watch-hash to NULL for bounties where watch tx has been mined. Used to avoid unneeded watch transactions in update-bounty-token-balances"
  []
  (p :update-watch-hash
     (doseq [{issue-id :issue_id
              watch-hash :watch_hash} (db-bounties/pending-watch-calls)]
       (log/infof "issue %s: pending watch call %s" issue-id watch-hash)
       (when-let [receipt (eth/get-transaction-receipt watch-hash)]
         (db-bounties/update-watch-hash issue-id nil)))))


(defn older-than-3h?
  [timestamp]
  (let [now (t/now)
        ts (time-coerce/from-date timestamp)
        diff (t/in-hours (t/interval ts now))]
    (println "hour diff:" diff)
    (> diff 3)))

(defn update-payout-receipt
  "Gets transaction receipt for each confirmed payout and updates payout_hash"
  []
  (log/info "In update-payout-receipt")
  (p :update-payout-receipt
     (doseq [{issue-id    :issue_id
           payout-hash :payout_hash
           contract-address :contract_address
           repo :repo
           owner :owner
           comment-id :comment_id
           issue-number :issue_number
           balance-eth :balance_eth
           tokens :tokens
           confirm-id :confirm_hash
           payee-login :payee_login
           updated :updated} (db-bounties/confirmed-payouts)]
    (log/infof "issue %s: confirmed payout: %s" issue-id payout-hash)
    (try
      (if-let [receipt (eth/get-transaction-receipt payout-hash)]
        (let [contract-tokens (multisig/token-balances contract-address)
              contract-eth-balance (eth/get-balance-wei contract-address)]
          (if (or
                (some #(> (second %) 0.0) contract-tokens)
                (> contract-eth-balance 0))
            (do
              (log/infof "issue %s: Contract (%s) still has funds" issue-id contract-address)
              (when (multisig/is-confirmed? contract-address confirm-id)
                (log/infof "issue %s: Detected bounty with funds and confirmed payout, calling executeTransaction" issue-id)
                (let [execute-tx-hash (multisig/execute-tx contract-address confirm-id)]
                  (log/infof "issue %s: execute tx: %s" issue-id execute-tx-hash))))

            (do
              (log/infof "issue %s: Payout has succeeded, payout receipt %s" issue-id receipt)
              (db-bounties/update-payout-receipt issue-id receipt)
              (github/update-paid-issue-comment owner
                                                repo
                                                comment-id
                                                contract-address
                                                (eth-decimal->str balance-eth)
                                                tokens
                                                payee-login))))
        (when (older-than-3h? updated)
          (log/warn "issue %s: Resetting payout hash for issue as it has not been mined in 3h" issue-id)
          (db-bounties/reset-payout-hash issue-id)))
      (catch Throwable ex
        (log/error ex "issue %s: update-payout-receipt exception" issue-id)))))
  (log/info "Exit update-payout-receipt"))

(defn abs
  "(abs n) is the absolute value of n"
  [n]
  (cond
    (not (number? n)) (throw (IllegalArgumentException.
                              "abs requires a number"))
    (neg? n) (- n)
    :else n))


(defn update-bounty-token-balances
  "Helper function for updating internal ERC20 token balances to token
  multisig contract. Will be called periodically for all open bounty
  contracts."
  [issue-id bounty-addr watch-hash]
  #_(log/info "In update-bounty-token-balances for issue" issue-id)
  (doseq [[tla token-data] (token-data/as-map)]
    (try
      (let [balance (multisig/token-balance bounty-addr tla)]
        (when (> balance 0)
          (do
            (log/infof "bounty %s: has %s of token %s" bounty-addr balance tla)
            (let [internal-balance (multisig/token-balance-in-bounty bounty-addr tla)]
              (when (and (nil? watch-hash)
                         (not= balance internal-balance))
                (log/infof "bounty %s: balances not in sync, calling watch" bounty-addr)
                (let [hash (multisig/watch-token bounty-addr tla)]
                  (db-bounties/update-watch-hash issue-id hash)))))))
      (catch Throwable ex
        (log/error ex "bounty %s: update-bounty-token-balances exception" bounty-addr))))
  #_(log/info "Exit update-bounty-token-balances"))


(defn update-contract-internal-balances
  "It is required in our current smart contract to manually update it's internal balance when some tokens have been added."
  []
  (log/info "In update-contract-internal-balances")
  (p :update-contract-internal-balances
     (doseq [{issue-id :issue_id
              bounty-address :contract_address
              watch-hash :watch_hash}
             (db-bounties/open-bounty-contracts)]
       (update-bounty-token-balances issue-id bounty-address watch-hash)))
  (log/info "Exit update-contract-internal-balances"))

(defn get-bounty-funds
  "Get funds in given bounty contract.
  Returns map of asset -> balance
   + key total-usd -> current total USD value for all funds"
  [bounty-addr]
  (let [token-balances (multisig/token-balances bounty-addr)
        eth-balance (read-string (eth/get-balance-eth bounty-addr 6))
        all-funds
        (merge token-balances
               {:ETH eth-balance})]
    (merge all-funds {:total-usd (fiat-util/bounty-usd-value all-funds)})))


(defn update-issue-usd-value
  [bounty-addr]
  (let [funds (get-bounty-funds bounty-addr)]
      (issues/update-usd-value bounty-addr
                               (:total-usd funds))))

(defn update-open-issue-usd-values
  "Sum up current USD values of all crypto assets in a bounty and store to DB"
  []
  (p :update-open-issue-usd-values
     (doseq [{bounty-addr :contract_address}
             (db-bounties/open-bounty-contracts)]
       (update-issue-usd-value bounty-addr))))

(defn float=
  ([x y] (float= x y 0.0000001))
  ([x y epsilon]
   (log/debug x y epsilon)
   (let [scale (if (or (zero? x) (zero? y)) 1 (abs x))]
     (<= (abs (- x y)) (* scale epsilon)))))

(defn map-float= [m1 m2]
  (and (= (set (keys m1)) (set (keys m2)))
       (every? #(float= (get m1 %1) (get m2 %1)) (keys m1))))

(defn update-balances
  []
  (log/info "In update-balances")
  (p :update-balances
     (doseq [{contract-address :contract_address
           owner            :owner
           repo             :repo
           comment-id       :comment_id
           issue-id         :issue_id
           db-balance-eth   :balance_eth
           db-tokens        :tokens
           issue-number     :issue_number} (db-bounties/open-bounty-contracts)]
    (try
      (when comment-id
        (let [balance-eth-str (eth/get-balance-eth contract-address 6)
              balance-eth (read-string balance-eth-str)
              token-balances (multisig/token-balances contract-address)]
          (log/debug "issue" issue-id ": update-balances" balance-eth
                     balance-eth-str token-balances owner repo issue-number)

          (when (or
                  (not (float= db-balance-eth balance-eth))
                  (not (map-float= db-tokens token-balances)))
            (log/info "balances differ")
            (log/info "ETH (db):" db-balance-eth (type db-balance-eth) )
            (log/info "ETH (chain):" balance-eth (type balance-eth) )
            (log/info "ETH cmp:" (float= db-balance-eth balance-eth))
            (log/info "tokens (db):" db-tokens (type db-tokens) (type (:SNT db-tokens)))
            (log/info "tokens (chain):" token-balances (type token-balances) (type (:SNT token-balances)))
            (log/debug "tokens cmp:" (= db-tokens token-balances))

            (issues/update-eth-balance contract-address balance-eth)
            (issues/update-token-balances contract-address token-balances)
            (bounties/update-bounty-comment-image issue-id
                                                  owner
                                                  repo
                                                  issue-number
                                                  contract-address
                                                  balance-eth
                                                  balance-eth-str
                                                  token-balances)
            (github/update-comment owner
                                   repo
                                   comment-id
                                   issue-number
                                   contract-address
                                   balance-eth
                                   balance-eth-str
                                   token-balances)
            (update-issue-usd-value contract-address))))
      (catch Throwable ex 
        (log/error ex "issue %s: update-balances exception" issue-id)))))
  (log/info "Exit update-balances"))


(defn wrap-in-try-catch [func]
  (try
    (func)
    (catch Throwable t
      (log/error t (.getMessage t) (ex-data t)))))

(defn run-tasks [tasks]
  (doall
   (map (fn [func] (wrap-in-try-catch func))
        tasks)))

(defn run-1-min-interval-tasks [time]
  (do
    (log/info "run-1-min-interval-tasks" time)
    ;; TODO: disabled for now. looks like it may cause extraneus
    ;; contract deployments and costs
    (run-tasks
     [deploy-pending-contracts
      update-issue-contract-address
      update-confirm-hash
      update-payout-receipt
      update-watch-hash
      self-sign-bounty
      ])
    (log/info "run-1-min-interval-tasks done")))


(defn run-10-min-interval-tasks [time]
  (do
    (log/info "run-10-min-interval-tasks" time)
    (run-tasks
     [update-contract-internal-balances
      update-balances
      update-open-issue-usd-values])
    (log/info "run-10-min-interval-tasks done")))


(mount/defstate scheduler
  :start (let [every-minute (rest
                             (periodic-seq (t/now)
                                           (t/minutes 1)))
               every-10-minutes (rest
                                 (periodic-seq (t/now)
                                               (t/minutes 10)))
               error-handler (fn [e]
                               (log/error "Scheduled task failed" e)
                               (throw e))
               stop-fn (chime-at every-minute
                                 run-1-min-interval-tasks
                                 {:error-handler error-handler})
               stop-fn2 (chime-at every-10-minutes
                                  run-10-min-interval-tasks
                                  {:error-handler error-handler})]
           (log/info "started scheduler")
           (bounties/update-bounty-issue-titles)
           (fn [] (do (stop-fn) (stop-fn2))))
  :stop (do
          (log/info "stopping scheduler")
          (scheduler)))
