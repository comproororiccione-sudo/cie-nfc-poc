package it.cienfcpoc

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {
    private enum class ScanKind { CIE_CAN, MRZ }

    private var adapter:NfcAdapter?=null
    private var report="Nessun report."
    @Volatile private var pending:NfcCredentials?=null
    @Volatile private var scanKind=ScanKind.CIE_CAN
    private val cameraExecutor=Executors.newSingleThreadExecutor()
    private val recognizing=AtomicBoolean(false)
    private val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var cameraProvider:ProcessCameraProvider?=null
    private val cameraPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(granted) startDocumentCamera() else Toast.makeText(this,"Permesso fotocamera necessario per la scansione",Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)
        adapter=NfcAdapter.getDefaultAdapter(this)

        findViewById<RadioGroup>(R.id.documentType).setOnCheckedChangeListener{_,id->
            scanKind=if(id==R.id.documentCie) ScanKind.CIE_CAN else ScanKind.MRZ
            findViewById<Button>(R.id.scanDocument).text=if(scanKind==ScanKind.CIE_CAN)"SCANSIONA CAN CIE" else "SCANSIONA MRZ"
            findViewById<TextView>(R.id.scanStatus).text=if(scanKind==ScanKind.CIE_CAN)
                "Inquadra il fronte della CIE. Il CAN resta solo in memoria."
            else "Inquadra la MRZ. I dati MRZ restano solo in memoria."
        }

        findViewById<RadioGroup>(R.id.mode).setOnCheckedChangeListener{_,id->
            val canMode=id==R.id.modeCan
            findViewById<EditText>(R.id.can).visibility=if(canMode)View.VISIBLE else View.GONE
            listOf(R.id.docNumber,R.id.birth,R.id.expiry).forEach{findViewById<EditText>(it).visibility=if(canMode)View.GONE else View.VISIBLE}
        }

        findViewById<Button>(R.id.scanDocument).setOnClickListener{
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startDocumentCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        findViewById<Button>(R.id.arm).setOnClickListener{
            pending=snapshotCredentials()?:return@setOnClickListener
            armNfc(if(pending!!.mode==AccessMode.BAC_ONLY)"SOLO BAC: usa un nuovo avvicinamento fisico del documento." else "Pronto. Avvicina il documento NFC.")
        }
        findViewById<Button>(R.id.copyReport).setOnClickListener{
            (getSystemService(Context.CLIPBOARD_SERVICE)as ClipboardManager).setPrimaryClip(ClipData.newPlainText("CIE NFC POC report",report))
            Toast.makeText(this,"Report senza PII copiato",Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDocumentCamera(){
        pending=null
        val previewView=findViewById<PreviewView>(R.id.cameraPreview)
        previewView.visibility=View.VISIBLE
        findViewById<TextView>(R.id.scanStatus).text=if(scanKind==ScanKind.CIE_CAN)
            "Inquadra il fronte della CIE e mantienilo fermo."
        else "Inquadra la zona MRZ del documento."
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider=future.get();cameraProvider=provider
            val preview=Preview.Builder().build().also{it.setSurfaceProvider(previewView.surfaceProvider)}
            val analysis=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(cameraExecutor){proxy->
                if(!recognizing.compareAndSet(false,true)){proxy.close();return@setAnalyzer}
                val media=proxy.image
                if(media==null){recognizing.set(false);proxy.close();return@setAnalyzer}
                recognizer.process(InputImage.fromMediaImage(media,proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener{text->
                        when(scanKind){
                            ScanKind.CIE_CAN -> findCan(text.text)?.let{onCanFound(it)}
                            ScanKind.MRZ -> MrzParser.parse(text.text)?.let{onMrzFound(it)}
                        }
                    }
                    .addOnCompleteListener{recognizing.set(false);proxy.close()}
            }
            provider.unbindAll()
            provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis)
        },ContextCompat.getMainExecutor(this))
    }

    private fun findCan(raw:String):String? {
        val matches=Regex("(?<!\\d)\\d{6}(?!\\d)").findAll(raw).map{it.value}.distinct().toList()
        return matches.singleOrNull()
    }

    private fun onCanFound(can:String){
        if(pending!=null)return
        pending=NfcCredentials(AccessMode.CAN_PACE,can=can)
        cameraProvider?.unbindAll()
        findViewById<PreviewView>(R.id.cameraPreview).visibility=View.GONE
        findViewById<TextView>(R.id.scanStatus).text="CAN rilevato. Valore nascosto e mantenuto solo in memoria."
        armNfc("CAN rilevato localmente. Avvicina la CIE.")
    }

    private fun onMrzFound(data:MrzAccessData){
        if(pending!=null)return
        pending=NfcCredentials(AccessMode.BAC_ONLY,documentNumber=data.documentNumber,birthYYMMDD=data.birthYYMMDD,expiryYYMMDD=data.expiryYYMMDD)
        cameraProvider?.unbindAll()
        findViewById<PreviewView>(R.id.cameraPreview).visibility=View.GONE
        findViewById<TextView>(R.id.scanStatus).text="MRZ verificata. Dati mantenuti solo in memoria."
        armNfc("MRZ verificata localmente. Avvicina il documento NFC.")
    }

    private fun armNfc(message:String){
        findViewById<TextView>(R.id.status).text=message
        adapter?.enableReaderMode(this,this,NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            Bundle().apply{putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY,250)})
    }

    private fun snapshotCredentials():NfcCredentials?{
        val mode=when(findViewById<RadioGroup>(R.id.mode).checkedRadioButtonId){
            R.id.modeCan->AccessMode.CAN_PACE
            R.id.modeBac->AccessMode.BAC_ONLY
            else->AccessMode.MRZ_PACE_WITH_BAC_FALLBACK
        }
        val can=findViewById<EditText>(R.id.can).text.toString()
        val doc=findViewById<EditText>(R.id.docNumber).text.toString()
        val birth=findViewById<EditText>(R.id.birth).text.toString()
        val expiry=findViewById<EditText>(R.id.expiry).text.toString()
        val ok=if(mode==AccessMode.CAN_PACE)can.matches(Regex("\\d{6}")) else doc.isNotBlank()&&birth.matches(Regex("\\d{6}"))&&expiry.matches(Regex("\\d{6}"))
        if(!ok){Toast.makeText(this,"Controlla i dati inseriti",Toast.LENGTH_SHORT).show();return null}
        return NfcCredentials(mode,if(mode==AccessMode.CAN_PACE)can else null,doc.ifBlank{null},birth.ifBlank{null},expiry.ifBlank{null})
    }

    override fun onTagDiscovered(tag:Tag){
        val credentials=pending
        val iso=IsoDep.get(tag)
        val result=if(credentials==null)NfcReadResult("CIE NFC POC — REPORT SENZA PII\nCredenziali: NON_DISPONIBILI","")
        else if(iso==null)NfcReadResult("CIE NFC POC — REPORT SENZA PII\nIsoDep: NON_DISPONIBILE","")
        else CieNfcReader().read(iso,credentials)
        report=result.report;pending=null
        runOnUiThread{
            findViewById<TextView>(R.id.status).text=report
            findViewById<TextView>(R.id.screenData).text=result.screenData
            findViewById<TextView>(R.id.scanStatus).text="Credenziale di accesso rimossa dalla memoria della sessione."
            clearInputsUi()
            adapter?.disableReaderMode(this)
        }
    }

    private fun clearInputsUi(){listOf(R.id.can,R.id.docNumber,R.id.birth,R.id.expiry).forEach{findViewById<EditText>(it).text.clear()}}
    override fun onDestroy(){
        pending=null
        adapter?.disableReaderMode(this)
        cameraProvider?.unbindAll()
        recognizer.close()
        cameraExecutor.shutdownNow()
        clearInputsUi()
        findViewById<TextView>(R.id.screenData).text=""
        super.onDestroy()
    }
}
