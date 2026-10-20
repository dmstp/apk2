package com.example.fbautoposter

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Experimental UI automation. Facebook UI varies by language/version; no guaranteed posting. */
class PostingAccessibilityService: AccessibilityService() {
 companion object { var instance:PostingAccessibilityService?=null; private set }
 private val handler=Handler(Looper.getMainLooper())
 private var urls=listOf<String>(); private var index=0; private var active=false; private var stage=0
 private var text=""; private var overlay:TextView?=null; private var wm:WindowManager?=null
 private var delayMs=60000L; private var mode="OFF"; private var api=""
 override fun onServiceConnected() { instance=this }
 override fun onInterrupt() { }
 override fun onDestroy() { stopPosting(); instance=null; super.onDestroy() }
 override fun onAccessibilityEvent(event:AccessibilityEvent?) { if(active) handler.removeCallbacks(scanRunnable).also { handler.postDelayed(scanRunnable,800) } }
 private val scanRunnable=Runnable { advance() }
 fun startPosting() {
  val p=getSharedPreferences("posting",MODE_PRIVATE)
  urls=p.getString("urls","")!!.lines().map { it.trim() }.filter { it.startsWith("https://www.facebook.com/groups/") || it.startsWith("https://m.facebook.com/groups/") }
  text=p.getString("message","")!!.trim(); api=p.getString("api","")!!.trim(); mode=p.getString("mode","OFF")!!
  delayMs=(p.getString("delay","60")!!.toLongOrNull()?:60L).coerceAtLeast(15L)*1000L
  if(urls.isEmpty()||text.isEmpty()){ status("Вкажіть групи і текст"); return }
  index=0; active=true; showOverlay(); openNext()
 }
 fun stopPosting(){ active=false; handler.removeCallbacksAndMessages(null); status("Зупинено"); overlay?.let { wm?.removeView(it) };overlay=null }
 private fun status(s:String){ overlay?.text="FB Poster | ${index+1}/${urls.size}\n$s" }
 private fun showOverlay(){ if(!Settings.canDrawOverlays(this))return
  wm=getSystemService(WINDOW_SERVICE) as WindowManager
  overlay=TextView(this).apply { setBackgroundColor(0xDD172033.toInt());setTextColor(-1);textSize=15f;setPadding(20,16,20,16) }
  val params=WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.START; x=12;y=80 }
  wm?.addView(overlay,params)
 }
 private fun openNext(){ if(!active)return; if(index>=urls.size){ status("Готово: перевірте результати у Facebook");active=false;return }
  stage=0;status("Відкриваю групу")
  val uri=Uri.parse(urls[index]); val intent=Intent(Intent.ACTION_VIEW,uri).apply { setPackage("com.facebook.katana");addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
  try{ startActivity(intent) }catch(e:Exception){status("Не вдалося відкрити Facebook: ${e.message}");active=false;return}
  handler.postDelayed(scanRunnable,4000)
 }
 private fun nodes(root:AccessibilityNodeInfo?):List<AccessibilityNodeInfo>{ if(root==null)return emptyList();val all=mutableListOf<AccessibilityNodeInfo>();fun walk(n:AccessibilityNodeInfo){all.add(n);for(i in 0 until n.childCount)n.getChild(i)?.let(::walk)};walk(root);return all }
 private fun tapLabel(labels:List<String>):Boolean{
  val root=rootInActiveWindow?:return false
  for(n in nodes(root)){val s=((n.text?:n.contentDescription)?:"").toString().lowercase();if(labels.any { s.contains(it) }){var target:AccessibilityNodeInfo?=n;while(target!=null&&!target.isClickable)target=target.parent;if(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)return true} };return false
 }
 private fun advance(){ if(!active)return
  when(stage){
   0 -> { status("Шукаю поле публікації");if(tapLabel(listOf("write something","write a post","напишіть щось","створити допис","напишите что-нибудь","co se vám honí hlavou","napište něco"))){stage=1;handler.postDelayed(scanRunnable,1800)}else handler.postDelayed(scanRunnable,1800) }
   1 -> {status("Готую текст Groq");stage=2
    if(mode=="OFF"||api.isBlank()){handler.post(scanRunnable)}else thread { val result=rewrite(text,mode,api);handler.post { if(active){prepared=result;handler.post(scanRunnable)} } }
   }
   2 -> {status("Вставляю текст");val root=rootInActiveWindow;val edit=nodes(root).firstOrNull { it.isEditable }
    if(edit!=null){val args=android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,prepared.ifBlank { text }) };if(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)){stage=3;handler.postDelayed(scanRunnable,1200)}else status("Не вдалося вставити текст")}
    else handler.postDelayed(scanRunnable,1200)
   }
   3 -> {status("Натискаю Опублікувати");if(tapLabel(listOf("post","publish","опублікувати","опубликовать","zveřejnit"))){stage=4;handler.postDelayed(scanRunnable,5000)}else handler.postDelayed(scanRunnable,1400) }
   4 -> {status("Очікую перед наступною групою");index++;prepared="";handler.postDelayed({openNext()},delayMs) }
  }
 }
 private var prepared=""
 private fun rewrite(source:String,mode:String,key:String):String = try {
  val conn=URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
  conn.requestMethod="POST";conn.connectTimeout=20000;conn.readTimeout=45000;conn.doOutput=true
  conn.setRequestProperty("Authorization","Bearer $key");conn.setRequestProperty("Content-Type","application/json")
  val prompt="Rewrite the Facebook job ad naturally in the same language. Keep facts, salaries, addresses and phone numbers unchanged. Output only plain text. Strength: $mode"
  val body=JSONObject().put("model","openai/gpt-oss-20b").put("temperature",0.7).put("max_completion_tokens",1800).put("messages",JSONArray().put(JSONObject().put("role","system").put("content",prompt)).put(JSONObject().put("role","user").put("content",source)))
  conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
  val response=conn.inputStream.bufferedReader().use { it.readText() };conn.disconnect()
  JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim().ifBlank { source }
 }catch(e:Exception){ source }
}
