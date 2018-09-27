(ns parallel.core
  (:refer-clojure :exclude [eduction sequence frequencies let slurp
                            count group-by sort min max amap distinct])
  (:require [parallel.foldmap :as fmap]
            [parallel.merge-sort :as msort]
            [parallel.map-combine :as mcombine]
            [parallel.fork-middle :as forkm]
            [clojure.core.reducers :as r]
            [clojure.core.protocols :as p]
            [clojure.java.io :as io]
            [clojure.core :as c])
  (:import
    [parallel.merge_sort MergeSort]
    [parallel.map_combine MapCombine]
    [java.io File FileInputStream]
    [java.util.concurrent.atomic AtomicInteger AtomicLong]
    [java.util.concurrent ConcurrentHashMap ConcurrentLinkedQueue]
    [java.util HashMap Collections Queue Map]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* true)
(def ^:const ncpu (.availableProcessors (Runtime/getRuntime)))

(def ^:dynamic *mutable* false)

(defn- foldable? [coll]
  (or (map? coll)
      (vector? coll)
      (instance? clojure.core.reducers.Cat coll)))

(defn- compose
  "As a consequence, reducef cannot be a vector."
  [xrf]
  (if (vector? xrf)
    ((peek xrf) (nth xrf 0))
    xrf))

(defn xrf
  "Expects a reducing function rf and a list
  of transducers (or comp thereof). Use with
  p/fold to compose any chain of transducers applied to
  a reducing function to run in parallel."
  [rf & xforms]
  (if (empty? xforms)
    rf
    [rf (apply comp xforms)]))

(defn- splitting
  "Calculates split sizes as they would be generated by
  a parallel fold with n=1."
  [coll]
  (iterate
    #(mapcat
       (fn [n] [(quot n 2) (- n (quot n 2))]) %)
    [(c/count coll)]))

(defn show-chunks
  "Shows chunk sizes for the desired chunk number
  on a given collection coll."
  [coll nchunks]
  {:pre [(== (bit-and nchunks (- nchunks)) nchunks)]}
  (->> (splitting coll)
       (take-while #(<= (c/count %) nchunks))
       last))

(defn chunk-size
  "Calculates the necessary chunk-size to obtain
  the given number of splits during a parallel fold.
  nchunks needs to be a power of two."
  [coll nchunks]
  (apply c/max (show-chunks coll nchunks)))

(defn foldvec
  "A general purpose reducers/foldvec taking a generic f
  to apply at the leaf instead of reduce."
  [v n combinef f]
  (c/let [cnt (c/count v)]
    (cond
      (empty? v) (combinef)
      (<= cnt n) (f v)
      :else (c/let [half (quot cnt 2)
                  r1 (subvec v 0 half)
                  r2 (subvec v half cnt)
                  fc (fn [v] #(foldvec v n combinef f))]
              (#'r/fjinvoke
                #(c/let [f1 (fc r1)
                       t2 (#'r/fjtask (fc r2))]
                   (#'r/fjfork t2)
                   (combinef (f1) (#'r/fjjoin t2))))))))

(defprotocol Folder
  (folder [coll]
          [coll nchunks]))

(extend-protocol Folder
  Object
  (folder
    ([coll]
     (reify r/CollFold
       (coll-fold [this n combinef reducef]
         (r/reduce reducef (combinef) coll))))
    ([coll nchunks]
     (reify r/CollFold
       (coll-fold [this _ combinef reducef]
         (r/reduce reducef (combinef) coll)))))
  clojure.lang.IPersistentVector
  (folder
    ([coll]
     (reify r/CollFold
       (coll-fold [this n combinef reducef]
         (foldvec coll n combinef #(r/reduce (compose reducef) (combinef) %)))))
    ([coll nchunks]
     (reify r/CollFold
       (coll-fold [this _ combinef reducef]
         (foldvec coll (chunk-size coll nchunks) combinef #(r/reduce (compose reducef) (combinef) %))))))
  clojure.lang.PersistentHashMap
  (folder
    ([coll]
     (reify r/CollFold
       (coll-fold [m n combinef reducef]
         (fmap/fold coll 512 combinef reducef))))
    ([coll nchunks]
     (reify r/CollFold
       (coll-fold [m n combinef reducef]
         (fmap/fold coll 512 combinef reducef))))))

(defn fold
  "Like reducers fold, but with stateful transducers support.
  Expect reducef to be built using p/xrf to defer initialization.
  n is the number-of-chunks instead of chunk size.
  n must be a power of 2 and defaults to 32."
  ([reducef coll]
   (fold (first reducef) reducef coll))
  ([combinef reducef coll]
   (fold 32 combinef reducef coll))
  ([n combinef reducef coll]
   (r/fold ::ignored combinef reducef (folder coll n))))

(defn count
  ([xform coll]
   (count 32 xform coll))
  ([n xform coll]
   (c/let [coll (if (foldable? coll) coll (into [] coll))
         cnt (AtomicLong. 0)
         reducef (xrf (fn [_ _] (.incrementAndGet cnt)) xform)
         combinef (constantly cnt)]
     (fold n combinef reducef coll)
     (.get cnt))))

(extend-protocol clojure.core.protocols/IKVReduce
  java.util.Map
  (kv-reduce
    [amap f init]
    (c/let [^java.util.Iterator iter (.. amap entrySet iterator)]
      (loop [ret init]
        (if (.hasNext iter)
          (c/let [^java.util.Map$Entry kv (.next iter)
                ret (f ret (.getKey kv) (.getValue kv))]
            (if (reduced? ret)
              @ret
              (recur ret)))
          ret)))))

(defn group-by
  "Similar to core/group-by, but executes in parallel.
  It takes an optional list of transducers to apply to the
  items in coll before generating the groups. Differently
  from core/group-by, the order of the items in each
  value vector can change between runs. It's generally 2x-5x faster
  than core/group-by (without xducers). If dealing with a Java mutable
  map with Queue type values is not a problem, a further 2x
  speedup can be achieved by:
        (binding [p/*mutable* true] (p/group-by f coll))
  Restrictions: it does not support nil values."
  [f coll & xforms]
  (c/let [coll (if (foldable? coll) coll (into [] coll))
        m (ConcurrentHashMap. (quot (c/count coll) 2) 0.75 ncpu)
        combinef (fn ([] m) ([m1 m2]))
        rf (fn [^Map m x]
             (c/let [k (f x)
                   ^Queue a (or (.get m k) (.putIfAbsent m k (ConcurrentLinkedQueue. [x])))]
               (when a (.add a x))
               m))]
    (fold combinef (apply xrf rf xforms) coll)
    (if *mutable* m (persistent! (reduce-kv (fn [m k v] (assoc! m k (vec v))) (transient {}) m)))))

(defn frequencies
  "Like clojure.core/frequencies, but executes in parallel.
  It takes an optional list of transducers to apply to coll before
  the frequency is calculated. It does not support nil values."
  [coll & xforms]
  (c/let [coll (if (foldable? coll) coll (into [] coll))
        m (ConcurrentHashMap. (quot (c/count coll) 2) 0.75 ncpu)
        combinef (fn ([] m) ([_ _]))
        rf (fn [^Map m k]
             (c/let [^AtomicInteger v (or (.get m k) (.putIfAbsent m k (AtomicInteger. 1)))]
               (when v (.incrementAndGet v))
               m))]
    (fold combinef (apply xrf rf xforms) coll)
    (if *mutable* m (into {} m))))

(defn update-vals
  "Use f to update the values of a map in parallel. It performs well
  with non-trivial f, otherwise is outperformed by reduce-kv.
  For larger maps (> 100k keys), the final transformation
  from mutable to persistent dominates over trivial f trasforms.
  You can access the raw mutable java.util.Map by setting the dynamic
  binding *mutable* to true. Restrictions: does not support nil values."
  [^Map input f]
  (c/let [ks (into [] (keys input))
        output (ConcurrentHashMap. (c/count ks) 1. ncpu)]
    (r/fold
      (fn ([] output) ([_ _]))
      (fn [^Map m k]
        (.put m k (f (.get input k)))
        m)
      ks)
    (if *mutable* output (into {} output))))

(defn sort
  "Splits input coll into chunk of 'threshold' (default 8192)
  size then sorts chunks in parallel. Input needs conversion into a native
  array before splitting. More effective for large colls
  (> 1M elements) or non trivial comparators. Set *mutable* to 'true'
  to access the raw results as a mutable array."
  ([coll]
   (sort 8192 < coll))
  ([cmp coll]
   (sort 8192 cmp coll))
  ([threshold cmp ^Object coll]
   (c/let [a (if (.. coll getClass isArray) coll (to-array coll))]
     (msort/sort threshold cmp a)
     (if *mutable* a (into [] a)))))

(defn slurp
  "Loads a java.io.File in parallel. By default,
  the loaded byte array is converted into an UTF-8 string.
  It takes an optional parsef function of the byte array for
  additional (or different) processing. When *mutable* var
  is true it returns the byte array as is."
  ([file]
   (slurp file (fn parsef [^bytes a] (String. a "UTF-8"))))
  ([^File file parsef]
   (c/let [size (.length file)
           threshold (quot size (* 4 ncpu))
           a (byte-array size)]
     (mcombine/map
       (fn read-chunk [low high]
         (c/let [fis (FileInputStream. file)]
           (try
             (.skip fis low)
             (.read fis a low (- high low))
             (finally (.close fis)))))
       (fn [_ _])
       threshold size)
     (if *mutable* a (parsef a)))))

(defn unchunk-map [f coll]
  (lazy-seq
    (when-let [s (seq coll)]
      (cons
        (f (first s))
        (unchunk-map f (rest s))))))

(defn external-sort
  "Allows large datasets (that would otherwise not fit into memory)
  to be sorted in parallel. It performs the following on a vector of 'ids'
  and 'fetchf', a function from chunk->data:
  * split ids into chunks of approximate size 'n'
  * call 'fetchf' on a chunk and expects actual data in return
  * sort actual data using 'cmp' ('compare' by default)
  * save result to temporary files (deleted when the JVM exits)
  * lazily concat files in order as they are requested"
  ([fetchf ids]
   (external-sort compare fetchf ids))
  ([cmp fetchf ids]
   (external-sort 512 compare fetchf ids))
  ([n cmp fetchf ids]
   (letfn [(save-chunk! [data]
             (c/let [file (File/createTempFile "mergesort-" ".tmp")]
               (with-open [fw (io/writer file)]
                 (binding [*out* fw] (pr data)))
               [(first data) file]))]
     (->>
       (r/fold
         n concat
         (fn [chunk] (->> chunk fetchf (c/sort cmp) save-chunk! vector))
         (reify r/CollFold
           (coll-fold [this n combinef f]
             (foldvec (into [] ids) n combinef f))))
       (sort-by first cmp)
       (unchunk-map #(read-string (slurp (last %))))
       (mapcat identity)))))

(defn- nearest-pow2 [x]
  (int (Math/pow 2 (- 32 (Integer/numberOfLeadingZeros x)))))

(defn- fold-adapt
  "Select r/fold or p/fold based on presence of xforms.
  Adapt p/fold chunk number to the requested chunk-size."
  [rf init coll chunk-size xforms]
  (c/let [v (if (vector? coll) coll (into [] coll))]
    (if (seq xforms)
      (fold (nearest-pow2 (/ (c/count v) chunk-size))
            (fn ([] init) ([a b] (rf a b)))
            (apply xrf rf xforms)
            v)
      (r/fold chunk-size (fn ([] init) ([a b] (rf a b))) rf v))))

(defn min
  "Find the min in coll in parallel. Accepts optional
  transducers to apply to coll before searching the min.
  Effective for coll size >10k items. 4000 is an approximate
  minimal chunk size."
  [coll & xforms]
  (fold-adapt c/min ##Inf coll 4000 xforms))

(defn max
  "Find the min in coll in parallel. Accepts optional
  transducers to apply to coll before searching the min.
  Effective for coll size >10k items. 4000 is an approximate
  minimal chunk size."
  [coll & xforms]
  (fold-adapt c/max ##-Inf coll 4000 xforms))

(defn amap
  "Applies f in parallel to the elements in the array.
  The threshold decides how big a chunk of computation should be before
  going sequential and it's given a default based on the number of
  available cores."
  ([f ^objects a]
   (amap (quot (alength a) (* 2 ncpu)) f a))
  ([threshold f ^objects a]
   (mcombine/map
     (fn [low high]
       (loop [idx low]
         (when (< idx high)
           (aset a idx (f (aget a idx)))
           (recur (unchecked-inc idx)))))
     (fn [_ _])
     threshold (alength a))
   a))

(defn distinct
  "Returns a non-lazy and unordered sequence of the distinct elements in coll.
  It does not support null values that need to be removed before calling.
  Also accepts an optional list of transducers that is applied before removing
  duplicates. When bound with *mutable* dynamic var, returns a java.util.Set."
  [coll & xforms]
  (c/let [coll (if (foldable? coll) coll (into [] coll))
        m (ConcurrentHashMap. (quot (c/count coll) 2) 0.75 ncpu)
        combinef (fn ([] m) ([_ _]))
        rf (fn [^Map m k] (.put m k 1) m)]
    (fold combinef (apply xrf rf xforms) coll)
    (if *mutable* (.keySet m) (enumeration-seq (.keys m)))))

(defn arswap
  "Arrays reverse-swap of the regions identified by:
  [low, low + radius]....[high - radius, high]
  Takes transformation f to apply to each item.
  Preconditions: (pos? (alength a)), (< low high), (pos? radius)"
  [f low high radius ^objects a]
  (loop [left low right high]
    (when (and (<= left right) (< left (+ low radius)))
      (c/let [tmp (f (aget a left))]
        (aset a left (f (aget a right)))
        (aset a right tmp)
        (recur (inc left) (dec right))))) a)

(defn- sequential-armap
  "Reverse an array."
  [f ^objects a]
  (loop [i 0]
    (when (<= i (quot (alength a) 2))
      (c/let [tmp (f (aget a i))
            j (- (alength a) i 1)]
        (aset a i (f (aget a j)))
        (aset a j tmp))
      (recur (unchecked-inc i)))))

(defn armap
  "Applies f in parallel over the reverse of the array.
  The threshold decides how big is the chunk of sequential
  computation, with a default of alength / twice the CPUs.
  Performs better than sequential for non-trivial transforms."
  ([f ^objects a]
   (when a
     (armap (quot (alength a) (* 2 ncpu)) f a)))
  ([threshold f ^objects a]
   (when (and a (pos? (alength a)))
     (if (pos? threshold)
       (forkm/submit f arswap threshold a)
       (sequential-armap f a))) a))

(defn- should-be [p msg form]
  (when-not p
    (c/let [line (:line (meta form))
          msg (format "%s requires %s in %s:%s" (first form) msg *ns* line)]
      (throw (IllegalArgumentException. msg)))))

(defmacro let [bindings & body]
  (should-be (vector? bindings) "a vector for its bindings" &form)
  (should-be (even? (c/count bindings)) "an even number of forms in bindings" &form)
  (c/let [ks (take-nth 2 bindings)
          vs (take-nth 2 (rest bindings))
          ts (take (c/count ks) (repeatedly gensym))]
    `(c/let ~(vec (interleave ts (map #(list 'future %) vs)))
       (c/let ~(vec (interleave ks (map #(list 'deref %) ts)))
         ~@body))))
