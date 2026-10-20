package com.example.fbautoposter

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var groupsContainer: LinearLayout
    private lateinit var groupCount: TextView
    private lateinit var listSpinner: Spinner
    private lateinit var message: EditText
    private lateinit var api: EditText
    private lateinit var delay: EditText
    private lateinit var mode: Spinner
    private var selectedIds = linkedSetOf<String>()
    private var currentList = "Усі групи"
    private var suppressSpinner = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("posting", MODE_PRIVATE)
        if (GroupsStore.all(this).isEmpty()) {
            GroupsStore.importLines(this, prefs.getString("urls", "") ?: "")
        }
        selectedIds.addAll(GroupsStore.all(this).map { it.id })

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 22, 26, 30)
        }
        fun label(value: String) = TextView(this).also { it.text = value; it.textSize = 17f; it.setPadding(0, 20, 0, 6); layout.addView(it) }
        fun button(value: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = value; setOnClickListener { action() } })
        }
        fun field(hint: String, key: String, lines: Int = 1): EditText = EditText(this).also {
            it.hint = hint
            it.setText(prefs.getString(key, ""))
            it.minLines = lines
            layout.addView(it)
        }

        label("FB Auto Poster • групи та списки")
        button("1. Увімкнути Accessibility Service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("2. Дозволити оверлей") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("Відкрити мої групи у Facebook") { openFacebookGroups() }
        button("ЗІБРАТИ ГРУПИ (автопрокрутка Facebook)") {
            val service = PostingAccessibilityService.instance
            if (service == null) toast("Спочатку ввімкніть Accessibility Service")
            else {
                service.startGroupScan()
                toast("Після сканування поверніться сюди й натисніть Оновити")
            }
        }
        button("Оновити знайдені групи") { refreshLists(); renderGroups() }

        label("Імпорт груп із посилань (можна вставити експорт з розширення)")
        val importField = EditText(this).apply {
            hint = "Назва групи | https://www.facebook.com/groups/123456/\nКожна група з нового рядка"
            minLines = 3
        }
        layout.addView(importField)
        button("Додати групи з посилань") {
            val count = GroupsStore.importLines(this, importField.text.toString())
            importField.text.clear()
            selectedIds.addAll(GroupsStore.all(this).map { it.id })
            refreshLists(); renderGroups()
            toast("Додано нових груп: $count")
        }
        label("Мої списки")
        listSpinner = Spinner(this)
        layout.addView(listSpinner)
        listSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { }
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (suppressSpinner) return
                val name = listSpinner.getItemAtPosition(position).toString()
                currentList = name
                selectedIds.clear()
                if (name == "Усі групи") selectedIds.addAll(GroupsStore.all(this@MainActivity).map { it.id })
                else selectedIds.addAll(GroupsStore.lists(this@MainActivity)[name].orEmpty())
                renderGroups()
            }
        }
        button("Зберегти вибрані групи як список") {
            val field = EditText(this).apply { hint = "Наприклад: Вакансії Чехія" }
            AlertDialog.Builder(this).setTitle("Назва списку")
                .setView(field).setPositiveButton("Зберегти") { _, _ ->
                    val name = field.text.toString().trim()
                    if (name.isNotBlank()) {
                        GroupsStore.saveList(this, name, selectedIds)
                        currentList = name
                        refreshLists()
                        toast("Список збережено")
                    }
                }.setNegativeButton("Скасувати", null).show()
        }
        button("Видалити поточний список") {
            if (currentList == "Усі групи") { toast("Спершу виберіть власний список"); return@button }
            AlertDialog.Builder(this).setMessage("Видалити список «$currentList»? Групи залишаться в каталозі.")
                .setPositiveButton("Видалити") { _, _ ->
                    GroupsStore.deleteList(this, currentList)
                    currentList = "Усі групи"
                    refreshLists(); renderGroups()
                }.setNegativeButton("Ні", null).show()
        }

        groupCount = TextView(this).apply { textSize = 15f; setPadding(0, 20, 0, 4); layout.addView(this) }
        button("Вибрати всі") { selectedIds.addAll(GroupsStore.all(this).map { it.id }); renderGroups() }
        button("Зняти вибір") { selectedIds.clear(); renderGroups() }
        groupsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(groupsContainer)
        button("Видалити вибрані групи з каталогу") {
            if (selectedIds.isEmpty()) return@button
            AlertDialog.Builder(this).setMessage("Видалити ${selectedIds.size} вибраних груп із каталогу?")
                .setPositiveButton("Видалити") { _, _ ->
                    GroupsStore.remove(this, selectedIds)
                    selectedIds.clear()
                    refreshLists(); renderGroups()
                }.setNegativeButton("Ні", null).show()
        }

        label("Текст допису")
        message = field("Текст оголошення", "message", 5)
        label("Groq AI")
        api = field("Groq API key (необов'язково)", "api")
        delay = field("Затримка між групами, секунди", "delay").also {
            if (it.text.isBlank()) it.setText("60")
        }
        mode = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("OFF", "LIGHT", "HEAVY"))
            setSelection(listOf("OFF", "LIGHT", "HEAVY").indexOf(prefs.getString("mode", "OFF")).coerceAtLeast(0))
            layout.addView(this)
        }
        button("ПОЧАТИ ПУБЛІКАЦІЇ") { startPosting() }
        button("Зупинити") { PostingAccessibilityService.instance?.stopPosting() }
        button("Скопіювати журнал публікацій") {
            val logText = prefs.getString("posting_log", "Ще немає записів").orEmpty()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("FB Auto Poster log", logText))
            toast("Журнал скопійовано")
        }
        button("Скопіювати діагностику кнопки") {
            val debug = prefs.getString("button_debug", "Ще немає діагностики") ?: ""
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Facebook post button diagnostics", debug))
            toast("Скопійовано — надішліть мені текст, якщо кнопку не знаходить")
        }
        layout.addView(TextView(this).apply {
            text = "Важливо: сканування офіційного Facebook експериментальне. Якщо група зібрана без URL, застосунок шукатиме її за назвою у списку. Для точності імпортуйте прямі посилання."
            setPadding(4, 14, 4, 15)
        })
        val scroll = ScrollView(this)
        scroll.addView(layout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        refreshLists(); renderGroups()
    }

    override fun onResume() { super.onResume(); if (::groupCount.isInitialized) { refreshLists(); renderGroups() } }

    private fun openFacebookGroups() {
        val uri = Uri.parse("https://www.facebook.com/groups/joins/")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.facebook.katana") }
        try { startActivity(intent) }
        catch (_: Exception) { toast("Встановіть офіційний Facebook") }
    }

    private fun refreshLists() {
        if (!::listSpinner.isInitialized) return
        val options = listOf("Усі групи") + GroupsStore.lists(this).keys.sorted()
        suppressSpinner = true
        listSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        val position = options.indexOf(currentList).let { if (it < 0) 0 else it }
        listSpinner.setSelection(position)
        suppressSpinner = false
    }

    private fun renderGroups() {
        if (!::groupsContainer.isInitialized) return
        val groups = GroupsStore.all(this)
        groupCount.text = "Груп: ${groups.size}, вибрано: ${selectedIds.size}"
        groupsContainer.removeAllViews()
        groups.forEach { group ->
            val chk = CheckBox(this).apply {
                text = "${group.name}${if (group.url.isBlank()) " · без посилання" else ""}"
                isChecked = group.id in selectedIds
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedIds.add(group.id) else selectedIds.remove(group.id)
                    groupCount.text = "Груп: ${groups.size}, вибрано: ${selectedIds.size}"
                }
            }
            groupsContainer.addView(chk)
        }
    }

    private fun startPosting() {
        val prefs = getSharedPreferences("posting", MODE_PRIVATE)
        val ids = JSONArray().apply { selectedIds.forEach { put(it) } }
        prefs.edit()
            .putString("selected_ids", ids.toString())
            .putString("message", message.text.toString())
            .putString("api", api.text.toString())
            .putString("delay", delay.text.toString())
            .putString("mode", mode.selectedItem.toString())
            .apply()
        val service = PostingAccessibilityService.instance
        if (service == null) toast("Увімкніть Accessibility Service")
        else service.startPosting()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
