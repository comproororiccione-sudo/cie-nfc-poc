package it.cienfcpoc

import android.nfc.tech.IsoDep
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.COMFile
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.SODFile
import java.security.MessageDigest

data class NfcCredentials(val can:String?=null,val documentNumber:String?=null,val birthYYMMDD:String?=null,val expiryYYMMDD:String?=null)

class CieNfcReader {
 fun read(isoDep:IsoDep,c:NfcCredentials):String {
  val out=mutableListOf("CIE NFC POC — REPORT SENZA PII"); val started=System.currentTimeMillis(); var service:PassportService?=null
  try {
   isoDep.timeout=12000
   service=PassportService(CardService.getInstance(isoDep),PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,PassportService.DEFAULT_MAX_BLOCKSIZE,false,false)
   service.open()
   val paceInfos=try{CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS)).securityInfos.filterIsInstance<PACEInfo>()}catch(_:Exception){emptyList()}
   out+="EF.CardAccess: "+if(paceInfos.isEmpty())"NON_LETTO/NON_PRESENTE" else "LETTO"
   out+="PACE dichiarati: "+paceInfos.size
   var auth=false
   if(!c.can.isNullOrBlank()){
    val key=PACEKeySpec.createCANKey(c.can)
    for(p in paceInfos){val t=System.currentTimeMillis();try{service.doPACE(key,p.objectIdentifier,PACEInfo.toParameterSpec(p.parameterId),null);out+="PACE-CAN: OK ("+(System.currentTimeMillis()-t)+"ms)";auth=true;break}catch(e:Exception){out+="PACE-CAN: ERRORE "+e.javaClass.simpleName+" ("+(System.currentTimeMillis()-t)+"ms)"}}
   } else if(!c.documentNumber.isNullOrBlank()&&!c.birthYYMMDD.isNullOrBlank()&&!c.expiryYYMMDD.isNullOrBlank()){
    val key=BACKey(c.documentNumber,c.birthYYMMDD,c.expiryYYMMDD)
    for(p in paceInfos){val t=System.currentTimeMillis();try{service.doPACE(key,p.objectIdentifier,PACEInfo.toParameterSpec(p.parameterId),null);out+="PACE-MRZ: OK ("+(System.currentTimeMillis()-t)+"ms)";auth=true;break}catch(e:Exception){out+="PACE-MRZ: ERRORE "+e.javaClass.simpleName+" ("+(System.currentTimeMillis()-t)+"ms)"}}
    if(!auth){val t=System.currentTimeMillis();try{service.doBAC(key);out+="BAC: OK ("+(System.currentTimeMillis()-t)+"ms)";auth=true}catch(e:Exception){out+="BAC: ERRORE "+e.javaClass.simpleName+" ("+(System.currentTimeMillis()-t)+"ms)"}}
   }
   if(!auth){out+="Accesso DG: NON_ESEGUITO";return out.joinToString("\n")}
   try{service.sendSelectApplet(true)}catch(_:Exception){}
   val com=try{COMFile(service.getInputStream(PassportService.EF_COM))}catch(_:Exception){null}
   val tags=com?.tagList?.toSet().orEmpty(); out+="EF.COM: "+if(com==null)"NON_LETTO" else "LETTO"
   out+="DG dichiarati: "+if(tags.isEmpty())"NON_DISPONIBILE" else tags.joinToString(","){"0x%02X".format(it)}
   val sod=try{SODFile(service.getInputStream(PassportService.EF_SOD))}catch(_:Exception){null}; out+="SOD: "+if(sod==null)"NON_LETTO" else "LETTO"
   val files=listOf(1 to PassportService.EF_DG1,11 to PassportService.EF_DG11,12 to PassportService.EF_DG12,14 to PassportService.EF_DG14,15 to PassportService.EF_DG15)
   val read=mutableMapOf<Int,ByteArray>()
   for((dg,fid) in files){val t=System.currentTimeMillis();try{val b=service.getInputStream(fid).readBytes();read[dg]=b;out+="DG"+dg+": LETTO bytes="+b.size+" ("+(System.currentTimeMillis()-t)+"ms)";if(dg==1){try{DG1File(b.inputStream()).mrzInfo;out+="DG1 campi: MRZ"}catch(_:Exception){out+="DG1 parsing: ERRORE"}}}catch(e:Exception){out+="DG"+dg+": ACCESSO_NEGATO/ERRORE "+e.javaClass.simpleName+" ("+(System.currentTimeMillis()-t)+"ms)"}}
   out+=listOf("DG2: NON_TENTATO","DG3: NON_TENTATO","DG4: NON_TENTATO")
   if(sod!=null){try{var ok=true;var n=0;val alg=sod.digestAlgorithm;for((dg,b) in read){val exp=sod.dataGroupHashes[dg]?:continue;n++;if(!MessageDigest.getInstance(alg).digest(b).contentEquals(exp))ok=false};out+="Integrità SOD DG letti: "+if(n==0)"NON_VERIFICATO" else if(ok)"PASS" else "FAIL"}catch(_:Exception){out+="Integrità SOD DG letti: NON_VERIFICATO"};out+="Firma SOD: NON_VERIFICATO";out+="Catena CSCA: NON_VERIFICATO (nessun trust store)"}
   out+="CA/AA: NON_VERIFICATO"
  }catch(e:Exception){out+="Sessione: ERRORE "+e.javaClass.simpleName}
  finally{try{service?.close()}catch(_:Exception){};try{isoDep.close()}catch(_:Exception){};out+="Tempo totale: "+(System.currentTimeMillis()-started)+"ms"}
  return out.joinToString("\n")
 }
}
