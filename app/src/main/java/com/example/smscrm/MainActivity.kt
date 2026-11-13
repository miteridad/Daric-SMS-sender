package com.example.smscrm

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var dbHelper: ContactDbHelper
    private lateinit var etTemplate: EditText
    private lateinit var etPhone: EditText
    private lateinit var rgSim: RadioGroup
    private lateinit var lvContacts: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private val contactList = ArrayList<String>()

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
        private const val CREATE_CSV_REQUEST_CODE = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dbHelper = ContactDbHelper(this)
        etTemplate = findViewById(R.id.etTemplate)
        etPhone = findViewById(R.id.etPhone)
        rgSim = findViewById(R.id.rgSim)
        lvContacts = findViewById(R.id.lvContacts)

        val btnSaveTemplate = findViewById<Button>(R.id.btnSaveTemplate)
        val btnNextStep = findViewById<Button>(R.id.btnNextStep)
        val btnExportCsv = findViewById<Button>(R.id.btnExportCsv)

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedTemplate = prefs.getString("sms_template", "سلام {name} عزیز، از آشنایی با شما خوشوقتم.")
        etTemplate.setText(savedTemplate)

        btnSaveTemplate.setOnClickListener {
            val templateText = etTemplate.text.toString().trim()
            prefs.edit().putString("sms_template", templateText).apply()
            Toast.makeText(this, "متن پیش‌فرض ذخیره شد", Toast.LENGTH_SHORT).show()
        }

        checkPermissions()

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, contactList)
        lvContacts.adapter = adapter
        loadContactsFromDb()

        btnNextStep.setOnClickListener {
            val phone = etPhone.text.toString().trim()
            val template = etTemplate.text.toString().trim()

            if (phone.isEmpty()) {
                etPhone.error = "شماره موبایل را وارد کنید"
                return@setOnClickListener
            }
            if (!template.contains("{name}")) {
                Toast.makeText(this, "لطفاً متغیر {name} را در متن پیامک قرار دهید", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            showStepTwoDialog(phone, template)
        }

        btnExportCsv.setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/csv"
                putExtra(Intent.EXTRA_TITLE, "contacts_crm.csv")
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, CREATE_CSV_REQUEST_CODE)
        }
    }

    private fun showStepTwoDialog(phone: String, template: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            layoutDirection = LinearLayout.LAYOUT_DIRECTION_RTL
        }

        val etName = EditText(this).apply {
            hint = "نام مخاطب (جایگذاری در پیامک و ذخیره)"
        }
        val etCompany = EditText(this).apply {
            hint = "نام شرکت (فقط ذخیره در دیتابیس)"
        }

        layout.addView(etName)
        layout.addView(etCompany)

        AlertDialog.Builder(this)
            .setTitle("تکمیل اطلاعات برای $phone")
            .setView(layout)
            .setPositiveButton("ارسال و ذخیره") { _, _ ->
                val name = etName.text.toString().trim()
                val company = etCompany.text.toString().trim()

                if (name.isEmpty()) {
                    Toast.makeText(this, "وارد کردن نام الزامی است", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val finalMessage = template.replace("{name}", name)
                val selectedSimSlot = if (rgSim.checkedRadioButtonId == R.id.rbSim2) 1 else 0

                sendSmsWithSim(phone, finalMessage, selectedSimSlot)
                dbHelper.insertContact(name, company, phone)
                loadContactsFromDb()
                etPhone.text.clear()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun sendSmsWithSim(phone: String, message: String, simSlotIndex: Int) {
        if (!hasRequiredPermissions()) {
            checkPermissions()
            return
        }

        try {
            val subManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val activeSubs = subManager.activeSubscriptionInfoList

            val smsManager: SmsManager = if (activeSubs != null && activeSubs.size > simSlotIndex) {
                val subId = activeSubs[simSlotIndex].subscriptionId
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getSmsManagerForSubscriptionId(subId)
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
            }

            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            Toast.makeText(this, "پیامک با موفقیت ارسال و در دیتابیس ثبت شد", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "دسترسی سیم‌کارت یا پیامک داده نشده است", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در ارسال پیامک: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CREATE_CSV_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                writeCsvToUri(uri)
            }
        }
    }

    private fun writeCsvToUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val writer = outputStream.bufferedWriter(Charsets.UTF_8)
                writer.write("\uFEFF")
                writer.write("نام,نام شرکت,شماره موبایل\n")

                val rows = dbHelper.getAllContactsRaw()
                for (row in rows) {
                    val safeName = row[0].replace(",", " ")
                    val safeCompany = row[1].replace(",", " ")
                    val safePhone = row[2].replace(",", " ")
                    writer.write("$safeName,$safeCompany,$safePhone\n")
                }
                writer.flush()
            }
            Toast.makeText(this, "فایل اکسل (CSV) با موفقیت ذخیره شد", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در ذخیره فایل: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadContactsFromDb() {
        contactList.clear()
        contactList.addAll(dbHelper.getAllContactsFormatted())
        adapter.notifyDataSetChanged()
    }

    private fun hasRequiredPermissions(): Boolean {
        val smsPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
        val phonePerm = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
        return smsPerm == PackageManager.PERMISSION_GRANTED && phonePerm == PackageManager.PERMISSION_GRANTED
    }

    private fun checkPermissions() {
        if (!hasRequiredPermissions()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE),
                PERMISSION_REQUEST_CODE
            )
        }
    }
}

class ContactDbHelper(context: Context) : SQLiteOpenHelper(context, "SmsCrm.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE contacts (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT, " +
                    "company TEXT, " +
                    "phone TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS contacts")
        onCreate(db)
    }

    fun insertContact(name: String, company: String, phone: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("name", name)
            put("company", company)
            put("phone", phone)
        }
        db.insert("contacts", null, values)
    }

    fun getAllContactsFormatted(): List<String> {
        val list = ArrayList<String>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT name, company, phone FROM contacts ORDER BY id DESC", null)
        if (cursor.moveToFirst()) {
            do {
                val name = cursor.getString(0)
                val company = cursor.getString(1).ifEmpty { "بدون شرکت" }
                val phone = cursor.getString(2)
                list.add("نام: $name | شرکت: $company | شماره: $phone")
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun getAllContactsRaw(): List<Array<String>> {
        val list = ArrayList<Array<String>>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT name, company, phone FROM contacts ORDER BY id DESC", null)
        if (cursor.moveToFirst()) {
            do {
                list.add(
                    arrayOf(
                        cursor.getString(0) ?: "",
                        cursor.getString(1) ?: "",
                        cursor.getString(2) ?: ""
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }
}
