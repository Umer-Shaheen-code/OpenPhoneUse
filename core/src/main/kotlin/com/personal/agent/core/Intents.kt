package com.personal.agent.core

/**
 * The command grammar. Intent ids are the same ids as the capability registry on the Android side
 * (and the LLM tool names, with '.' → '_').
 * Earlier intents win ties.
 */
object CommandGrammar {

    class Builder {
        val out = ArrayList<Pattern>()
        private var current = ""
        fun intent(id: String, block: Builder.() -> Unit) { current = id; block() }
        fun p(src: String, w: Double = 1.0, fixed: Map<String, SlotValue> = emptyMap()) { out += Pattern(current, src, w, fixed) }
    }

    private fun app(id: String) = mapOf("app" to SlotValue.App(id))
    private fun enum(name: String, v: String) = mapOf(name to SlotValue.Enum(v))
    private val FWD = mapOf("direction" to SlotValue.Enum("forward"))
    private val BACK = mapOf("direction" to SlotValue.Enum("back"))

    val patterns: List<Pattern> = Builder().apply {
        // ---------------- meta / dialog ----------------
        intent("meta.yes") {
            p("(yes|yeah|yep|yup|sure|correct|confirm|affirmative|right|ok|okay)"); p("(do it|send it|go ahead|yes do it|yes send it|sounds good|that is right)")
        }
        intent("meta.no") { p("(no|nope|nah|negative)"); p("(do not|dont) [send it|do it]"); p("no thanks") }
        intent("meta.cancel") { p("(cancel|nevermind|stop|quiet|silence|enough) [it|that|this]"); p("(never mind|forget it|cancel that|stop talking|be quiet|shut up)") }
        intent("meta.choose") { p("{index}"); p("the {index}") }
        intent("meta.repeat") {
            p("(repeat|pardon)"); p("(repeat|say) (that|it) [again]"); p("say again"); p("come again"); p("what did you say"); p("read (it|that) again")
        }
        intent("meta.help") {
            p("help"); p("what can you do"); p("what (are|is) your (commands|features|skills)"); p("how can you help [me]"); p("what can i (say|ask) [you]")
        }
        intent("list.more") { p("(continue|more|keep going) [reading]"); p("read [the] (rest|more)"); p("read more"); p("(go on|carry on)") }

        // ---------------- time & date ----------------
        intent("time.now") {
            p("what time is it [now|right now]"); p("what is the [current] time [now|right now]"); p("[tell me] the time")
            p("time [now]"); p("do you know what time it is"); p("[tell me] what time it is"); p("what is time [now]")
            p("(kya|kitna) (time|waqt) (hua|huwa|ho raha) [hai]"); p("(time|waqt) kya (hua|hai) [abhi]"); p("abhi kya time hai")
        }
        intent("time.in_city") {
            p("what time is it in {city}"); p("what is the time in {city}"); p("[tell me] [the] time in {city}"); p("what is the [current] time in {city}")
            p("{city} (mein|main|me) kya time [hua] hai")
        }
        intent("date.today") {
            p("what is the date [today]"); p("what is today [date]"); p("what date is it [today]"); p("[tell me] [today|the] date [today]")
            p("what is today date"); p("what is the date of today"); p("which date is it [today]")
            p("aaj [ki] (tareekh|tarikh|date) kya hai")
        }
        intent("day.today") {
            p("(what|which) day is it [today]"); p("what day is today"); p("what is the day [today]"); p("is today {day=monday|tuesday|wednesday|thursday|friday|saturday|sunday}")
            p("aaj (kaunsa|konsa|kya) din hai")
        }
        intent("date.days_until") {
            p("how many days [are there|are left|left] (until|till|to|before) {date}"); p("how long (until|till|before) {date}"); p("days (until|till|to) {date}")
        }

        // ---------------- device status ----------------
        intent("battery.status") {
            p("[what is] [my|the] battery [level|percentage|status|percent]"); p("how much battery [do i have|is left|left|have i got]")
            p("(am i|is [my|the] phone|is it) charging"); p("how long (until|till|before) [my phone is|the phone is|it is|i am] (full|charged|fully charged)")
            p("[what is] [my|the] phone battery [level]"); p("check [my|the] battery")
            p("battery (kitni|kya|kitna) hai"); p("phone charge ho raha hai")
        }

        // ---------------- gmail full API (Phase 3) ----------------
        intent("email.read_full") {
            p("(read|show|check|open) [me] [the|my] (full|complete|entire) (email|emails|mail|mails) [from {sender:words}]")
            p("(read|show) [the] (full|whole) (email|mail) from {sender:words}")
            p("poori email (parho|sunao|dikhao) [{sender:words} ki]")
        }
        intent("email.reply") {
            p("reply to [that|the|last] email [(saying|with|that)] {message}")
            p("(email|mail) {contact} [(saying|that)] {message}")
            p("send [an] email to {contact} [(saying|that)] {message}")
            p("{contact} ko email (karo|bhejo) {message}")
        }

        // ---------------- notifications ----------------
        intent("notif.read") {
            p("(read|tell me|show|check|give me|read out|what are) [me] [all] [my|the] [new|unread|latest|recent] [{app}] <msgs>")
            p("(read|check) [my] {app}")
            p("(any|do i have any|are there any|i have any|got any|have i got any) [new|unread] [{app}] <msgs>")
            p("(any|do i have any|are there any|got any) [new|unread] <msgs> (on|in|from) {app}")
            p("(read|check|tell me|show|give me|read out|what are) [me] [all] [my|the] [new|unread|latest|recent] <msgs> (on|in|from|of) {app}")
            p("what is new [on|in] [{app}]"); p("what did i miss [on|in] [{app}]"); p("(anything|something) new [on|in] [{app}]")
            p("who (messaged|texted|wrote|emailed|mailed) [me]")
            p("(any|do i have any|are there any|got any) [new|unread] <msgs> from {contact}")
            p("(read|show) [me] [the] [new|unread|latest] <msgs> from {contact}")
            p("read {contact} <msgs>")
            p("what did {contact} (say|send|write|text|message)")
            p("did {contact} (message|text|email|write|reply|say anything) [me]")
            p("(any|do i have any|got any) (emails|email|mails|mail) from {contact}", fixed = app("gmail"))
            p("read [my] {app} from {contact}")
            // Roman-Urdu
            p("[mere|meri] [naye|new] [{app}] (messages|notifications|emails|msgs) (parho|sunao|batao|dikhao|check karo)")
            p("{app} (check karo|parho|sunao)")
            p("(kisi ka|koi) [naya] (message|email|paigham) (aya|aaya) hai")
            p("{contact} (ne|ny) kya (kaha|likha|bheja) [hai]")
            p("{contact} (ka|ke) (message|messages|email) (parho|sunao|aya hai)")
        }
        intent("notif.mark_read") {
            p("mark [it|that|them|this|everything|all] [as] read"); p("mark [all] [the] [messages|chat|conversation] from {contact} [as] read")
            p("mark {contact} [messages|chat] [as] read"); p("mark [all] [my] {app} [messages|notifications|chats] [as] read")
        }
        intent("notif.clear") {
            p("(clear|dismiss|remove) [all] [my|the] [{app}] (notifications|notification)"); p("clear all"); p("(clear|dismiss) (it|that|this)")
        }
        intent("notif.archive") { p("archive [it|that|this|the email|that email|this email|the mail]"); p("archive [the] (email|mail) from {contact}") }

        // ---------------- messaging ----------------
        intent("msg.send") {
            p("reply {message} to {contact} [(on|via|in|over) {app}]")
            p("reply to {contact} [(on|via|in|over) {app}] [saying|with|that|and say|and tell (him|her|them)] {message}")
            p("reply to {contact} [(on|via|in|over) {app}]")
            p("reply [back] [(saying|with|that)] {message}", w = 0.92)
            p("reply")
            p("(tell|text|message|msg|whatsapp|ping) {contact} [(on|via|in|over) {app}] [that] {message}")
            p("(tell|ask) {contact} to {message}", w = 0.95)
            p("send {message} to {contact} [(on|via|in|over) {app}]", w = 0.97)
            p("send [a|an] [{app}] [message|text|msg] to {contact} [(on|via|in|over) {app}] [saying|that] {message}")
            p("send [a|an] [message|text|msg] to {contact} [(on|via|in|over) {app}]")
            p("(say|write) {message} to {contact}", w = 0.95)
            p("message {contact} [(on|via|in|over) {app}]")
            p("(send|reply|text) (him|her|them) {message}", w = 0.9)
            // Roman-Urdu
            p("{contact} ko whatsapp karo [ke|kay] {message}", fixed = app("whatsapp"))
            p("{contact} ko sms karo [ke|kay] {message}", fixed = app("sms"))
            p("{contact} ko [{app} par|pe] (bolo|kaho|likho|bhejo|message karo) [ke|kay] {message}")
            p("(usay|use|unko) (reply|jawab) (karo|do|bhejo) [ke] {message}", w = 0.9)
        }

        // ---------------- calls ----------------
        intent("call.contact") {
            p("(call|phone|ring|dial) {contact} [(on|via|over|using) {app}]")
            p("[make a|start a] (call|voice call) (to|with) {contact} [(on|via|over|using) {app}]")
            p("{app} call {contact}"); p("video call {contact} [(on|via|over|using) {app}]", fixed = mapOf("video" to SlotValue.Enum("yes")))
            p("call (him|her|them) back", w = 0.95); p("call back", w = 0.9)
            p("{contact} ko [{app} par|pe] (call|phone) (karo|lagao|milao)")
        }
        intent("call.missed") {
            p("who called [me]"); p("(any|do i have any|did i get any|did i miss any) [missed] calls"); p("(read|check|show) [my] missed calls"); p("who was calling [me]"); p("missed calls")
            p("(kis|kisne) call ki [thi]"); p("missed calls (batao|dikhao|check karo)")
        }
        intent("call.answer") { p("(answer|accept|pick up) [the] [call|phone]"); p("call (uthao|pick karo|receive karo)") }
        intent("call.end") { p("(decline|reject|end|hang up|cut) [the] [call]"); p("call (kaato|kato|band karo|cut karo)") }

        // ---------------- media ----------------
        intent("media.seek_relative") {
            p("(forward|skip ahead|jump ahead|go ahead|move ahead|skip forward|go forward|jump forward|move forward|seek forward|advance) [the|this] [<noun>] [by|for] {duration}", fixed = FWD)
            p("(go|skip|jump|move|seek) {duration} (ahead|forward|forwards)", fixed = FWD)
            p("{duration} (ahead|forward|forwards)", fixed = FWD)
            p("skip [ahead] {duration}", fixed = FWD)
            p("forward [the] <noun> {duration}", fixed = FWD)
            p("(rewind|go back|skip back|jump back|move back|seek back|go backward|go backwards|back|reverse) [the|this] [<noun>] [by|for] {duration}", fixed = BACK)
            p("(go|skip|jump|move|seek) {duration} (back|backward|backwards|behind)", fixed = BACK)
            p("{duration} (back|backward|backwards)", fixed = BACK)
            p("rewind [the] <noun> {duration}", fixed = BACK)
            p("(take|bring|put) [it|the <noun>] back {duration}", fixed = BACK)
            // Roman-Urdu
            p("[<noun>] {duration} (aage|agay|age) (karo|barhao|bhagao)", fixed = FWD)
            p("[<noun>] {duration} (peeche|piche|peechay) (karo|le jao)", fixed = BACK)
        }
        intent("media.seek_absolute") {
            p("(go|jump|skip|seek|move) [back] to {position}"); p("(start|play|restart) [it|the <noun>|<noun>] from {position}")
            p("set [the] [<noun>] to {position}"); p("(restart|replay) [the|this] [<noun>]", fixed = mapOf("position" to SlotValue.Position(0)))
            p("start [it|the <noun>] over", fixed = mapOf("position" to SlotValue.Position(0)))
            p("(play|start) [it] from the (beginning|start)", fixed = mapOf("position" to SlotValue.Position(0)))
        }
        intent("media.pause") {
            p("pause [the] [<noun>]"); p("pause {app}"); p("(stop|hold) [the] (music|song|video|playback|audio|podcast)"); p("stop {app}")
            p("(stop|pause) playing"); p("pause it")
            p("(<noun>|gaana|gana) (roko|rok do|band karo|pause karo)")
        }
        intent("media.play") {
            p("(resume|continue|unpause) [the] [<noun>]"); p("(play|start) [the] (music|song|video|playback|audio|it)"); p("play"); p("(play|resume|open and play) {app}")
            p("(resume|continue) playing"); p("start playing")
            p("(<noun>|gaana|gana) (chalao|chala do|resume karo)")
        }
        intent("media.next") {
            p("(next|skip) [song|track|video|one|episode]"); p("play [the] next [song|track|video|one|episode]"); p("skip [this|the] [song|track|video|one]"); p("(change|switch) [the] (song|track)")
            p("(agla|next) (gaana|gana|video|song) [lagao|chalao]")
        }
        intent("media.previous") {
            p("(previous|last|prior) [song|track|video|one|episode]"); p("play [the] (previous|last) [song|track|video|one]")
            p("go back [to the] (previous|last) (song|track|video|one)"); p("go back a (song|track)")
            p("(pichla|previous) (gaana|gana|video|song) [lagao|chalao]")
        }
        intent("media.now_playing") {
            p("[tell me] what is (playing|this song|this video|this track|this)"); p("what song is (this|playing|that)"); p("(which|what) (song|track|video) is (this|playing|that)")
            p("who (sings|sang|is singing) [this] [song]"); p("what is the name of (this|the) (song|video|track)"); p("what am i (listening to|watching)")
            p("what is currently playing"); p("now playing"); p("name (this|that) song"); p("what is the song")
            p("kya (chal raha|baj raha) hai"); p("kaunsa gaana (chal raha|hai)")
        }
        intent("media.speed") {
            p("(play|set it|set) [at|to] {speed}"); p("[set] [the] [playback] speed [to|at] {speed}"); p("{speed} speed"); p("speed [it] up [to {speed}]")
            p("(play|set) [at|to] [the] {speed} speed"); p("slow [it] down", fixed = mapOf("speed" to SlotValue.Speed(0.75f))); p("normal speed", fixed = mapOf("speed" to SlotValue.Speed(1f)))
        }
        intent("media.like") {
            p("(like|love|heart|thumbs up|save) [this|the] [song|video|track]")
            p("(add|save) [this|the] [song|track|video] to [my] (liked songs|favorites|favourites|likes|liked videos)")
        }
        intent("media.play_search") {
            p("play {query} (on|in|using|from) {app}", w = 0.97); p("(search|find) [for] {query} (on|in) {app}", w = 0.95)
            p("(search|look up) {app} for {query}", w = 0.95)
            p("play {query}", w = 0.86); p("put on {query}", w = 0.85); p("i want to (hear|listen to|watch) {query}", w = 0.85)
        }

        // ---------------- sound / device ----------------
        intent("volume.set") {
            p("(set|turn|change|put) [the] [media|music] volume [to|at] {level}"); p("[media] volume [to|at] {level}"); p("set [the] volume {level}")
            p("(volume|awaz|awaaz) {level} [par|pe] (karo|rakho)")
        }
        intent("volume.up") {
            p("(volume up|louder|turn it up|turn up the volume|turn the volume up|make it louder|increase [the] volume|raise [the] volume|crank it up)")
            p("increase [the] volume by {level}"); p("(louder|volume up) please")
            p("(volume|awaz|awaaz) (barhao|zyada karo|tez karo|oonchi karo)")
        }
        intent("volume.down") {
            p("(volume down|softer|quieter|turn it down|turn down the volume|turn the volume down|make it quieter|make it softer|decrease [the] volume|lower [the] volume|reduce [the] volume)")
            p("decrease [the] volume by {level}")
            p("(volume|awaz|awaaz) (kam karo|ghatao|halki karo)")
        }
        intent("volume.mute") { p("mute [the] [media|music|volume|sound|audio|it]") }
        intent("volume.unmute") { p("unmute [the] [media|music|volume|sound|audio|it]") }
        intent("ringer.mode") {
            p("(put|set|turn|switch|change) [the|my] [phone|ringer] (on|to|into) {mode=silent|vibrate|vibration|normal|ring|ringing|sound|loud} [mode]")
            p("{mode=silent|vibrate|vibration|normal|ring} mode"); p("(silence|mute) [my|the] (phone|ringer)", fixed = enum("mode", "silent"))
            p("turn (on|off) {mode=silent|vibrate|vibration} [mode]"); p("unmute [my|the] (phone|ringer)", fixed = enum("mode", "normal"))
        }
        intent("dnd.set") {
            p("(turn|switch|put) {state=on|off} [the] dnd [mode]"); p("(turn|switch) [the] dnd [mode] {state=on|off}"); p("dnd [mode] {state=on|off}")
            p("(enable|start|activate) dnd [mode]", fixed = enum("state", "on")); p("(disable|stop|deactivate|end) dnd [mode]", fixed = enum("state", "off"))
        }
        intent("torch.set") {
            p("(turn|switch|put) {state=on|off} [the] (flashlight|torch|light)"); p("(turn|switch) [the] (flashlight|torch|light) {state=on|off}")
            p("(flashlight|torch) {state=on|off}"); p("(flashlight|torch)", fixed = enum("state", "toggle"))
            p("(enable|open) [the] (flashlight|torch)", fixed = enum("state", "on")); p("(disable|close) [the] (flashlight|torch)", fixed = enum("state", "off"))
            p("(flashlight|torch|light) {state=on|off} karo"); p("(flashlight|torch|light) (jalao|kholo)", fixed = enum("state", "on")); p("(flashlight|torch|light) band karo", fixed = enum("state", "off"))
        }
        intent("brightness.set") {
            p("(set|change|put|turn) [the] [screen] brightness [to|at] {level}"); p("[screen] brightness [to|at] {level}")
            p("brightness {level} [par|pe] (karo|rakho|set karo)")
        }
        intent("brightness.up") { p("(brightness up|brighter|increase [the] [screen] brightness|raise [the] brightness|make [the] screen brighter)"); p("brightness (barhao|zyada karo|tez karo)") }
        intent("brightness.down") { p("(brightness down|dimmer|decrease [the] [screen] brightness|lower [the] brightness|dim [the] screen)"); p("brightness (kam karo|ghatao)") }

        // ---------------- alarms, timers, reminders ----------------
        intent("alarm.set") {
            p("(set|create|make|put) [an|a] [new] alarm [for|at] [tomorrow] [at|for] {time} [tomorrow]"); p("wake me [up] [tomorrow] (at|by) {time} [tomorrow]")
            p("alarm [for|at] {time}"); p("(set|create) [an|a] alarm (in|after) {duration}")
            p("{time} [baje|ka] alarm lagao"); p("alarm lagao {time} [ka]")
        }
        intent("timer.set") {
            p("(set|start|create|make|put) [a|an] [new] timer (for|of) {duration}"); p("timer [for] {duration}"); p("{duration} timer")
            p("(set|start) [a|an] {duration} timer"); p("count down [from] {duration}"); p("(set|start) [a|an] timer {duration}")
            p("{duration} ka timer lagao"); p("timer lagao {duration} ka")
        }
        intent("alarm.dismiss") { p("(dismiss|stop|turn off|cancel|silence) [the] alarm") }
        intent("alarm.snooze") { p("snooze [the alarm] [for {duration}]"); p("snooze it") }
        intent("reminder.set") {
            p("remind me (in|after) {duration} [to|that|about] {text}"); p("remind me [to|that|about] {text} (in|after) {duration}")
            p("remind me (at|by) {time} [to|that|about] {text}"); p("remind me [to|that|about] {text} (at|by) {time}")
            p("remind me [tomorrow] at {time} [tomorrow] [to|that|about] {text}", w = 0.97)
            p("(set|create) [a|an] reminder (in|for|after) {duration} [to|that|about|for] {text}"); p("(set|create) [a|an] reminder [to|that|about|for] {text} (in|after) {duration}")
            p("remind me [to|that|about] {text}", w = 0.9)
            p("{duration} baad (yaad dilana|yaad dila dena|remind karna) [ke] {text}")
            p("mujhe {duration} baad (yaad dilana|remind karna) [ke] {text}")
        }

        // ---------------- notes & lists ----------------
        intent("list.add") {
            p("add {item} to [my|the] {list} [list]"); p("put {item} on [my|the] {list} list"); p("{list} list add {item}", w = 0.9)
        }
        intent("list.read") { p("(read|what is on|what is in|show|check|tell me) [me] [my|the] {list} list"); p("what do i need [to buy|from the store]", fixed = mapOf("list" to SlotValue.Words("shopping"))) }
        intent("note.add") {
            p("(note likho|likh lo|yaad rakho) [ke] {note}")
            p("(note|note down|write down|remember|take a note|make a note|save a note|add a note) [that] {note}"); p("(take|make|save|add) [a] note [saying|that] {note}")
            p("remember (where|that) {note}", w = 0.95)
        }
        intent("note.read") { p("(read|what are|show|tell me|list) [me] [my] [last|latest|recent] notes"); p("what did i (note|save|ask you to remember)"); p("read [my] last note"); p("mere notes (parho|sunao|dikhao)") }

        // ---------------- info ----------------
        intent("calc") {
            p("(what is|calculate|compute|how much is|what does|whats) {expr}"); p("convert {expr}"); p("{expr}", w = 0.9)
        }
        intent("weather") {
            p("(what is|how is|whats) the weather [like] [{when=today|now|tomorrow|tonight}] [{when=today|now|tomorrow|tonight}]")
            p("weather [{when=today|tomorrow|now|tonight}]"); p("(is it|will it) [going to] (rain|raining) [{when=today|tomorrow|tonight}]")
            p("is it going to rain [{when=today|tomorrow|tonight}]"); p("will it rain [{when=today|tomorrow|tonight}]"); p("how (hot|cold|warm) is it [outside|today|now]")
            p("what is the temperature [outside|today|now]"); p("[what is the] forecast [for] [{when=today|tomorrow}]"); p("do i need an umbrella [{when=today|tomorrow}]")
            p("[aaj|kal] mausam (kaisa|kesa) hai"); p("(kya|aaj|kal) barish (hogi|ho rahi hai)")
        }
        intent("calendar.agenda") {
            p("what is (on|in) [my] (calendar|schedule|agenda) [for] [{day=today|tomorrow}]"); p("(do i have|any|are there|have i got) [any] (meetings|events|appointments|meeting|event) [{day=today|tomorrow}]")
            p("what is my (schedule|agenda) [for] [{day=today|tomorrow}]"); p("what is next on my (calendar|schedule)"); p("my (calendar|schedule|agenda) [for] [{day=today|tomorrow}]")
            p("what do i have [{day=today|tomorrow}]", w = 0.9)
        }

        // ---------------- apps & navigation ----------------
        intent("nav.go") {
            p("(navigate|drive|directions|take me) (to|home) [{place}]", w = 0.95); p("(navigate|take me|drive me|get directions) (to) {place}"); p("directions to {place}")
            p("(navigate|take me|drive me) home", fixed = mapOf("place" to SlotValue.Text("home")))
        }
        intent("app.open") { p("(open|launch|start|run) {appname}", w = 0.9); p("{appname} (kholo|open karo)", w = 0.9) }
        intent("device.lock") { p("lock [the|my] (phone|screen|device)"); p("lock it") }
        intent("device.screenshot") { p("(take|capture|grab) [a] screenshot"); p("screenshot") }
        intent("device.home") { p("go [to the] home [screen]"); p("home screen") }
        intent("device.back") { p("go back"); p("back") }

        // ---------------- generative (LLM tier) ----------------
        intent("llm.summarize") {
            p("(summarize|summarise|summary of|give me a summary of|sum up) [all] [my|the] [new|unread|recent] [{app}] [<msgs>]")
            p("(summarize|summarise) [the] (chat|conversation|messages) (with|from) {contact}")
        }
    }.out
}
