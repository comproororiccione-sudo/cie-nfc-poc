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
    @Volatile private var pending: NfcCredentials? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        adapter=NfcAdapter.getDefaultAdapter(this)
        findViewById<RadioGroup>(R.id.mode).setOnCheckedChangeListener { _, id ->
            val canMode=id==R.id.modeCan
            findViewById<EditText>(R.id.can).visibility=if(canMode) View.VISIBLE else View.GONE
            listOf(R.id.docNumber,R.id.birth,R.id.expiry).forEach { findViewById<EditText>(it).visibility=if(canMode) View.GONE else View.VISIBLE }
        }
        findViewById<Button>(R.id.arm).setOnClickListener {
            pending=snapshotCredentials() ?: return@setOnClickListener
            findViewById<TextView>(R.id.status).text=if(pending!!.mode==AccessMode.BAC_ONLY)
                "SOLO BAC: usa un nuovo avvicinamento della CIE (reset fisico)." else "Pronto. Avvicina la CIE."
            adapter?.enableReaderMode(this,this,NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                Bundle().apply{putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY,250)})
        }
        findViewById<Button>(R.id.copyReport).setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("CIE NFC POC report",report))
            Toast.makeText(this,"Report senza PII copiato",Toast.LENGTH_SHORT).show()
        }
    }

    private fun snapshotCredentials():NfcCredentials? {
        val mode=when(findViewById<RadioGroup>(R.id.mode).checkedRadioButtonId){
            R.id.modeCan->AccessMode.CAN_PACE
            R.id.modeBac->AccessMode.BAC_ONLY
            else->AccessMode.MRZ_PACE_WITH_BAC_FALLBACK
        }
        val can=findViewById<EditText>(R.id.can).text.toString()
        val doc=findViewById<EditText>(R.id.docNumber).text.toString()
        val birth=findViewById<EditText>(R.id.birth).text.toString()
        val expiry=findViewById<EditText>(R.id.expiry).text.toString()
        val ok=if(mode==AccessMode.CAN_PACE) can.matches(Regex("\\d{6}")) else doc.isNotBlank()&&birth.matches(Regex("\\d{6}"))&&expiry.matches(Regex("\\d{6}"))
        if(!ok){Toast.makeText(this,"Controlla i dati inseriti",Toast.LENGTH_SHORT).show();return null}
        return NfcCredentials(mode,if(mode==AccessMode.CAN_PACE)can else null,doc.ifBlank{null},birth.ifBlank{null},expiry.ifBlank{null})
    }

    override fun onTagDiscovered(tag:Tag) {
        val credentials=pending
        val iso=IsoDep.get(tag)
        val result=if(credentials==null) NfcReadResult("CIE NFC POC — REPORT SENZA PII\nCredenziali: NON_DISPONIBILI","")
        else if(iso==null) NfcReadResult("CIE NFC POC — REPORT SENZA PII\nIsoDep: NON_DISPONIBILE","")
        else CieNfcReader().read(iso,credentials)
        report=result.report; pending=null
        runOnUiThread {
            findViewById<TextView>(R.id.status).text=report
            findViewById<TextView>(R.id.screenData).text=result.screenData
            clearInputsUi()
            adapter?.disableReaderMode(this)
        }
    }
    private fun clearInputsUi(){listOf(R.id.can,R.id.docNumber,R.id.birth,R.id.expiry).forEach{findViewById<EditText>(it).text.clear()}}
    override fun onDestroy(){pending=null;clearInputsUi();findViewById<TextView>(R.id.screenData).text="";super.onDestroy()}
}