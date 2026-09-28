package com.yazsras.astravar.overlay

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.yazsras.astravar.*
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.*
import com.yazsras.astravar.ui.elementTint
import com.yazsras.astravar.ui.rollFeedback
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import kotlin.math.abs

class OverlayService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val app get()=application as AstravarApp
    private lateinit var wm: WindowManager
    private lateinit var uiContext: Context
    private var view: LinearLayout?=null
    private var params: WindowManager.LayoutParams?=null
    private var state: Campaign?=null
    private var prefs=OverlayPrefs()
    private var preferencesLoaded=false
    private var expanded=false
    private var hidden=false
    private var elements=listOf<DamageType>()
    private var selected=setOf<String>()
    private var preview: ShotPlan?=null
    private var busy=false
    private var status=""
    private var distance=30
    private var targetPoint=""
    private var directImpact=false
    private var immediateId: String?=null
    private var manualSlot=false
    private var panelPage="combat"
    private var showDice=false
    private var editingTarget=false
    private var resultId: String?=null
    private var outcome: Outcome?=null
    private var attackText=""
    private val rollTexts=mutableMapOf<String,String>()
    private var targetResults=listOf<TargetSave>()
    private var portrait=true
    private var imeBottom=0
    private var registered=false
    private val watcher=Handler(Looper.getMainLooper())
    private val permissionCheck=object: Runnable {
        override fun run() {
            if(!Settings.canDrawOverlays(this@OverlayService)) { stopSelf(); return }
            if(getSystemService(KeyguardManager::class.java).isKeyguardLocked) { hidden=true; removeWindow() }
            watcher.postDelayed(this,1200)
        }
    }
    private val lockReceiver=object: BroadcastReceiver() {
        override fun onReceive(c: Context?,i: Intent?) { if(i?.action==Intent.ACTION_SCREEN_OFF) { hidden=true; removeWindow(); updateNotification() } }
    }
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onCreate() {
        super.onCreate()
        uiContext=if(Build.VERSION.SDK_INT>=30) {
            val display=getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null)
        } else this
        wm=uiContext.getSystemService(WindowManager::class.java)
        portrait=resources.configuration.orientation!=Configuration.ORIENTATION_LANDSCAPE
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("session","Astravar play session",NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this,lockReceiver,IntentFilter(Intent.ACTION_SCREEN_OFF),ContextCompat.RECEIVER_NOT_EXPORTED); registered=true
        scope.launch { app.preferences.flow.collect { prefs=it;if(!editingTarget) {distance=it.distance;targetPoint=it.targetPoint;directImpact=it.directImpact;immediateId=it.immediateId;manualSlot=it.manualSlot};elements=it.elements;selected=it.selectedPayloads.filter {id->state?.installed?.payloads?.any {p->p.id==id}==true}.toSet();if(!preferencesLoaded) {params=null;preferencesLoaded=true};if(!hidden) render() } }
        scope.launch { app.repository.state.filterNotNull().collect { state=it; selected=prefs.selectedPayloads.filter { id -> it.installed?.payloads?.any { p->p.id==id }==true }.toSet(); if(!hidden) render() } }
        watcher.post(permissionCheck)
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="STOP") { stopSelf(); return START_NOT_STICKY }
        if(!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if(getSystemService(KeyguardManager::class.java).isKeyguardLocked) { stopSelf(); return START_NOT_STICKY }
        hidden=false
        try {
            ServiceCompat.startForeground(this,7,notification(),if(Build.VERSION.SDK_INT>=34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            render()
        } catch(e: RuntimeException) { stopSelf() }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        fun pi(action: String,request: Int)=PendingIntent.getService(this,request,Intent(this,OverlayService::class.java).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,"session").setSmallIcon(R.drawable.ic_crystal).setContentTitle("Astravar session ${if(hidden) "hidden" else "active"}")
            .setContentText("Astravar combat controls · ${if(hidden) "tap Show to resume" else "crystal controls float above ordinary apps"}")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0,"Show",pi("SHOW",1)).addAction(0,"Stop",pi("STOP",2)).build()
    }
    private fun updateNotification() { getSystemService(NotificationManager::class.java).notify(7,notification()) }
    private fun dp(v: Int)=(v*resources.displayMetrics.density*prefs.scale).toInt()
    private fun background(color: Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(18).toFloat(); setStroke(dp(1),Color.rgb(160,130,84)) }
    private fun text(value: String,size: Float=14f)=TextView(uiContext).apply { text=value; textSize=size; setTextColor(Color.rgb(231,234,241)); setPadding(dp(10),dp(5),dp(10),dp(5)) }
    private fun button(label: String,danger: Boolean=false,action: ()->Unit)=Button(uiContext).apply {
        text=label; contentDescription=label; isAllCaps=false; minHeight=maxOf(dp(48),(48*resources.displayMetrics.density).toInt()); setTextColor(Color.WHITE)
        background=background(if(danger) Color.rgb(97,34,38) else Color.rgb(35,46,57)); setOnClickListener { if(!busy) action() }
    }
    private fun run(label: String,block: suspend ()->Unit) {
        if(busy) return; busy=true
        scope.launch { try { block(); status=label } catch(e: Exception) { status=e.message ?: "Operation failed" } finally { busy=false; preview=null; render() } }
    }
    private fun mainApp() { hidden=true; removeWindow(); updateNotification(); startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
    private fun saveElements(values: List<DamageType>) {elements=values;scope.launch {app.preferences.elements(values)};render()}
    private fun render() {
        if(hidden || !preferencesLoaded || !Settings.canDrawOverlays(this)) return
        val s=state ?: return
        val root=LinearLayout(uiContext).apply { orientation=LinearLayout.VERTICAL; background=background(Color.rgb(17,20,25)); elevation=dp(10).toFloat() }
        val handle=text(if(expanded) "◇  ASTRAVAR                 ⌃" else "◇\n${if(s.attuned && s.installed?.usable(s.gameSeconds)==true && !s.postShatter) "Ready" else "Check"}",if(expanded) 16f else 18f)
        handle.gravity=Gravity.CENTER; handle.contentDescription=if(expanded) "Drag Astravar panel" else "Expand Astravar crystal"
        handle.setOnClickListener {expanded=!expanded;if(!expanded) {resultId=null;editingTarget=false};render()}
        handle.minimumHeight=dp(if(expanded) 48 else 64); root.addView(handle); drag(handle)
        if(expanded) {
            val content=LinearLayout(uiContext).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(8),0,dp(8),dp(8))}
            val scroll=ScrollView(uiContext).apply {isFillViewport=false;addView(content)}
            root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
            renderPanel(content,s)
        }

        val previous=params
        removeWindow()
        val bounds=bounds()
        val width=if(expanded) minOf(dp(320),bounds[2]-bounds[0]) else dp(64)
        val height=if(expanded) minOf(dp(if(panelPage=="combat") 410 else 460),bounds[3]-bounds[1]) else dp(maxOf(72,(60*resources.configuration.fontScale).toInt()))
        val lp=WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            (if(expanded && (editingTarget || resultId?.let {id->s.pending.find {it.plan.id==id}?.let {it.result==null && (it.plan.blast || it.rolled==null)}}==true)) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT)
        lp.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        lp.gravity=Gravity.TOP or Gravity.LEFT
        lp.x=previous?.x ?: if(portrait) prefs.portraitX else prefs.landscapeX
        lp.y=previous?.y ?: if(portrait) prefs.portraitY else prefs.landscapeY
        clamp(lp); params=lp; view=root
        root.setOnApplyWindowInsetsListener { _,insets ->
            if(Build.VERSION.SDK_INT>=30) { val bottom=insets.getInsets(WindowInsets.Type.ime()).bottom; if(bottom!=imeBottom) { imeBottom=bottom; params?.let { p -> val b=bounds();p.height=if(expanded) minOf(dp(if(panelPage=="combat") 410 else 460),b[3]-b[1]) else dp(72);clamp(p); safeUpdate() } } }; insets
        }
        try { wm.addView(root,lp) } catch(_: RuntimeException) { view=null; stopSelf() }
    }
    private fun row(parent: LinearLayout,vararg children: View) {
        parent.addView(LinearLayout(uiContext).apply {orientation=LinearLayout.HORIZONTAL;children.forEach {child->addView(child,LinearLayout.LayoutParams(0,-2,1f).apply {setMargins(dp(2),dp(2),dp(2),dp(2))})}})
    }
    private fun options()=RollOptions(prefs.advantage,prefs.disadvantage,prefs.armorClass)
    private fun makePlan(s: Campaign): ShotPlan {
        val mode=prefs.mode;val magic=mode in listOf(Ability.SPELL,Ability.DISCHARGE,Ability.SHATTER)
        return Engine.plan(s,UUID.randomUUID().toString(),mode,if(magic) selected.toList() else emptyList(),if(magic) s.templates.find {it.id==immediateId} else null,elements,directImpact && mode in listOf(Ability.DISCHARGE,Ability.SHATTER),distance,targetPoint,manualSlot)
    }
    private fun requestRoll(s: Campaign) {
        try {
            val p=makePlan(s)
            if(p.blast) {preview=p;panelPage="confirm";render()} else fire(p)
        } catch(e: Exception) {status=e.message ?: "Check weapon configuration";panelPage="combat";render()}
    }
    private fun fire(p: ShotPlan) {
        run("Dice saved") {
            val after=app.repository.fire(p,options(),"overlay")
            resultId=p.id;outcome=null;targetResults=emptyList();panelPage="result";showDice=false
            if(prefs.haptics) view?.let {rollFeedback(it,after.pending.last().rolled)}
        }
    }
    private fun renderPanel(content: LinearLayout,s: Campaign) {
        val pending=s.pending.find {it.plan.id==resultId} ?: s.pending.lastOrNull()
        if(status.isNotBlank() && status!in setOf("Dice saved","Roll mode selected","Payload selected","Core installed","Mode selected")) content.addView(text(status))
        when(panelPage) {
            "result" -> if(pending!=null) {
                val p=pending.plan;val r=pending.rolled;val result=pending.result
                if(result==null && (p.blast || r==null)) {
                    if(r==null && !prefs.manualOverrides) content.addView(button("Roll saved shot") {run("Dice saved") {app.repository.rollPending(p.id,options(),"overlay")}})
                    else resolutionEditor(content,pending)
                } else {
                    content.addView(text(when {r?.natural==20->"CRITICAL HIT";r?.natural==1->"NATURAL 1 — MISS";result?.outcome==Outcome.HIT->"HIT";result?.outcome==Outcome.MISS->"MISS";else->p.ability.title.uppercase()},14f))
                    content.addView(text(r?.attackTotal?.let {"$it TO HIT"} ?: "DC ${p.profile.saveDc} DEX SAVE",28f))
                    r?.let {content.addView(text(it.attackDescription(p),13f))}
                    if(result!=null) {
                        if(p.blast) result.targets.forEach {content.addView(text("${it.name}: ${Engine.damage(p,result,it).damageText()}"))}
                        else {val damage=Engine.damage(p,result);content.addView(text("${damage.values.sum()} DAMAGE",23f));content.addView(text(damage.damageText(),16f))}
                    }
                    if(r?.options?.armorClass==null && result?.outcome==Outcome.UNCONFIRMED) content.addView(text("AC unknown · damage rolled",12f))
                    content.addView(button("Roll again") {requestRoll(s)})
                    row(content,button("Details") {showDice=!showDice;render()},button("Copy") {(getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Astravar result",shotSummary(pending)))})
                    if(showDice) content.addView(text(shotSummary(pending),12f))
                }
                row(content,button("Weapon") {panelPage="combat";resultId=null;render()},button("Collapse") {expanded=false;render()})
            } else {panelPage="combat";render()}
            "confirm" -> {
                preview?.let {p->
                    content.addView(text(p.ability.title,22f));content.addView(text(p.actionCost))
                    content.addView(text(Engine.components(p).joinToString("\n") {"${it.damage.dice} ${it.damage.type.name.lowercase()}"}))
                    if(p.hasSpell) content.addView(text("Uses ${p.selectedPayloads.size} stored spell(s)${p.immediate?.let {" + level-${it.castingLevel} slot"} ?: ""}. Spent even on a miss."))
                    if(p.ability==Ability.SHATTER) content.addView(text("Destroys this core and ALL remaining stored spells."))
                    if(p.ability==Ability.DISCHARGE) content.addView(text("Core exhausted for 600 game seconds."))
                    content.addView(button(if(p.ability==Ability.SHATTER) "Destroy core & roll" else "Confirm & roll",p.ability==Ability.SHATTER) {fire(p)})
                    content.addView(button("Cancel") {preview=null;panelPage="combat";render()})
                }
            }
            "cores" -> {
                content.addView(text("Choose a core",20f))
                s.cores.forEach {c->content.addView(button("${c.name} · ${if(c.id==s.installedId) "Active" else c.readiness(s.gameSeconds)}\n${c.payloads.size} stored · ${if(s.postShatter) s.profile.replacementCost else s.profile.swapCost}") {run("Core installed") {app.repository.mutate(label="Install ${c.name}",origin="overlay") {Engine.install(it,c.id)};panelPage="combat"}}.apply {isEnabled=c.id!=s.installedId && c.usable(s.gameSeconds) && s.installation==null})}
                content.addView(button("Back") {panelPage="combat";render()})
            }
            "payloads" -> {
                content.addView(text("Stored spells · select ${s.profile.releaseLimit} max",18f))
                if(s.installed?.payloads.isNullOrEmpty()) content.addView(text("No stored spells. Charge a core in Inventory."))
                s.installed?.payloads?.forEach {p->content.addView(button("${if(p.id in selected) "✓ Selected" else "Stored"} · ${p.template.name} · L${p.template.castingLevel}") {
                    val ids=if(p.id in selected) selected-p.id else if(s.profile.releaseLimit==1) setOf(p.id) else (selected+p.id).toList().takeLast(s.profile.releaseLimit).toSet()
                    run("Payload selected") {app.preferences.payloads(ids);app.preferences.mode(Ability.SPELL);if(s.profile.releaseLimit==1) panelPage="combat"}
                })}
                content.addView(button("No payload · arcane shot") {run("Payload cleared") {app.preferences.payloads(emptySet());app.preferences.mode(Ability.ARCANE);panelPage="combat"}})
                content.addView(button("Done") {panelPage="combat";render()})
            }
            "modes" -> {
                content.addView(text("Firing mode",20f))
                Ability.entries.forEach {mode->content.addView(button(mode.title,mode==Ability.SHATTER) {run("Mode selected") {app.preferences.mode(mode);panelPage=if(mode==Ability.ELEMENTAL) "elements" else if(mode in listOf(Ability.DISCHARGE,Ability.SHATTER)) "target" else "combat";editingTarget=panelPage=="target"}})}
                content.addView(button("Back") {panelPage="combat";render()})
            }
            "elements" -> {
                content.addView(text("Element selector · ${s.profile.elementCapacity} max",18f))
                Engine.elements(s.profile).forEach {type->content.addView(button("${if(type in elements) "✓ " else ""}${type.name.lowercase()}") {
                    val next=if(s.profile.elementCapacity==1) listOf(type) else if(type in elements) elements-type else (elements+type).takeLast(s.profile.elementCapacity)
                    panelPage=if(s.profile.elementCapacity==1) "combat" else "elements";saveElements(next)
                }.apply {setTextColor(elementTint(type).toArgb())})}
                content.addView(button("Done") {panelPage="combat";render()})
            }
            "target" -> {
                editingTarget=true
                content.addView(text("Target settings",20f))
                var ac=prefs.armorClass?.toString() ?: "";var range=distance.toString();var point=targetPoint
                content.addView(edit("Armor Class · optional",ac,true) {ac=it})
                content.addView(edit("Distance in feet",range,true) {range=it})
                if(prefs.mode in listOf(Ability.DISCHARGE,Ability.SHATTER)) {
                    content.addView(edit("Blast target point",point) {point=it})
                    content.addView(Switch(uiContext).apply {text="Direct pistol impact";isChecked=directImpact;setTextColor(Color.WHITE);setOnCheckedChangeListener {_,checked->directImpact=checked}})
                }
                content.addView(button("Save target settings") {run("Target updated") {
                    val d=range.toIntOrNull() ?: error("Enter a distance in feet");require(d in 0..180)
                    val armor=if(ac.isBlank()) null else ac.toIntOrNull() ?: error("Enter an AC or leave it blank")
                    app.preferences.targeting(options().copy(armorClass=armor));app.preferences.aim(d,point,directImpact,immediateId,manualSlot);distance=d;targetPoint=point;editingTarget=false;panelPage="combat"
                }})
                content.addView(button("Cancel") {directImpact=prefs.directImpact;editingTarget=false;panelPage="combat";render()})
            }
            "more" -> {
                content.addView(button("Target settings") {editingTarget=true;panelPage="target";render()})
                content.addView(button("Element selection") {panelPage="elements";render()})
                content.addView(button("Fresh slot: ${s.templates.find {it.id==immediateId}?.name ?: "None"}") {val ids=listOf<String?>(null)+s.templates.filter {it.approved}.map {it.id};val next=ids[(ids.indexOf(immediateId)+1)%ids.size];run("Fresh slot selected") {app.preferences.aim(distance,targetPoint,directImpact,next,manualSlot)}})
                if(immediateId!=null) content.addView(button("Slot availability: ${if(manualSlot) "confirmed" else "tap to confirm if unknown"}") {run("Availability updated") {app.preferences.aim(distance,targetPoint,directImpact,immediateId,!manualSlot)}})
                if(s.encounter) content.addView(button("Next turn · +6 game seconds") {run("Turn advanced") {app.repository.mutate(label="Next turn +6 seconds",origin="overlay") {Engine.advance(it,6,true)}}})
                s.pending.filter {it.result==null}.forEach {shot->content.addView(button("Finish ${shot.plan.ability.title}") {resultId=shot.plan.id;outcome=null;targetResults=emptyList();panelPage="result";render()})}
                if(prefs.manualOverrides) content.addView(button("Manual roll override") {try {val p=makePlan(s);run("External dice pending") {app.repository.commit(p,"overlay");resultId=p.id;panelPage="result"}} catch(e:Exception) {status=e.message ?: "Check shot";render()}})
                content.addView(button("Open full app") {mainApp()})
                content.addView(button("Hide · use notification to return") {hidden=true;removeWindow();updateNotification()})
                content.addView(button("Stop session") {stopSelf()})
                content.addView(button("Back") {panelPage="combat";render()})
            }
            else -> {
                content.addView(text("${prefs.mode.title}     +${s.profile.attack} TO HIT",20f))
                val planned=runCatching {makePlan(s)}
                val damage=planned.map {Engine.components(it).joinToString(" + ") {"${it.damage.dice} ${it.damage.type.name.lowercase()}"}}.getOrDefault("${s.profile.base} Force")
                content.addView(text(damage,16f))
                planned.getOrNull()?.takeIf {it.hasSpell}?.let {p->content.addView(text("Uses ${p.selectedPayloads.size} stored spell(s)${p.immediate?.let {" + level-${it.castingLevel} slot"} ?: ""}, even on a miss.",12f))}
                row(content,*RollMode.entries.map {mode->button("${if((mode==RollMode.ADVANTAGE && prefs.advantage) || (mode==RollMode.DISADVANTAGE && prefs.disadvantage) || (mode==RollMode.NORMAL && !prefs.advantage && !prefs.disadvantage)) "✓ " else ""}${when(mode) {RollMode.NORMAL->"Normal";RollMode.ADVANTAGE->"Adv";RollMode.DISADVANTAGE->"Dis"}}") {run("Roll mode selected") {app.preferences.targeting(options().copy(advantage=mode==RollMode.ADVANTAGE,disadvantage=mode==RollMode.DISADVANTAGE))}}}.toTypedArray())
                content.addView(button("Roll attack") {requestRoll(s)})
                row(content,button("${s.installed?.name ?: "Core"} · ${s.installed?.readiness(s.gameSeconds) ?: "None"}") {panelPage="cores";render()},button("Payload · ${s.installed?.payloads?.filter {it.id in selected}?.joinToString {it.template.name}?.ifBlank {"None"} ?: "None"}") {panelPage="payloads";render()})
                row(content,button("Mode") {panelPage="modes";render()},button("More") {panelPage="more";render()},button("Collapse") {expanded=false;render()})
                if(!s.attuned) content.addView(text("Confirm attunement in the full app"))
                if(pending?.rolled!=null) content.addView(button("Last roll · ${pending.rolled?.attackTotal ?: "blast"}") {resultId=pending.plan.id;panelPage="result";render()})
            }
        }
    }
    private fun recent(s: Campaign): String {
        val shot=s.pending.lastOrNull() ?: return "Astravar"
        if(shot.result==null) return "Roll pending"
        return if(shot.plan.blast) "Blast resolved" else "${Engine.damage(shot.plan,shot.result!!).values.sum()} damage"
    }
    private fun edit(hint: String,value: String,numeric: Boolean=false,change: (String)->Unit)=EditText(uiContext).apply {
        this.hint=hint;contentDescription=hint;setTextColor(Color.WHITE);setHintTextColor(Color.LTGRAY);setText(value);minHeight=maxOf(dp(48),(48*resources.displayMetrics.density).toInt())
        if(numeric) inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        doAfterTextChanged {change(it?.toString() ?: "")}
    }
    private fun resolutionEditor(content: LinearLayout,pending: PendingShot) {
        val p=pending.plan
        val auto=pending.rolled
        if(auto!=null) {
            content.addView(text(auto.attackDescription(p),18f))
            if(auto.suggestedOutcome!=null) outcome=auto.suggestedOutcome
            Engine.components(p,auto.critical).forEach {rollTexts[it.id]=auto.damage.getValue(it.id).total.toString()}
            attackText=auto.attackTotal?.toString() ?: ""
            content.addView(text("DC ${p.profile.saveDc} Dexterity · the DM supplies each creature's save."))
        } else content.addView(text("Manual override · costs already spent"))
        if((!p.blast || p.directImpact) && auto?.suggestedOutcome==null) {
            for(o in if(auto==null) listOf(Outcome.HIT,Outcome.CRITICAL,Outcome.MISS) else listOf(Outcome.HIT,Outcome.MISS)) content.addView(button("${if(outcome==o) "✓" else "○"} $o") {outcome=o;if(auto==null) rollTexts.clear();render()})
            if(auto==null) content.addView(edit("Attack total · modifier +${p.profile.attack}",attackText,true) {attackText=it})
        }
        val components=Engine.components(p,outcome==Outcome.CRITICAL).filterNot {outcome==Outcome.MISS && it.portion==Portion.DIRECT}
        components.forEach {c -> content.addView(text("${c.label}: ${c.damage.dice} ${c.damage.type}${auto?.let {" = ${it.damage.getValue(c.id).total}"} ?: ""}"));if(auto==null) content.addView(edit("Actual ${c.id} result",rollTexts[c.id] ?: "",true) {rollTexts[c.id]=it})}
        if(p.blast) {
            content.addView(text("Add all affected creatures. Saving with zero entries records no creatures affected."))
            targetResults.forEachIndexed {i,t ->
                content.addView(edit("Creature ${i+1}",t.name) {v ->targetResults=targetResults.toMutableList().also {it[i]=it[i].copy(name=v)}})
                content.addView(button("Save: ${t.saved?.let {if(it) "Succeeded" else "Failed"} ?: "not entered"} · tap to set") {targetResults=targetResults.toMutableList().also {it[i]=t.copy(saved=t.saved==false)};render()})
                if(p.directImpact) content.addView(button("${if(t.directTarget) "✓" else "○"} Direct target") {targetResults=targetResults.mapIndexed {j,x->x.copy(directTarget=j==i && !t.directTarget)};render()})
                components.map {it.damage.type}.distinct().forEach {type -> content.addView(button("$type defense: ${t.defenses[type] ?: Defense.NORMAL}") {val current=t.defenses[type] ?: Defense.NORMAL;targetResults=targetResults.toMutableList().also {it[i]=t.copy(defenses=t.defenses+(type to Defense.entries[(current.ordinal+1)%Defense.entries.size]))};render()})}
            }
            content.addView(button("Add affected creature") {if(targetResults.size<200) {targetResults=targetResults+TargetSave("Creature ${targetResults.size+1}",null);render()}})
        }
        content.addView(button(if(p.blast) "Finish blast · ${targetResults.size} creatures" else "Save manual override") {
            run("Player-entered results saved") {
                val result=if(auto!=null) DiceRoller.resolution(p,auto,outcome,targetResults) else Resolution(outcome ?: error("Choose the actual attack outcome"),attackText.takeIf {it.isNotBlank()}?.toIntOrNull(),components.associate {it.id to (rollTexts[it.id]?.toIntOrNull() ?: error("Enter ${it.label} result"))},targetResults)
                app.repository.mutate(label="Resolved ${p.ability} · player entered results",origin="overlay") {Engine.resolve(it,p.id,result)}
                panelPage="result"
            }
        })
        content.addView(button("Leave results pending") {resultId=null;render()})
    }
    private fun bounds(): IntArray {
        if(Build.VERSION.SDK_INT>=30) {
            val m=wm.currentWindowMetrics; val i=m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return intArrayOf(i.left,i.top,m.bounds.width()-i.right,m.bounds.height()-maxOf(i.bottom,imeBottom))
        }
        return intArrayOf(0,dp(24),resources.displayMetrics.widthPixels,resources.displayMetrics.heightPixels-dp(48))
    }
    private fun clamp(p: WindowManager.LayoutParams) { val b=bounds(); p.x=p.x.coerceIn(b[0],maxOf(b[0],b[2]-p.width)); p.y=p.y.coerceIn(b[1],maxOf(b[1],b[3]-p.height)) }
    private fun safeUpdate() { try { view?.let { wm.updateViewLayout(it,params) } } catch(_: RuntimeException) { stopSelf() } }
    private fun drag(handle: View) {
        var x=0f; var y=0f; var px=0; var py=0; var moved=false
        handle.setOnTouchListener { v,e ->
            val p=params ?: return@setOnTouchListener false
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x=e.rawX; y=e.rawY; px=p.x; py=p.y; moved=false; true }
                MotionEvent.ACTION_MOVE -> { val dx=e.rawX-x; val dy=e.rawY-y; if(abs(dx)+abs(dy)>dp(8)) moved=true; if(moved) { p.x=px+dx.toInt(); p.y=py+dy.toInt(); clamp(p); safeUpdate() }; true }
                MotionEvent.ACTION_UP -> { if(!moved) { v.performClick() } else { val b=bounds(); p.x=if(p.x+p.width/2<(b[2]+b[0])/2) b[0] else b[2]-p.width; clamp(p); safeUpdate(); scope.launch { app.preferences.position(!portrait,p.x,p.y) } }; true }
                else -> false
            }
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); portrait=newConfig.orientation!=Configuration.ORIENTATION_LANDSCAPE; params=null; imeBottom=0; render() }
    private fun removeWindow() { view?.let { try { wm.removeViewImmediate(it) } catch(_: RuntimeException) {} }; view=null }
    override fun onDestroy() { watcher.removeCallbacksAndMessages(null); if(registered) unregisterReceiver(lockReceiver); removeWindow(); scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
}
