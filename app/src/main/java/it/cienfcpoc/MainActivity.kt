package it.cienfcpoc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {
    private var adapter: NfcAdapter? = null
    private var report = "Nessun report."
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        adapter = NfcAdapter.getDefaultAdapter(this)
        findViewById<RadioGroup>(R.id.mode).setOnCheckedChangeListener { _, id ->
            val mrz = id == R.id.modeMrz
            findViewById<EditText>(R.id.can).visibility = if (mrz) View.GONE else View.VISIBLE
            listOf(R.id.docNumber, R.id.birth, R.id.expiry).forEach {
                findViewById<EditText>(it).visibility = if (mrz) View.VISIBLE else View.GONE
            }
        }
        findViewById<Button>(R.id.arm).setOnClickListener {
            if (!validInput()) return@setOnClickListener
            findViewById<TextView>(R.id.status).text = "Pronto. Avvicina la CIE."
            adapter?.enableReaderMode(this, this,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) })
        }
        findViewById<Button>(R.id.copyReport).setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("CIE NFC POC report", report))
            Toast.makeText(this, "Report senza PII copiato", Toast.LENGTH_SHORT).show()
        }
    }
    private fun validInput(): Boolean {
        val canMode = findViewById<RadioButton>(R.id.modeCan).isChecked
        val ok = if (canMode) findViewById<EditText>(R.id.can).text.toString().matches(Regex("\\d{6}"))
        else findViewById<EditText>(R.id.docNumber).text.isNotBlank()
            && findViewById<EditText>(R.id.birth).text.toString().matches(Regex("\\d{6}"))
            && findViewById<EditText>(R.id.expiry).text.toString().matches(Regex("\\d{6}"))
        if (!ok) Toast.makeText(this, "Controlla i dati inseriti", Toast.LENGTH_SHORT).show()
        return ok
    }
    override fun onTagDiscovered(tag: Tag) {
        val iso = IsoDep.get(tag)
        if (iso == null) {
            report = "CIE NFC POC — REPORT SENZA PII\nIsoDep: NON_DISPONIBILE"
        } else {
            val canMode = findViewById<RadioButton>(R.id.modeCan).isChecked
            val credentials = if (canMode) {
                NfcCredentials(can = findViewById<EditText>(R.id.can).text.toString())
            } else {
                NfcCredentials(
                    documentNumber = findViewById<EditText>(R.id.docNumber).text.toString(),
                    birthYYMMDD = findViewById<EditText>(R.id.birth).text.toString(),
                    expiryYYMMDD = findViewById<EditText>(R.id.expiry).text.toString()
                )
            }
            report = CieNfcReader().read(iso, credentials)
        }
        runOnUiThread { findViewById<TextView>(R.id.status).text = report }
        clearInputs()
        adapter?.disableReaderMode(this)
    }
    private fun clearInputs() = runOnUiThread {
        listOf(R.id.can, R.id.docNumber, R.id.birth, R.id.expiry).forEach { findViewById<EditText>(it).text.clear() }
    }
    override fun onDestroy() {
        clearInputs()
        super.onDestroy()
    }
}
