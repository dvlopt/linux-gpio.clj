(ns dvlopt.linux.gpio

  "This namespace provides utilities for opening a GPIO device in order to :
  
       - Request some information (eg. about a line).
       - Request a handle for driving one or several lines at once.
       - Request a watcher for efficiently monitoring one or several lines.

   A handle controls all the lines it is responsible for at once. Whether those lines
   are actually driven exactly at the same time, atomically, depends on the underlying
   driver and is opaque to this library.

   A watcher is used essentially for interrupts. Requested events, such as a line
   transitioning from false to true, are queued by the kernel until read. However, Linux
   not being a real-time OS, such \"interrupts\" are imperfect and should not be used for
   critical tasks where a simple microcontroller will be of a better fit.
  
   Lines are abstracted by associating a line number with a tag (ie. any value meant to be
   more representative of what the line does, often a keyword).
  
   As usual, all IO functions might throw if anything goes wrong.
  
   All functions and important concepts are specified with clojure.spec."

  {:author "Adam Helinski"}

  (:refer-clojure :exclude [read])
  (:require [clojure.spec.alpha :as s]
            [dvlopt.void        :as void])
  (:import (io.dvlopt.linux.gpio GpioBuffer
                                 GpioChipInfo
                                 GpioDevice
                                 GpioEdgeDetection
                                 GpioEvent
                                 GpioEventHandle
                                 GpioEventRequest
                                 GpioEventWatcher
                                 GpioHandle
                                 GpioHandleRequest
                                 GpioFlags
                                 GpioLine
                                 GpioLineInfo)
           java.lang.AutoCloseable))




;;;;;;;;;; Declarations


(declare IBuffer
         IBuffereable
         IHandle
         IWatcher)




;;;;;;;;;; Specs for IO related objects


(s/def ::auto-closeable

  #(instance? AutoCloseable
              %))


(s/def ::buffer

  #(satisfies? IBuffer
               %))


(s/def ::buffereable

  (s/and ::auto-closeable
         #(satisfies? IBuffereable
                      %)))


(s/def ::device

  (s/and ::auto-closeable
         #(instance? GpioDevice
                     %)))


(s/def ::handle

  (s/and ::auto-closeable
         ::buffereable
         #(satisfies? IHandle
                      %)))


(s/def ::watcher

  (s/and ::auto-closeable
         ::buffereable
         #(satisfies? IWatcher
                      %)))


;;;;;;;;;; Specs for concepts relevant to this library


(s/def ::active-low?

  boolean?)


(s/def ::chip-description

  (s/keys :req [::n-lines]
          :opt [::label
                ::name]))


(s/def ::consumer

  (s/and string?
         not-empty))


(s/def ::direction

  #{:input
    :output})


(s/def ::edge

  #{:falling
    :rising})


(s/def ::edge-detection

  #{:rising
    :falling
    :rising-and-falling})


(s/def ::event

  (s/keys :req [::edge
                ::nano-timestamp
                ::tag]))


(s/def ::flags

  (s/keys :opt [::active-low?
                ::direction
                ::open-drain?
                ::open-source?]))


(s/def ::label

  (s/and string?
         not-empty))


(s/def ::line-description

  (s/keys :req [::active-low?
                ::direction
                ::line-number
                ::open-drain?
                ::open-source?
                ::used?]
          :opt [::consumer
                ::name]))


(s/def ::line-number

  (s/int-in 0
            65))


(s/def ::n-lines

  (s/int-in 0
            65))


(s/def ::name

  (s/and string?
         not-empty))


(s/def ::nano-timestamp

  (s/and int?
         #(>= %
              0)))


(s/def ::open-drain?

  boolean?)


(s/def ::open-source?

  boolean?)


(s/def ::state

  boolean?)


(s/def ::tag

  any?)


(s/def ::tag->state

  (s/map-of ::tag
            ::state))


(s/def ::tags

  (s/coll-of ::tag))


(s/def ::timeout-ms

  int?)


(s/def ::used?

  boolean?)




;;;;;;;;;; Default values for options


(def defaults

  "Default values for argument options."

  {::edge-detection :rising-and-falling})




;;;;;;;;;;


(s/fdef close

  :args (s/cat :resource ::auto-closeable))


(defn close

  "Closes a GPIO resource such as a device or a handle."

  [^AutoCloseable resource]

  (.close resource)
  nil)




;;;;;;;;;;


(s/fdef device

  :args (s/cat :device-path (s/or :string (s/and string?
                                                 not-empty)
                                  :number number?))
  :ret  ::device)


(defn device

  "Opens a GPIO device either by specifying a full path or by giving the number of the
   device.
  
   Read permission must be granted, which is enough even for writing to outputs.

   Closing a GPIO device does not close related resources (ie. obtained handles and watchers)."

  ^GpioDevice

  [device-path]

  (if (number? device-path)
    (GpioDevice. ^int device-path)
    (GpioDevice. ^String device-path)))




(s/fdef describe-chip

  :args (s/tuple ::device)
  :ret  ::chip-description)


(defn describe-chip

  "Requests a description of the given GPIO device."

  [^GpioDevice device]

  (let [info (.requestChipInfo device)]
    (void/assoc-some {::n-lines (.getLines info)}
                     ::label (.getLabel info)
                     ::name  (.getName  info))))




(s/fdef describe-line

  :args (s/tuple ::device
                 ::line-number)
  :ret  ::line-description)


(defn describe-line 

  "Requests a description of the given line."

  [^GpioDevice device line-number]

  (let [info  (.requestLineInfo device
                                line-number)
        flags (.getFlags info)]
    (void/assoc-some {::active-low?  (.isActiveLow flags)
                      ::direction    (if (.isInput flags)
                                       :input
                                       :output)
                      ::line-number  (.getLine info)
                      ::open-drain?  (.isOpenDrain flags)
                      ::open-source? (.isOpenSource flags)
                      ::used?        (.isUsed info)}
                     ::consumer (.getConsumer info)
                     ::name     (.getName info))))




;;;;;;;;;;


(defn- -flags

  ;; Produces a GpioFlags given options.
  
  ^GpioFlags

  [opts]

  (let [^GpioFlags flags (GpioFlags.)]
    (when (::active-low? opts)
      (.setActiveLow flags
                     true))
    (when-some [direction (::direction opts)]
      (condp identical?
             direction
        :input  (.setInput  flags)
        :output (.setOutput flags)))
    (when (::open-drain? opts)
      (.setOpenDrain flags
                     true))
    (when (::open-source? opts)
      (.setOpenSource flags
                      true))
    flags))




;;;;;;;;;;


(defprotocol ^:private IPure

  ;; Retrieves the raw java type from the original library

  (^:private -raw-type [this]))




;;;;;;;;;;


(defn- -get-gpio-line

  ;; Retrieves a GpioLine given a tag or throw if not found.

  [tag->GpioLine tag]

  (when-not (contains? tag->GpioLine
                       tag)

    (throw (IllegalArgumentException. (str "GPIO buffer is not responsible for tag -> "
                                           tag))))
  (get tag->GpioLine
       tag))




(defprotocol IBuffereable

  "For objects that can work with a GPIO buffer."

  (buffer [this]

    "Produces a GPIO buffer representing the state of up to 64 lines.

     It implements `IBuffer` and works with tags associated with `this`.

     It does not do any IO on its own as it is meant to be used in conjunction with a
     handle or a watcher."))




(defprotocol IBuffer

  "Controlling a GPIO buffer before or after doing some IO.
  
   Ex. (write some-handle
              (-> some-buffer
                  (clear-lines)
                  (set-lines {:green-led true
                              :red-led   false})))"

  (clear-lines [buffer]

    "Sets all lines of the given buffer to false.")


  (get-line [buffer tag]

    "Retrieves the state of a single line from the given buffer.")


  (get-lines [buffer]
             [buffer tags]

    "Retrieves the state of several lines (or all of them if nothing is specified) from the given buffer.")


  (set-line [buffer tag state]

    "Sets the state of a single line in the given buffer.")


  (set-lines [buffer tag->state]

    "Sets the state of several lines in the given buffer.")


  (toggle-line [buffer tag]

    "Toggles the state of a single line in the given buffer.")


  (toggle-lines [buffer]
                [buffer tags]

    "Toggles the state of several lines in the given buffer."))




(s/fdef clear-lines

  :args (s/tuple ::buffer)
  :ret  ::buffer)


(s/fdef get-line

  :args (s/tuple ::buffer
                 ::tag)
  :ret  ::buffer)


(s/fdef get-lines

  :args (s/tuple ::buffer
                 ::tags)
  :ret  ::tag->state)


(s/fdef set-line

  :args (s/tuple ::buffer
                 ::tag
                 ::state)
  :ret  ::buffer)


(s/fdef set-lines

  :args (s/tuple ::buffer
                 ::tag->state)
  :ret  ::buffer)


(s/fdef toggle-line

  :args (s/tuple ::buffer
                 ::tag)
  :ret  ::buffer)


(s/fdef toggle-lines

  :args (s/cat ::buffer ::buffer
               ::tags   (s/? ::tags))
  :ret  ::buffer)




(defn- -buffer

  ;; Produces a buffer associated with a bunch of tags.

  [tag->GpioLine]

  (let [gpio-buffer (GpioBuffer.)]
    (reify

      IBuffer

        (clear-lines [this]
          (.clear gpio-buffer)
          this)


        (get-line [_ tag]
          (.get gpio-buffer
                (-get-gpio-line tag->GpioLine
                                tag)))


        (get-lines [this]
          (get-lines this
                     (keys tag->GpioLine)))


        (get-lines [this tags]
          (reduce (fn line-state [tag->state tag]
                    (assoc tag->state
                           tag
                           (get-line this
                                     tag)))
                  {}
                  tags))


        (set-line [this tag state]
          (.set gpio-buffer
                (-get-gpio-line tag->GpioLine
                                tag)
                state)
          this)


        (set-lines [this tag->state]
          (doseq [[tag state] tag->state]
            (set-line this
                      tag
                      state))
          this)


        (toggle-line [this tag]
          (set-line this
                    tag
                    (not (get-line this
                                   tag)))
          this)


        (toggle-lines [this]
          (toggle-lines this
                        (keys tag->GpioLine)))


        (toggle-lines [this tags]
          (doseq [tag tags]
            (toggle-line this
                         tag))
          this)


     IPure

       (-raw-type [_]
         gpio-buffer)
     )))




;;;;;;;;;;


(defprotocol IHandle

  "IO using a GPIO handle and a buffer.

   Reading or writing the state of lines happens virtually at once for all of them.
   Whether it happens exactly at the same time depends on the underlying driver and
   this fact is opaque. In any case, driving several lines using a single handle is
   more efficient than using a handle for each line."

  (read [handle buffer]

    "Using a handle, reads the current state of lines into the given buffer.")


  (write [handle buffer]

    "Using a handle, writes the current state of lines using the given buffer."))




(s/fdef read

  :args (s/cat ::handle ::handle
               ::buffer ::buffer))


(s/fdef write

  :args (s/cat ::handle ::handle
               ::buffer ::buffer))



(s/def ::handle-options

  (s/nilable (s/merge ::flags
                      (s/keys :opt [::consumer]))))


(s/def ::line-number->line-options

  (s/map-of ::line-number
            ::line-options))


(s/def ::line-options

  (s/keys :opt [::state
                ::tag]))


(s/fdef handle

  :args  (s/cat ::device                    ::device
                ::line-number->line-options ::line-number->line-options
                ::handle-options            (s/? ::handle-options))
  :ret  ::handle)


(defn handle

  "Given a GPIO device, requests a handle for one or several lines which can
   then be used to read and/or write the state of lines.
  
   Implements `IHandle`.
  

   Ex. (handle some-device
               {17 {::tag :red-led}
                27 {::tag   :green-led
                    ::state true}}
               {::direction :output})"

  ([device lines-number->line-options]

   (handle device
           lines-number->line-options
           nil))


  ([^GpioDevice device line-number->line-options handle-options]

   (let [req           (GpioHandleRequest.)
         tag->GpioLine (reduce-kv (fn add-tag [tag->GpioLine line-number line-options]
                                    (assoc tag->GpioLine
                                           (get line-options
                                                ::tag
                                                line-number)
                                           (if (::state line-options)
                                             (.addLine req
                                                       line-number
                                                       true)
                                             (.addLine req
                                                       line-number))))
                                  {}
                                  line-number->line-options)]
     (some->> (::consumer handle-options)
              (.setConsumer req))
     (.setFlags req
                (-flags handle-options))
     (let [gpio-handle (.requestHandle device
                                       req)]
       (reify

         AutoCloseable

           (close [_]
             (.close gpio-handle))


        IBuffereable

          (buffer [_]
            (-buffer tag->GpioLine))


        IHandle

          (read [_ buffer]
            (.read gpio-handle
                   (-raw-type buffer)))


          (write [_ buffer]
            (.write gpio-handle
                    (-raw-type buffer)))
          )))))




;;;;;;;;;;


(defn- -event-handle

  ;; Requests a GpioEventHandle.

  ^GpioEventHandle

  [^GpioDevice device line-number event-handle-options]

  (.requestEvent device
                 (let [req (GpioEventRequest. line-number
                                              (condp identical?
                                                     (void/obtain ::edge-detection
                                                                  event-handle-options
                                                                  defaults)
                                                :rising             GpioEdgeDetection/RISING
                                                :falling            GpioEdgeDetection/FALLING
                                                :rising-and-falling GpioEdgeDetection/RISING_AND_FALLING)
                                              (-flags event-handle-options))]

                   (some->> (::consumer event-handle-options)
                            (.setConsumer req))
                   req)))




(defprotocol IWatcher

  "IO using a watcher."

  (poll [watcher buffer tag]

    "Using a watcher, reads the current state of a line.")


  (event [watcher]
         [watcher timeout-ms]

    "Using a watcher, efficiently blocks until the state of one of the monitored inputs switches to a relevant
     one or the timeout elapses.
    
     A timeout of -1 will block forever until something happens."))




(s/fdef poll

  :args (s/tuple ::watcher
                 ::buffer
                 ::tag)
  :ret  ::state)


(s/fdef wait

  :args (s/tuple ::watcher
                 ::timeout-ms)
  :ret  (s/or :nothing nil?
              :event   ::event))




(defn- -close-watcher-resources

  ;; Closes all resources related to a watcher.

  [^GpioEventWatcher gpio-watcher tag->event-handle]

  (close gpio-watcher)
  (doseq [[_ event-handle] tag->event-handle]
    (close event-handle)))




(s/def ::line-number->watch-options

  (s/map-of ::line-number
            (s/merge ::handle-options
                     ::line-options)))
            

(s/fdef watcher

  :args (s/tuple ::device
                 ::line-number->watch-options)
  :ret  ::watcher)


(defn watcher

  "Given a GPIO device, produces a watcher which can then be used to efficiently monitor inputs for changes or poll
   their current values.
  
   Implements `IWatcher`.


   Ex. (watcher some-device
                {18 {::tag       :left-button
                     ::direction :input}
                 23 {::tag       :right-button
                     ::direction :output}})"

  [^GpioDevice device line-number->watch-options]

  (let [gpio-watcher      (GpioEventWatcher.)
        tag->event-handle (reduce-kv (fn event-handle [tag->event-handle line-number watch-options]
                                       (try
                                         (assoc tag->event-handle
                                                (if (contains? watch-options
                                                               ::tag)
                                                  (::tag watch-options)
                                                  line-number)
                                                (-event-handle device
                                                               line-number
                                                               watch-options))
                                         (catch Throwable e
                                           (-close-watcher-resources gpio-watcher
                                                                     tag->event-handle)
                                           (throw e))))
                                     {}
                                     line-number->watch-options)
        line-number->tag (reduce-kv (fn add-event-handle [line-number->tag tag ^GpioEventHandle event-handle]
                                      (try
                                        (let [line-number (.-lineNumber (.getLine event-handle))]
                                          (.addHandle gpio-watcher
                                                      event-handle
                                                      line-number)
                                          (assoc line-number->tag
                                                 line-number
                                                 tag))
                                        (catch Throwable e
                                          (-close-watcher-resources gpio-watcher
                                                                    tag->event-handle)
                                          (throw e))))
                                    {}
                                    tag->event-handle)
        gpio-event       (GpioEvent.)]
    (reify

      AutoCloseable

        (close [_]
          (-close-watcher-resources gpio-watcher
                                    tag->event-handle))


      IBuffereable

        (buffer [_]
          (-buffer (reduce-kv (fn gpio-line [tag->GpioLine tag ^GpioEventHandle event-handle]
                                (assoc tag->GpioLine
                                       tag
                                       (.getLine event-handle)))
                              {}
                              tag->event-handle)))


      IWatcher

        (poll [_ buffer tag]
          (if (contains? tag->event-handle
                         tag)
            (do
              (.read ^GpioEventHandle (get tag->event-handle
                                           tag)
                     (-raw-type buffer))
              (get-line buffer
                        tag))
            (throw (IllegalArgumentException. (str "GPIO watcher is not responsible for tag -> "
                                                   tag)))))


        (event [this]
          (event this
                 -1))


        (event [_ timeout-ms]
          (when (.waitForEvent gpio-watcher
                               gpio-event
                               timeout-ms)
            {::edge           (if (.isRising gpio-event)
                                :rising
                                :falling)
             ::nano-timestamp (.getNanoTimestamp gpio-event)
             ::tag            (get line-number->tag
                                   (.getId gpio-event))}))
      )
  ))
