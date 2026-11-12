(ns cljev.live-test
  "Tests that call the TypeSafe API. They need TYPESAFE_API_KEY, they are
  billed, and their assertions depend on the model's answers. Run them with
  `CLJEV_LIVE_TESTS=1 bb test:live`; `bb test` and `bb check` never run them."
  (:require [cljev.core :as jev]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io ByteArrayOutputStream DataOutputStream)
           (java.util.zip CRC32 DeflaterOutputStream)))

(def client (delay (jev/client {:timeout-ms 30000})))

(def message
  "I was charged twice for my subscription this month. Please refund the extra charge.")

;; A PNG encoder small enough to live here, so the image tests need no
;; fixture file and no image library.

(defn- png-chunk ^bytes [^String kind ^bytes data]
  (let [out (ByteArrayOutputStream.)
        kind (.getBytes kind "US-ASCII")
        crc (doto (CRC32.)
              (.update kind 0 (alength kind))
              (.update data 0 (alength data)))]
    (doto (DataOutputStream. out)
      (.writeInt (alength data))
      (.write kind 0 (alength kind))
      (.write data 0 (alength data))
      (.writeInt (unchecked-int (.getValue crc))))
    (.toByteArray out)))

(defn- png
  "An RGB PNG of `width` by `height` whose pixel at x, y is `(pixel x y)`, a
  vector of red, green, and blue from 0 to 255."
  ^bytes [width height pixel]
  (let [header (let [out (ByteArrayOutputStream.)]
                 (doto (DataOutputStream. out)
                   (.writeInt width)
                   (.writeInt height)
                   (.writeByte 8)    ; bit depth
                   (.writeByte 2)    ; color type: RGB
                   (.writeByte 0)
                   (.writeByte 0)
                   (.writeByte 0))
                 (.toByteArray out))
        pixels (let [out (ByteArrayOutputStream.)]
                 (with-open [deflate (DeflaterOutputStream. out)]
                   (dotimes [y height]
                     (.write deflate 0)   ; filter: none
                     (dotimes [x width]
                       (doseq [c (pixel x y)]
                         (.write deflate (int c))))))
                 (.toByteArray out))
        out (ByteArrayOutputStream.)]
    (doseq [^bytes part [(byte-array (map unchecked-byte [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A]))
                         (png-chunk "IHDR" header)
                         (png-chunk "IDAT" pixels)
                         (png-chunk "IEND" (byte-array 0))]]
      (.write out part 0 (alength part)))
    (.toByteArray out)))

;; A white 320 by 320 image with a red rectangle from 30% to 70% across and
;; 20% to 50% down.
(def red-rectangle
  (delay (png 320 320 (fn [x y]
                        (if (and (<= 96 x 223) (<= 64 y 159))
                          [220 30 30]
                          [255 255 255])))))

(defn- near? [expected actual]
  (< (abs (- expected actual)) 0.12))

(deftest lists-models
  (let [models (jev/models @client)]
    (is (seq models))
    (is (every? (comp string? :name) models))
    (is (re-find #"^req_" (:request-id (meta models))))))

(deftest answers-each-question-type
  (let [result (jev/ask @client message
                        {:billing (jev/noul "Is this message about billing?")
                         :team (jev/choice "Which team should handle this message?"
                                           [[:technical "Bugs, outages, and how to use the product"]
                                            [:billing "Charges, refunds, invoices, and payment methods"]
                                            [:sales "Pricing, plans, and new purchases"]])
                         :urgency (jev/score "How urgent is this message?"
                                             ["Can wait" "This week" "Today"])})
        {:keys [billing team urgency]} (:answers result)]
    (is (string? (:model result)))
    (is (pos? (get-in result [:usage :input-tokens])))
    (is (re-find #"^req_" (:request-id (meta result))))
    (is (= :noul (:type billing)))
    (is (> (:noul billing) 0.8))
    (is (= :billing (:choice team)))
    (is (= #{:technical :billing :sales} (set (keys (:probabilities team)))))
    (is (<= 0 (:score urgency) 2))
    (is (= #{0 1 2} (set (keys (:probabilities urgency)))))
    (is (= {0 "Can wait" 1 "This week" 2 "Today"} (:legend urgency)))))

(deftest accepts-a-large-ordered-choice
  (let [colors [:red :orange :yellow :green :blue :purple :pink :brown :black
                :white :gray :teal :navy :maroon :olive :beige]
        answer (jev/ask-one @client "The sky on a clear day at noon."
                            (jev/choice "What color is the thing described?" colors))]
    (is (= :blue (:choice answer)))
    (is (= (set colors) (set (keys (:probabilities answer)))))))

(deftest accepts-a-compressed-body
  (let [filler (apply str (repeat 300 "The quarterly report was filed on time. "))
        state (str filler "The office dog is named Biscuit. " filler)
        answer (jev/ask-one @client state (jev/noul "Is the office dog named Biscuit?"))]
    (is (> (count state) (* 8 jev/compression-threshold)))
    (is (> (:noul answer) 0.8))))

(deftest reports-errors
  (let [e (try (jev/ask @client "s" {:q (jev/noul "?")} {:model "no-such-model"})
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= ::jev/api-error (:type (ex-data e))))
    (is (<= 400 (:status (ex-data e)) 499))
    (is (re-find #"^req_" (:request-id (ex-data e))))))

(deftest asks-about-many-states
  (let [results (jev/ask-many @client
                              ["Where is my refund?" "I love this product." "What time is it?"]
                              {:question (jev/noul "Is this text a question?")}
                              {:concurrency 3})]
    (is (= [true false true]
           (map #(> (get-in % [:answers :question :noul]) 0.5) results)))))

(deftest reads-images
  (let [questions {:red (jev/noul "Is there a red rectangle in the image?")
                   :blue (jev/noul "Is there a blue circle in the image?")}
        looks-right? (fn [result]
                       (and (> (get-in result [:answers :red :noul]) 0.7)
                            (< (get-in result [:answers :blue :noul]) 0.3)))]
    (testing "placed in the state, uploaded as multipart"
      (is (looks-right? (jev/ask @client {:image (jev/image @red-rectangle :png)}
                                 questions {:images? true}))))
    (testing "uploaded as base64"
      (is (looks-right? (jev/ask @client {:image (jev/image @red-rectangle :png)}
                                 questions {:images? true :upload :base64}))))
    (testing "referenced from text"
      (let [img (jev/image @red-rectangle :png)]
        (is (looks-right? (jev/ask @client (str "The image to look at: " (:ref img))
                                   questions {:assets [img] :images? true})))))))

(deftest locates-a-box
  (let [answer (jev/ask-one @client {:image (jev/image @red-rectangle :png)}
                            (jev/box "the red rectangle")
                            {:images? true})
        {:keys [top bottom left right]} (:box answer)]
    (is (= :box (:type answer)))
    (is (near? 0.2 top))
    (is (near? 0.5 bottom))
    (is (near? 0.3 left))
    (is (near? 0.7 right))))
