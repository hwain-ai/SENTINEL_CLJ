(ns sentinel-clj.rendering.canonical-decimal
  (:require [clojure.string :as string]
            [sentinel-clj.crap.models :as models]))

(def scale 1000000000000N)
(def fractional-digits 12)

(defn- validate-fraction [numerator denominator]
  (when-not (and (integer? numerator) (not (neg? numerator)))
    (throw (models/contract-error :numerator-out-of-range
                                  "numerator must be a nonnegative integer")))
  (when-not (and (integer? denominator) (pos? denominator))
    (throw (models/contract-error :denominator-out-of-range
                                  "denominator must be a positive integer"))))

(defn- rounded-scaled-value [numerator denominator]
  (let [scaled (* (bigint numerator) scale)
        quotient (quot scaled denominator)
        remainder (rem scaled denominator)
        comparison (compare (* 2 remainder) denominator)]
    (if (or (pos? comparison)
            (and (zero? comparison) (odd? quotient)))
      (inc quotient)
      quotient)))

(defn- padded-fraction [value]
  (let [text (str value)
        missing (- fractional-digits (count text))]
    (str (apply str (repeat missing "0")) text)))

(defn render [numerator denominator]
  (validate-fraction numerator denominator)
  (let [rounded (rounded-scaled-value numerator denominator)
        integer-part (quot rounded scale)
        fraction-part (rem rounded scale)
        fraction-text (string/replace (padded-fraction fraction-part) #"0+$" "")]
    (if (empty? fraction-text)
      (str integer-part)
      (str integer-part "." fraction-text))))
