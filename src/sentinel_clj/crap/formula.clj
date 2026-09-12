(ns sentinel-clj.crap.formula
  (:require [sentinel-clj.crap.models :as models]
            [sentinel-clj.rendering.canonical-decimal :as decimal]))

(def gate-maximum 8)

(defn- require-input-integer [value not-integer-code range-code minimum label]
  (when-not (integer? value)
    (throw (models/contract-error not-integer-code (str label " must be an integer"))))
  (models/require-safe-integer value minimum range-code label))

(defn- validate-inputs [complexity covered total]
  (require-input-integer complexity
                         :cyclomaticComplexityNotInteger
                         :cyclomaticComplexityOutOfRange
                         1
                         "cyclomaticComplexity")
  (require-input-integer covered
                         :coveredUnitsNotInteger
                         :coveredUnitsOutOfRange
                         0
                         "coveredUnits")
  (require-input-integer total
                         :totalUnitsNotInteger
                         :totalUnitsOutOfRange
                         1
                         "totalUnits")
  (when (> covered total)
    (throw (models/contract-error :coveredUnitsExceedTotalUnits
                                  "coveredUnits must not exceed totalUnits"))))

(defn- reduced-fraction [numerator denominator]
  (let [divisor (.gcd (biginteger numerator) (biginteger denominator))]
    [(quot numerator divisor) (quot denominator divisor)]))

(defn calculate [complexity covered total]
  (validate-inputs complexity covered total)
  (let [cc (bigint complexity)
        uncovered (- (bigint total) (bigint covered))
        denominator (*' total total total)
        numerator (+ (*' cc cc uncovered uncovered uncovered)
                     (*' cc denominator))
        [reduced-numerator reduced-denominator] (reduced-fraction numerator denominator)]
    {:numerator reduced-numerator
     :denominator reduced-denominator
     :decimal (decimal/render reduced-numerator reduced-denominator)
     :pass? (<= numerator (*' gate-maximum denominator))}))
