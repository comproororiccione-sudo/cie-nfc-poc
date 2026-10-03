package it.cienfcpoc

import android.nfc.tech.IsoDep
import net.sf.scuba.smartcards.CardService
import net.sf.scuba.smartcards.CardServiceException
import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.LDSFileUtil
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.COMFile
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG12File
import java.security.MessageDigest
import java.io.IOException

enum class AccessMode { CAN_PACE, MRZ_PACE_WITH_BAC_FALLBACK, BAC_ONLY }
enum class CredentialOrigin { SCANSIONE_CAN, MANUALE_CAN, SCANSIONE_MRZ, MANUALE_MRZ }
data class NfcCredentials(val mode:AccessMode,val can:String?=null,val documentNumber:String?=null,val birthYYMMDD:String?=null,val expiryYYMMDD:String?=null,val origin:CredentialOrigin)
enum class NfcOutcome { SUCCESS, CAN_REJECTED, READ_INTERRUPTED, ERROR }
data class NfcReadResult(val report:String,val screenData:String,val outcome:NfcOutcome)

object NfcFailureClassifier {
 fun ioCauseName(t:Throwable):String?=generateSequence(t as Throwable?){it.cause}.firstOrNull{it is IOException || it.javaClass.simpleName=="TagLostException"}?.let{if(it.javaClass.simpleName=="TagLostException")"TagLostException" else "IOException"}
 fun isInterrupted(t:Throwable):Boolean=ioCauseName(t)!=null
 fun isCanRejectedDuringPace(t:Throwable):Boolean=!isInterrupted(t) && generateSequence(t as Throwable?){it.cause}.any{it is CardServiceException || it.javaClass.simpleName=="PACEException"}
}

class CieNfcReader {
 fun read(isoDep:IsoDep,c:NfcCredentials):NfcReadResult {
  val out=mutableListOf("CIE NFC POC v"+BuildConfig.VERSION_NAME+" — REPORT SENZA PII","Build commit: "+BuildConfig.GIT_SHA,"Origine credenziale: "+c.origin.name); val screen=mutableListOf("DATI LETTI (solo a schermo, non copiati)")
  val started=System.currentTimeMillis(); var service:PassportService?=null; var outcome=NfcOutcome.ERROR
  try {
   isoDep.timeout=12000
   service=PassportService(CardService.getInstance(isoDep),PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,PassportService.DEFAULT_MAX_BLOCKSIZE,false,false);service.open()
   val paceInfos=try{CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS)).securityInfos.filterIsInstance<PACEInfo>()}catch(e:Exception){if(NfcFailureClassifier.isInterrupted(e))throw e;out+="EF.CardAccess: "+status(e);emptyList()}
   if(paceInfos.isNotEmpty())out+="EF.CardAccess: LETTO; PACE dichiarati="+paceInfos.size
   var auth=false;var paceOk=false
   if(c.mode==AccessMode.CAN_PACE){
    val key=PACEKeySpec.createCANKey(c.can!!)
    for(p in paceInfos)try{service.doPACE(key,p.objectIdentifier,PACEInfo.toParameterSpec(p.parameterId),p.parameterId);out+="PACE-CAN: OK";auth=true;paceOk=true;break}catch(e:Exception){out+="PACE-CAN: "+status(e);if(NfcFailureClassifier.isInterrupted(e))throw e;if(NfcFailureClassifier.isCanRejectedDuringPace(e)){out+="Causa I/O in catena: NO";outcome=NfcOutcome.CAN_REJECTED}else throw e}
    if(auth)try{service.sendSelectApplet(true);out+="SELECT applet dopo PACE: OK"}catch(e:Exception){out+="SELECT applet dopo PACE: "+status(e);if(NfcFailureClassifier.isInterrupted(e))throw e;auth=false}
   } else {
    val key=BACKey(c.documentNumber,c.birthYYMMDD,c.expiryYYMMDD)
    if(c.mode==AccessMode.MRZ_PACE_WITH_BAC_FALLBACK){
     for(p in paceInfos)try{service.doPACE(key,p.objectIdentifier,PACEInfo.toParameterSpec(p.parameterId),p.parameterId);out+="PACE-MRZ: OK";auth=true;paceOk=true;break}catch(e:Exception){out+="PACE-MRZ: "+status(e);if(NfcFailureClassifier.isInterrupted(e))throw e}
    }
    if(paceOk)try{service.sendSelectApplet(true);out+="SELECT applet dopo PACE: OK"}catch(e:Exception){out+="SELECT applet dopo PACE: "+status(e);auth=false}
    else try{service.sendSelectApplet(false);out+="SELECT applet prima di BAC: OK";service.doBAC(key);out+=(if(c.mode==AccessMode.BAC_ONLY)"BAC SOLO (nuovo avvicinamento): OK" else "BAC fallback: OK");auth=true}catch(e:Exception){out+="BAC: "+status(e);if(NfcFailureClassifier.isInterrupted(e))throw e;auth=false}
   }
   if(auth){inspect(service,out,screen);outcome=NfcOutcome.SUCCESS} else out+="Accesso DG: NON_ESEGUITO"
  }catch(e:Exception){out+="Sessione: "+status(e);val io=NfcFailureClassifier.ioCauseName(e);out+="Causa I/O in catena: "+if(io!=null)"SI ($io)" else "NO";outcome=if(io!=null)NfcOutcome.READ_INTERRUPTED else if(outcome==NfcOutcome.CAN_REJECTED)NfcOutcome.CAN_REJECTED else NfcOutcome.ERROR}
  finally{try{service?.close()}catch(_:Exception){};try{isoDep.close()}catch(_:Exception){};out+="Esito: "+outcome.name;out+="Tempo totale: "+(System.currentTimeMillis()-started)+"ms"}
  return NfcReadResult(out.joinToString("\n"),screen.joinToString("\n"),outcome)
 }

 private fun inspect(service:PassportService,out:MutableList<String>,screen:MutableList<String>){
  val com=try{COMFile(service.getInputStream(PassportService.EF_COM))}catch(e:Exception){if(NfcFailureClassifier.isInterrupted(e))throw e;out+="EF.COM: "+status(e);null}
  val comDgs=com?.let{LDSFileUtil.getDataGroupNumbers(it).toSet()}.orEmpty()
  if(com!=null)out+="DG da EF.COM: "+comDgs.sorted().joinToString(","){"DG"+it}
  val sod=try{SODFile(service.getInputStream(PassportService.EF_SOD))}catch(e:Exception){if(NfcFailureClassifier.isInterrupted(e))throw e;out+="EF.SOD: "+status(e);null}
  val sodDgs=sod?.dataGroupHashes?.keys?.toSet().orEmpty()
  if(sod!=null)out+="DG da SOD: "+sodDgs.sorted().joinToString(","){"DG"+it}
  if(com!=null&&sod!=null){val diff=((comDgs-sodDgs)+(sodDgs-comDgs)).sorted();out+="Discrepanze COM/SOD: "+if(diff.isEmpty())"NESSUNA" else diff.joinToString(","){"DG"+it}}
  val declared=(if(comDgs.isNotEmpty())comDgs else sodDgs).sorted();val read=mutableMapOf<Int,ByteArray>()
  var cfAt:String?=null;var birthAt:String?=null;var addressAt:String?=null
  for(dg in declared){
   if(dg==2||dg==3||dg==4){out+="DG"+dg+": DICHIARATO_DALLA_CARTA; NON_SUPPORTATO_DAL_POC; NON_TENTATO";continue}
   val fid=try{LDSFileUtil.lookupFIDByDataGroupNumber(dg)}catch(_:Exception){out+="DG"+dg+": NON_SUPPORTATO_DAL_POC";continue}
   try{
    val b=service.getInputStream(fid).readBytes();read[dg]=b;out+="DG"+dg+": DICHIARATO_DALLA_CARTA; LETTO; bytes="+b.size
    when(dg){
     1->{val m=DG1File(b.inputStream()).mrzInfo;out+="DG1 parser: MRZ PRESENTE";screen+="DG1 MRZ: "+m.toString()}
     11->{val f=DG11File(b.inputStream());field(out,"DG11 fullName",f.nameOfHolder);screenValue(screen,"DG11 nome",f.nameOfHolder);field(out,"DG11 personalNumber",f.personalNumber)
       val pn=f.personalNumber;if(!pn.isNullOrBlank()){out+="DG11 personalNumber: lunghezza16="+(pn.length==16)+"; strutturaCF="+validCf(pn);cfAt="DG11 personalNumber";screenValue(screen,"DG11 personalNumber",pn)}
       field(out,"DG11 placeOfBirth",f.placeOfBirth);if(f.placeOfBirth.isNotEmpty()){birthAt="DG11 placeOfBirth";screenValue(screen,"DG11 luogo nascita",f.placeOfBirth.joinToString(" / "))}
       field(out,"DG11 permanentAddress",f.permanentAddress);if(f.permanentAddress.isNotEmpty()){addressAt="DG11 permanentAddress";screenValue(screen,"DG11 indirizzo",f.permanentAddress.joinToString(" / "))}
       field(out,"DG11 telephone",f.telephone);field(out,"DG11 profession",f.profession);field(out,"DG11 title",f.title);field(out,"DG11 personalSummary",f.personalSummary);field(out,"DG11 custodyInformation",f.custodyInformation);field(out,"DG11 otherNames",f.otherNames);field(out,"DG11 fullDateOfBirth",f.fullDateOfBirth)}
     12->{val f=DG12File(b.inputStream());field(out,"DG12 issuingAuthority",f.issuingAuthority);screenValue(screen,"DG12 autorità rilascio",f.issuingAuthority);field(out,"DG12 dateOfIssue",f.dateOfIssue);screenValue(screen,"DG12 data rilascio",f.dateOfIssue);field(out,"DG12 namesOfOtherPersons",f.namesOfOtherPersons);field(out,"DG12 endorsementsAndObservations",f.endorsementsAndObservations);field(out,"DG12 taxOrExitRequirements",f.taxOrExitRequirements);out+="DG12 imageOfFront: "+imageMeta(f.imageOfFront);out+="DG12 imageOfRear: "+imageMeta(f.imageOfRear);field(out,"DG12 dateAndTimeOfPersonalization",f.dateAndTimeOfPersonalization);field(out,"DG12 personalizationSystemSerialNumber",f.personalizationSystemSerialNumber)}
     else->out+="DG"+dg+" parser: NON_SUPPORTATO_DAL_POC; lunghezza="+b.size
    }
   }catch(e:Exception){if(NfcFailureClassifier.isInterrupted(e))throw e;out+="DG"+dg+": DICHIARATO_DALLA_CARTA; "+status(e)}
  }
  out+="CF in: "+(cfAt?:"NON_PRESENTE");out+="Luogo di nascita in: "+(birthAt?:"NON_PRESENTE");out+="Indirizzo in: "+(addressAt?:"NON_PRESENTE")
  if(sod!=null)try{var n=0;var ok=true;for((dg,b)in read){val exp=sod.dataGroupHashes[dg]?:continue;n++;if(!MessageDigest.getInstance(sod.digestAlgorithm).digest(b).contentEquals(exp))ok=false};out+="Integrità hash SOD dei DG letti: "+if(n==0)"NON_VERIFICATO" else if(ok)"PASS_PARZIALE" else "FAIL"}catch(_:Exception){out+="Integrità hash SOD dei DG letti: NON_VERIFICATO"}
  out+="Firma SOD: NON_VERIFICATO";out+="Catena CSCA: NON_VERIFICATO";out+="CA/AA: NON_VERIFICATO"
 }
 private fun status(e:Exception):String{val c=generateSequence(e as Throwable?){it.cause}.filterIsInstance<CardServiceException>().firstOrNull();if(c!=null){val sw=c.sw;val s=when(sw){0x6A82->"NON_PRESENTE";0x6982,0x6985->"ACCESSO_NEGATO";else->"ERRORE"};return s+" SW=0x"+("%04X".format(sw and 0xFFFF))+" exception="+e.javaClass.simpleName};return "ERRORE "+e.javaClass.simpleName}
 private fun field(out:MutableList<String>,name:String,v:Any?){val len=when(v){null->0;is String->v.length;is Collection<*>->v.sumOf{it?.toString()?.length?:0};else->v.toString().length};out+=name+": "+if(len>0)"PRESENTE lunghezza="+len else "ASSENTE"}
 private fun screenValue(s:MutableList<String>,n:String,v:Any?){if(v!=null&&v.toString().isNotBlank())s+=n+": "+v}
 private fun imageMeta(b:ByteArray?):String=if(b==null||b.isEmpty())"ASSENTE" else "PRESENTE bytes="+b.size+" (NON_DECODIFICATA)"
 private fun validCf(raw:String):Boolean {
  val cf=raw.uppercase()
  if(!cf.matches(Regex("[A-Z0-9]{16}"))) return false
  val odd=mapOf('0' to 1,'1' to 0,'2' to 5,'3' to 7,'4' to 9,'5' to 13,'6' to 15,'7' to 17,'8' to 19,'9' to 21,'A' to 1,'B' to 0,'C' to 5,'D' to 7,'E' to 9,'F' to 13,'G' to 15,'H' to 17,'I' to 19,'J' to 21,'K' to 2,'L' to 4,'M' to 18,'N' to 20,'O' to 11,'P' to 3,'Q' to 6,'R' to 8,'S' to 12,'T' to 14,'U' to 16,'V' to 10,'W' to 22,'X' to 25,'Y' to 24,'Z' to 23)
  fun even(c:Char)=if(c.isDigit()) c-'0' else c-'A'
  var sum=0
  for(i in 0..14) {
   if(i%2==0) { val value=odd[cf[i]] ?: return false; sum+=value } else sum+=even(cf[i])
  }
  return ('A'.code+sum%26).toChar()==cf[15]
 }
}