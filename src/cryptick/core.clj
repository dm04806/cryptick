; Copyright (C) 2014 John. P Hackworth <jph@hackworth.be>
;
; This Source Code Form is subject to the terms of the Mozilla Public
; License, v. 2.0. If a copy of the MPL was not distributed with this
; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns cryptick.core
  (:require [org.httpkit.client :as http]
            [cheshire.core :refer :all]
            [clojure.string :refer [upper-case lower-case]]))


(defn parse-numbers
  "JSON reader helper which implements string to double decoding. This
  function is used to normalize string encoded numerics into raw JVM
  numerics for better use from Clojure clients."

  [m]
  (into {}
        (for [[k v] m]
          [k
           (cond (map? v)
                   (parse-numbers v)

                 (or (nil? v)
                     (number? v))
                   v

                 (and (string? v)
                      (->> v
                           (re-matches #"^(\d*\.?\d*)$")))
                   (Double/parseDouble v)

                 true
                   v)])))


(def default-options
  (atom
   {:content-type "application/json"
    :user-agent "cryptick 0.1.3"
    :insecure? false
    :keepalive 1000
    }))

(def feeds
  (atom
   {:btce
      {:url "https://btc-e.com/api/2"
       :method :get
       :pair-required? true
       :pair-example "btc_usd"
       :parse-for (fn [ticker pair]
                    (:ticker ticker))
       :url-for (fn [xchng pair]
                  (format "%s/%s/ticker"
                          (:url xchng)
                          (-> pair name lower-case)))}

    :bter
      {:url "http://data.bter.com/api/1/ticker"
       :method :get
       :pair-required? true
       :pair-example "doge_btc"
       :parse-for (fn [ticker pair]
                    (parse-numbers
                     (dissoc ticker :result)))
       :url-for (fn [xchng pair]
                  (format "%s/%s"
                          (:url xchng)
                          pair))}

    :havelock
      {:url "https://www.havelockinvestments.com/r/tickerfull"
       :method :post
       :parse-for (fn [ticker pair]
                    (if (nil? pair)
                      (parse-numbers ticker)
                      (get (parse-numbers ticker)
                           (keyword (upper-case pair)))))
       :url-for (fn [xchng pair]
                  (:url xchng))
       :http-options (fn [xchng pair]
                       (if (nil? pair)
                         {:method :get}
                         {:form-params {:symbol pair}
                          :method :post}))}

    :bitstamp
      {:url "https://www.bitstamp.net/api/ticker/"
       :method :get
       :parse-for (fn [ticker pair]
                    (parse-numbers ticker))
       :url-for (fn [xchng pair]
                  (:url xchng))
       :http-options (fn [xchng pair]
                       {:pair "btc_usd"
                        :method (:method xchng)})}

    :okcoin
      {:url "https://www.okcoin.com/api/ticker.do?symbol="
       :method :get
       :pair-required? true
       :pair-example "ltc_cny"
       :parse-for (fn [ticker pair]
                    (parse-numbers (:ticker ticker)))
       :url-for (fn [xchng pair]
                  (format "%s%s"
                          (:url xchng)
                          (lower-case pair)))}

    :bitcoincharts-weighted-prices
      {:url "http://api.bitcoincharts.com/v1/weighted_prices.json"
       :method :get
       :limits "Max queries, once every 15 minutes"
       :parse-for (fn [ticker pair]
                    (if-not (nil? pair)
                      (->> pair
                           (upper-case)
                           (keyword)
                           [:timestamp]
                           (select-keys ticker)
                           (parse-numbers))
                      (parse-numbers ticker)))
       :url-for (fn [xchng pair]
                  (:url xchng))}

    :bitcoincharts-markets
      {:url "http://api.bitcoincharts.com/v1/markets.json"
       :method :get
       :limits "Max queries, once every 15 minutes"
       :parse-for (fn [ticker pair]
                    (if-not (nil? pair)
                      (->> ticker
                           (filter #(= pair (:symbol %)))
                           (first))
                      ticker))
       :url-for (fn [xchng pair]
                  (:url xchng))}}))


(defn parse-for
  "Parses a ticker query to the argument exchange given a pair"

  [exchange ticker & [pair]]
  ((-> @feeds
       (get exchange)
       (get :parse-for))
   ticker pair))


(defn url-for
  "Builds the query URL for a given exchange and ticker symbol"

  [exchange & [pair]]
  (if-let [exchange (get @feeds exchange)]
    ((get exchange :url-for)
     exchange pair)))


(defn http-options-for
  "Builds an options structure specific to making requests of the
  indicated exchange and ticker."

  [exchange & [pair]]
  (let [ex (get @feeds exchange)]
    (merge @default-options
           {:pair pair
            :exchange exchange
            :method (:method ex)}
           (if-let [optsfn (get exchange :http-options)]
             (optsfn exchange pair)))))


(defn callback
  [{:keys [status headers body error opts] :as response}]

  (if error
    (-> "Request failed, error: %s"
        (format error)
        (Exception.)
        (throw))

    (case status
      200 (let [{:keys [pair exchange]} (:opts response)
                ticker (parse-string body true)]
            (if-not (nil? ticker)
              (parse-for exchange ticker pair)))

      (-> "Request failed, response code: %s"
          (format status)
          (Exception.)
          (throw)))))


(defn ticker
  [exchange & [pair]]
  (let [ex (get @feeds exchange)]
    (when-not ex
      (-> "Invalid exchange specified: %s"
          (format exchange)
          (Exception.)
          (throw)))

    (when (and (:pair-required? ex)
               (nil? pair))
      (-> "Currency pair must be specified for %s. Example: \"%s\""
          (format exchange (:pair-example ex))
          (Exception.)
          (throw)))

    (let [options (http-options-for exchange pair)
          url     (url-for exchange pair)]
      (case (:method options)
        :get  (http/get url options callback)
        :post (http/post url options callback)))))
