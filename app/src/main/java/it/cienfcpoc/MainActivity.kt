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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private var lastCanCandidate:String?=null
    private var canStableFrames=0
    private val activityId=LifecycleTrace.newActivityId()
    private var canAcquiredAt:Long?=null
    private var canInterruptions=0
    private var mrzFrames=0
    private var mrzRemovedChars=0
    private val mrzLengths=mutableMapOf<Int,Int>()
    private val mrzLinesPerFrame=mutableMapOf<Int,Int>()
    private val mrzFormats=mutableMapOf<String,Int>()
    private val mrzFailedChecks=mutableMapOf<String,Int>()
    private val mrzFillerLengths=mutableMapOf<Int,Int>()
    private var mrzTd1Line1Total=0
    private var mrzTd1Line1Plausible=0
    private var mrzTd1Line2=0
    private var mrzTd1Pairs=0
    private val mrzTd1PairChecks=IntArray(4)
    private var mrzTd1FailedDoc=0; private var mrzTd1FailedBirth=0; private var mrzTd1FailedExpiry=0
    private var mrzTd3FailedDoc=0; private var mrzTd3FailedBirth=0; private var mrzTd3FailedExpiry=0
    private var mrzTd1CheckNonNumeric=0; private var mrzTd1CheckWrong=0
    private var mrzTd1FillerYes=0; private var mrzTd1FillerNo=0
    private var mrzTd1NonNumericWithFiller=0; private var mrzTd1NonNumericWithoutFiller=0
    private var mrzTd1Pos14Filler=0; private var mrzTd1Pos14DigitLike=0; private var mrzTd1Pos14Other=0
    private var mrzTd1SuccessRaw=0; private var mrzTd1SuccessA=0; private var mrzTd1SuccessB=0; private var mrzTd1SuccessAB=0
    private var mrzValid3Observed=0
    private val mrzValidKeys=mutableSetOf<String>()
    private val mrzLastSeenFrame=mutableMapOf<String,Int>()
    private var mrzMinRepeatDistance:Int?=null
    private var lastMrzKey:String?=null
    private var mrzStableFrames=0
    private val mainHandler=Handler(Looper.getMainLooper())
    private val canExpiry=Runnable { if(canState()=="SCADUTO"){ clearPendingCredentials(); traceLifecycle("CAN_EXPIRED"); findViewById<TextView>(R.id.scanStatus).text="CAN scaduto. Esegui una nuova scansione." } }
    private val cameraPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(granted) startDocumentCamera() else Toast.makeText(this,"Permesso fotocamera necessario per la scansione",Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)
        adapter=NfcAdapter.getDefaultAdapter(this)
        traceLifecycle("onCreate")

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

        findViewById<Button>(R.id.stopMrzDiagnostic).setOnClickListener{ stopMrzAndCopyDiagnostic() }
        findViewById<Button>(R.id.scanDocument).setOnClickListener{
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startDocumentCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        findViewById<Button>(R.id.arm).setOnClickListener{
            pending=snapshotCredentials()?:return@setOnClickListener
            if(pending!!.mode==AccessMode.CAN_PACE){canAcquiredAt=SystemClock.elapsedRealtime();canInterruptions=0;scheduleCanExpiry()}
            armNfc(if(pending!!.mode==AccessMode.BAC_ONLY)"SOLO BAC: usa un nuovo avvicinamento fisico del documento." else "Pronto. Avvicina il documento NFC.")
        }
        findViewById<Button>(R.id.copyReport).setOnClickListener{
            (getSystemService(Context.CLIPBOARD_SERVICE)as ClipboardManager).setPrimaryClip(ClipData.newPlainText("CIE NFC POC report",report+"\nLifecycle: "+LifecycleTrace.summary()))
            Toast.makeText(this,"Report senza PII copiato",Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDocumentCamera(){
        clearPendingCredentials()
        canInterruptions=0
        lastCanCandidate=null
        canStableFrames=0
        resetMrzDiagnostics()
        findViewById<Button>(R.id.stopMrzDiagnostic).visibility=if(scanKind==ScanKind.MRZ)View.VISIBLE else View.GONE
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
                            ScanKind.CIE_CAN -> observeCan(findCan(text.text), hasCieFrontMarker(text.text))
                            ScanKind.MRZ -> { val parsed=MrzParser.parseWithDiagnostic(text.text); observeMrzDiagnostic(parsed.second); observeMrzCandidate(parsed.first) }
                        }
                    }
                    .addOnCompleteListener{recognizing.set(false);proxy.close()}
            }
            provider.unbindAll()
            provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis)
        },ContextCompat.getMainExecutor(this))
    }

    private fun normalizeOcr(raw:String)=raw.uppercase()
        .replace('À','A').replace('Á','A').replace('È','E').replace('É','E').replace('Ì','I').replace('Ò','O').replace('Ù','U')

    private fun hasCieFrontMarker(raw:String):Boolean {
        val normalized=normalizeOcr(raw)
        return normalized.contains("CARTA DI IDENTITA") ||
            normalized.contains("CARTA D'IDENTITA") ||
            normalized.contains("IDENTITY CARD")
    }

    private fun findCan(raw:String):String? {
        val normalized=normalizeOcr(raw)
        if(!hasCieFrontMarker(normalized))return null
        val matches=Regex("(?<!\\d)\\d{6}(?!\\d)").findAll(normalized).map{it.value}.distinct().toList()
        return matches.singleOrNull()
    }

    private fun observeCan(can:String?,frontMarker:Boolean){
        if(!frontMarker || can==null){
            lastCanCandidate=null
            canStableFrames=0
            return
        }
        if(can==lastCanCandidate) canStableFrames++ else {
            lastCanCandidate=can
            canStableFrames=1
        }
        if(canStableFrames>=3)onCanFound(can)
    }

    private fun onCanFound(can:String){
        if(pending!=null)return
        pending=NfcCredentials(AccessMode.CAN_PACE,can=can,origin=CredentialOrigin.SCANSIONE_CAN)
        canAcquiredAt=SystemClock.elapsedRealtime()
        scheduleCanExpiry()
        cameraProvider?.unbindAll()
        findViewById<PreviewView>(R.id.cameraPreview).visibility=View.GONE
        findViewById<TextView>(R.id.scanStatus).text="CAN rilevato. Valore nascosto e mantenuto solo in memoria."
        armNfc("CAN rilevato localmente. Avvicina la CIE.")
    }


    private fun observeMrzCandidate(data:MrzAccessData?){
        if(data==null){return}
        val key=data.documentNumber+"|"+data.birthYYMMDD+"|"+data.expiryYYMMDD
        mrzValid3Observed++;mrzValidKeys+=key
        mrzLastSeenFrame[key]?.let{prev->val distance=mrzFrames-prev;mrzMinRepeatDistance=mrzMinRepeatDistance?.let{old->minOf(old,distance)}?:distance}
        mrzLastSeenFrame[key]=mrzFrames
        val previous=mrzLastSeenFrame[key]
        if(previous!=null && mrzFrames-previous<=10)onMrzFound(data)
        lastMrzKey=key;mrzStableFrames=1
    }

    private fun onMrzFound(data:MrzAccessData){
        if(pending!=null)return
        pending=NfcCredentials(AccessMode.BAC_ONLY,documentNumber=data.documentNumber,birthYYMMDD=data.birthYYMMDD,expiryYYMMDD=data.expiryYYMMDD,origin=CredentialOrigin.SCANSIONE_MRZ)
        cameraProvider?.unbindAll()
        findViewById<PreviewView>(R.id.cameraPreview).visibility=View.GONE
        findViewById<Button>(R.id.stopMrzDiagnostic).visibility=View.GONE
        findViewById<TextView>(R.id.scanStatus).text="MRZ verificata. Dati mantenuti solo in memoria."
        armNfc("MRZ verificata localmente. Avvicina il documento NFC.")
    }


    private fun resetMrzDiagnostics(){
        mrzFrames=0;mrzRemovedChars=0;mrzLengths.clear();mrzLinesPerFrame.clear();mrzFormats.clear();mrzFailedChecks.clear();mrzFillerLengths.clear()
        mrzTd1Line1Total=0;mrzTd1Line1Plausible=0;mrzTd1Line2=0;mrzTd1Pairs=0;mrzTd1PairChecks.fill(0)
        mrzTd1FailedDoc=0;mrzTd1FailedBirth=0;mrzTd1FailedExpiry=0;mrzTd3FailedDoc=0;mrzTd3FailedBirth=0;mrzTd3FailedExpiry=0
        mrzTd1CheckNonNumeric=0;mrzTd1CheckWrong=0;mrzTd1FillerYes=0;mrzTd1FillerNo=0
        mrzTd1NonNumericWithFiller=0;mrzTd1NonNumericWithoutFiller=0;mrzTd1Pos14Filler=0;mrzTd1Pos14DigitLike=0;mrzTd1Pos14Other=0
        mrzTd1SuccessRaw=0;mrzTd1SuccessA=0;mrzTd1SuccessB=0;mrzTd1SuccessAB=0
        mrzValid3Observed=0;mrzValidKeys.clear();mrzLastSeenFrame.clear();mrzMinRepeatDistance=null;lastMrzKey=null;mrzStableFrames=0
    }

    private fun observeMrzDiagnostic(d:MrzFrameDiagnostic){
        mrzFrames++
        mrzRemovedChars+=d.removedChars
        d.candidateLengths.forEach{mrzLengths[it]=(mrzLengths[it]?:0)+1}
        mrzLinesPerFrame[d.mlKitLineCount]=(mrzLinesPerFrame[d.mlKitLineCount]?:0)+1
        mrzFormats[d.attemptedFormat]=(mrzFormats[d.attemptedFormat]?:0)+1
        d.fillerLineLengths.forEach{mrzFillerLengths[it]=(mrzFillerLengths[it]?:0)+1}
        mrzTd1Line1Total+=d.td1Line1Total;mrzTd1Line1Plausible+=d.td1Line1Plausible;mrzTd1Line2+=d.td1Line2Count;mrzTd1Pairs+=d.td1PairsTried
        for(i in 0..3)mrzTd1PairChecks[i]+=d.td1PairCheckCounts[i]
        mrzTd1FailedDoc+=d.td1FailedDoc;mrzTd1FailedBirth+=d.td1FailedBirth;mrzTd1FailedExpiry+=d.td1FailedExpiry
        mrzTd3FailedDoc+=d.td3FailedDoc;mrzTd3FailedBirth+=d.td3FailedBirth;mrzTd3FailedExpiry+=d.td3FailedExpiry
        mrzTd1CheckNonNumeric+=d.td1CheckDigitNonNumeric;mrzTd1CheckWrong+=d.td1CheckDigitWrong
        mrzTd1FillerYes+=d.td1FillerPos1Present;mrzTd1FillerNo+=d.td1FillerPos1Absent
        mrzTd1NonNumericWithFiller+=d.td1NonNumericWithFiller;mrzTd1NonNumericWithoutFiller+=d.td1NonNumericWithoutFiller
        mrzTd1Pos14Filler+=d.td1Pos14Filler;mrzTd1Pos14DigitLike+=d.td1Pos14DigitLikeLetter;mrzTd1Pos14Other+=d.td1Pos14OtherLetter
        mrzTd1SuccessRaw+=d.td1SuccessRaw;mrzTd1SuccessA+=d.td1SuccessA;mrzTd1SuccessB+=d.td1SuccessB;mrzTd1SuccessAB+=d.td1SuccessAB
    }

    private fun mrzDiagnosticReport():String{
        fun dist(m:Map<*,Int>)=if(m.isEmpty())"NESSUNO" else m.entries.sortedBy{it.key.toString()}.joinToString(", "){it.key.toString()+":"+it.value}
        val avg=if(mrzFrames==0)0.0 else mrzRemovedChars.toDouble()/mrzFrames
        return listOf(
            "DIAGNOSTICA MRZ — SENZA PII",
            "Fotogrammi analizzati: $mrzFrames",
            "Lunghezze righe candidate: "+dist(mrzLengths),
            "Righe ML Kit per fotogramma: "+dist(mrzLinesPerFrame),
            "Righe con '<': distribuzione lunghezze: "+dist(mrzFillerLengths),
            "Formato tentato: "+dist(mrzFormats),
            "Check falliti TD1: documentNumber=$mrzTd1FailedDoc; birthDate=$mrzTd1FailedBirth; expiryDate=$mrzTd1FailedExpiry",
            "Check falliti TD3: documentNumber=$mrzTd3FailedDoc; birthDate=$mrzTd3FailedBirth; expiryDate=$mrzTd3FailedExpiry",
            "TD1 riga1: totali=$mrzTd1Line1Total; plausibili=$mrzTd1Line1Plausible; riga2=$mrzTd1Line2",
            "TD1 coppie plausibili provate: $mrzTd1Pairs; check 3/3=${mrzTd1PairChecks[3]}, 2/3=${mrzTd1PairChecks[2]}, 1/3=${mrzTd1PairChecks[1]}, 0/3=${mrzTd1PairChecks[0]}",
            "TD1 numero documento: checkDigitNonNumerico=$mrzTd1CheckNonNumeric; checkDigitErrato=$mrzTd1CheckWrong",
            "TD1 posizione 1 filler: presente=$mrzTd1FillerYes; assente=$mrzTd1FillerNo",
            "TD1 check non numerico x filler: presente=$mrzTd1NonNumericWithFiller; assente=$mrzTd1NonNumericWithoutFiller",
            "TD1 classe pos14: filler=$mrzTd1Pos14Filler; letteraSimileCifra=$mrzTd1Pos14DigitLike; altraLettera=$mrzTd1Pos14Other",
            "TD1 successi 3/3 per correzione: senza=$mrzTd1SuccessRaw; soloA=$mrzTd1SuccessA; soloB=$mrzTd1SuccessB; A+B=$mrzTd1SuccessAB",
            "TD1 chiavi 3/3: osservate=$mrzValid3Observed; distinte=${mrzValidKeys.size}; distanzaMinimaRipetizione=${mrzMinRepeatDistance?.toString()?:"NESSUNA"}",
            "Caratteri rimossi normalizzazione: totale=$mrzRemovedChars; media/fotogramma="+String.format(java.util.Locale.US,"%.2f",avg)
        ).joinToString("\n")
    }

    private fun stopMrzAndCopyDiagnostic(){
        cameraProvider?.unbindAll()
        findViewById<PreviewView>(R.id.cameraPreview).visibility=View.GONE
        findViewById<Button>(R.id.stopMrzDiagnostic).visibility=View.GONE
        val diagnostic=mrzDiagnosticReport()
        report=diagnostic
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Diagnostica MRZ",diagnostic))
        findViewById<TextView>(R.id.scanStatus).text="Scansione MRZ interrotta. Diagnostica senza PII copiata."
        Toast.makeText(this,"Diagnostica MRZ senza PII copiata",Toast.LENGTH_SHORT).show()
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
        return NfcCredentials(mode,if(mode==AccessMode.CAN_PACE)can else null,doc.ifBlank{null},birth.ifBlank{null},expiry.ifBlank{null},if(mode==AccessMode.CAN_PACE)CredentialOrigin.MANUALE_CAN else CredentialOrigin.MANUALE_MRZ)
    }

    override fun onTagDiscovered(tag:Tag){
        val credentials=pending
        val iso=IsoDep.get(tag)
        val result=when {
            credentials==null -> NfcReadResult("CIE NFC POC — REPORT SENZA PII\nCredenziali: NON_DISPONIBILI\nEsito: ERROR","",NfcOutcome.ERROR)
            iso==null -> NfcReadResult("CIE NFC POC — REPORT SENZA PII\nIsoDep: NON_DISPONIBILE\nEsito: ERROR","",NfcOutcome.ERROR)
            else -> CieNfcReader().read(iso,credentials)
        }
        report=result.report
        runOnUiThread{
            findViewById<TextView>(R.id.screenData).text=result.screenData
            adapter?.disableReaderMode(this)
            when(result.outcome){
                NfcOutcome.SUCCESS -> {
                    findViewById<TextView>(R.id.status).text=report
                    findViewById<TextView>(R.id.scanStatus).text="Credenziale di accesso rimossa dalla memoria della sessione."
                    clearPendingCredentials();canInterruptions=0;clearInputsUi()
                }
                NfcOutcome.CAN_REJECTED -> {
                    findViewById<TextView>(R.id.status).text="CAN non valido, riscansiona il fronte della CIE."
                    findViewById<TextView>(R.id.scanStatus).text="PACE-CAN rifiutata. Riscansiona il fronte della CIE."
                    clearPendingCredentials();clearInputsUi();canInterruptions=0
                    scanKind=ScanKind.CIE_CAN
                    findViewById<RadioButton>(R.id.documentCie).isChecked=true
                    startDocumentCamera()
                }
                NfcOutcome.READ_INTERRUPTED -> {
                    if(credentials?.mode==AccessMode.CAN_PACE && canState()=="PRESENTE"){
                        canInterruptions++
                        if(canInterruptions>=3){
                            findViewById<TextView>(R.id.status).text="Tre letture interrotte. Esegui una nuova scansione CAN."
                            clearPendingCredentials();clearInputsUi();canInterruptions=0
                            startDocumentCamera()
                        } else {
                            findViewById<TextView>(R.id.status).text="Lettura interrotta, tieni la carta ferma e riavvicinala."
                            findViewById<TextView>(R.id.scanStatus).text="CAN mantenuto solo in memoria. Avvicina di nuovo la CIE."
                            armNfc("Lettura interrotta, tieni la carta ferma e riavvicinala.")
                        }
                    } else {
                        findViewById<TextView>(R.id.status).text="Lettura interrotta. Ripeti l'acquisizione."
                        clearPendingCredentials();clearInputsUi()
                    }
                }
                NfcOutcome.ERROR -> {
                    findViewById<TextView>(R.id.status).text=report
                    findViewById<TextView>(R.id.scanStatus).text="Errore di lettura. Credenziale rimossa."
                    clearPendingCredentials();clearInputsUi()
                }
            }
            updateLifecycleView()
        }
    }

    private fun canState():String {
        val p=pending
        val acquired=canAcquiredAt
        if(p?.mode!=AccessMode.CAN_PACE || acquired==null)return "ASSENTE"
        return if(SystemClock.elapsedRealtime()-acquired>=120000L)"SCADUTO" else "PRESENTE"
    }

    private fun scheduleCanExpiry(){
        mainHandler.removeCallbacks(canExpiry)
        mainHandler.postDelayed(canExpiry,120000L)
    }

    private fun clearPendingCredentials(){
        pending=null
        canAcquiredAt=null
        mainHandler.removeCallbacks(canExpiry)
    }

    private fun traceLifecycle(event:String){
        LifecycleTrace.add(activityId,event,canState())
        updateLifecycleView()
    }

    private fun updateLifecycleView(){
        findViewById<TextView>(R.id.lifecycleTrace)?.text="DEV lifecycle: "+LifecycleTrace.summary()
    }

    override fun onResume(){
        super.onResume()
        traceLifecycle("onResume")
        when(canState()){
            "PRESENTE" -> armNfc("Avvicina di nuovo la CIE.")
            "SCADUTO" -> {clearPendingCredentials();traceLifecycle("CAN_EXPIRED")}
        }
    }

    override fun onPause(){
        adapter?.disableReaderMode(this)
        traceLifecycle("onPause")
        super.onPause()
    }

    override fun onStop(){
        clearPendingCredentials()
        traceLifecycle("onStop")
        super.onStop()
    }

    private fun clearInputsUi(){listOf(R.id.can,R.id.docNumber,R.id.birth,R.id.expiry).forEach{findViewById<EditText>(it).text.clear()}}
    override fun onDestroy(){
        clearPendingCredentials()
        traceLifecycle("onDestroy")
        adapter?.disableReaderMode(this)
        cameraProvider?.unbindAll()
        recognizer.close()
        cameraExecutor.shutdownNow()
        clearInputsUi()
        findViewById<TextView>(R.id.screenData).text=""
        super.onDestroy()
    }
}
