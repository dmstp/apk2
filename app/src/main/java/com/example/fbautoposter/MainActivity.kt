package com.example.fbautoposter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
  val prefs = getSharedPreferences("posting", MODE_PRIVATE)
  val layout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(30,30,30,20) }
  fun field(hint:String, key:String, lines:Int=1): EditText = EditText(this).also { e -> e.hint=hint; e.setText(prefs.getString(key,"")); e.minLines=lines; layout.addView(e) }
  val urls=field("Посилання на групи, кожне з нового рядка", "urls",4)
  val message=field("Текст оголошення", "message",5)
  val api=field("Groq API key (необов'язково)", "api")
  val delay=field("Затримка між групами, секунди", "delay").also { if(it.text.isEmpty())it.setText("60") }
  val mode=Spinner(this).also { it.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("OFF","LIGHT","HEAVY")); layout.addView(it) }
  fun button(title:String, action:()->Unit){ layout.addView(Button(this).apply { text=title; setOnClickListener { action() } }) }
  button("1. Дозволити керування Facebook") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
  button("2. Дозволити оверлей") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) }
  button("3. Запустити") {
   prefs.edit().putString("urls",urls.text.toString()).putString("message",message.text.toString()).putString("api",api.text.toString()).putString("delay",delay.text.toString()).putString("mode",mode.selectedItem.toString()).apply()
   val service=PostingAccessibilityService.instance
   if(service==null) Toast.makeText(this,"Увімкніть Accessibility Service",Toast.LENGTH_LONG).show()
   else service.startPosting()
  }
  button("Зупинити") { PostingAccessibilityService.instance?.stopPosting() }
  val scroll=ScrollView(this); scroll.addView(layout); setContentView(scroll)
 }
}
