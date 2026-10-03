package it.cienfcpoc

data class MrzAccessData(val documentNumber:String,val birthYYMMDD:String,val expiryYYMMDD:String)

data class MrzFrameDiagnostic(
    val candidateLengths:List<Int>, val mlKitLineCount:Int, val attemptedFormat:String,
    val removedChars:Int, val fillerLineLengths:List<Int>,
    val td1Line1Total:Int, val td1Line1Plausible:Int, val td1Line2Count:Int,
    val td1PairsTried:Int, val td1PairCheckCounts:IntArray,
    val td1FailedDoc:Int, val td1FailedBirth:Int, val td1FailedExpiry:Int,
    val td3FailedDoc:Int, val td3FailedBirth:Int, val td3FailedExpiry:Int,
    val td1CheckDigitNonNumeric:Int, val td1CheckDigitWrong:Int,
    val td1FillerPos1Present:Int, val td1FillerPos1Absent:Int,
    val td1NonNumericWithFiller:Int, val td1NonNumericWithoutFiller:Int,
    val td1Pos14Filler:Int, val td1Pos14DigitLikeLetter:Int, val td1Pos14OtherLetter:Int,
    val td1SuccessRaw:Int, val td1SuccessA:Int, val td1SuccessB:Int, val td1SuccessAB:Int
)

object MrzParser {
    private val digitLike=mapOf('O' to '0','Q' to '0','D' to '0','I' to '1','L' to '1','Z' to '2','S' to '5','G' to '6','B' to '8')
    fun parse(raw:String)=parseWithDiagnostic(raw).first
    fun parseWithDiagnostic(raw:String):Pair<MrzAccessData?,MrzFrameDiagnostic>{
        var removed=0
        val lines=raw.uppercase().lines().map{source->
            val noSpaces=source.replace(" ","")
            val cleaned=noSpaces.replace(Regex("[^A-Z0-9<]"),"")
            removed+=noSpaces.length-cleaned.length; cleaned
        }
        val candidates=lines.filter{it.length>=10}
        val fillerLengths=lines.filter{it.contains("<")}.map{it.length}
        var attempted="NESSUNO"
        var t3d=0;var t3b=0;var t3e=0
        var td3Result:MrzAccessData?=null
        for(line in lines.filter{it.length>=44}){
            attempted="TD3";val l=line.take(44)
            val d=validField(l.substring(0,9),l[9]);val b=validField(l.substring(13,19),l[19]);val e=validField(l.substring(21,27),l[27])
            if(!d)t3d++;if(!b)t3b++;if(!e)t3e++
            if(d&&b&&e)td3Result=MrzAccessData(l.substring(0,9).replace("<",""),l.substring(13,19),l.substring(21,27))
        }

        val allL1=lines.filter{it.length>=15&&it[0] in setOf('I','A','C')}
        val l1s=allL1.filter{it.contains("<")&&it.substring(1,4).all{c->c in 'A'..'Z'||c=='<'}}
        val l2Regex=Regex("^[0-9OQDILZSGB]{7}[MF<X][0-9OQDILZSGB]{7}.*$")
        val l2s=lines.filter{it.length>=15&&l2Regex.matches(it)}
        if(l1s.isNotEmpty()||l2s.isNotEmpty())attempted=if(attempted=="TD3")"TD3+TD1" else "TD1"
        var pairs=0;val pc=IntArray(4);var fdoc=0;var fbirth=0;var fexp=0
        var nonNum=0;var wrong=0;var fillYes=0;var fillNo=0
        var nnFill=0;var nnNoFill=0;var p14Filler=0;var p14Similar=0;var p14Other=0
        var successRaw=0;var successA=0;var successB=0;var successAB=0
        val valid=linkedMapOf<String,MrzAccessData>()
        for(rawL1 in l1s){
            val hasFiller=rawL1[1]=='<'
            if(hasFiller)fillYes++ else fillNo++
            val rawCheck=rawL1[14]
            val rawDocOk=validField(rawL1.substring(5,14),rawCheck)
            if(!rawCheck.isDigit()){
                nonNum++;if(hasFiller)nnFill++ else nnNoFill++
                when{rawCheck=='<'->p14Filler++;digitLike.containsKey(rawCheck)->p14Similar++;rawCheck in 'A'..'Z'->p14Other++}
            }else if(!rawDocOk)wrong++
            val l1Variants=mutableListOf(Pair(rawL1,false))
            if(!hasFiller&&rawL1.substring(1,4).all{it in 'A'..'Z'})l1Variants+=Pair(rawL1.substring(0,1)+"<"+rawL1.substring(1),true)
            for(l2Raw in l2s){
                pairs++
                val rawBirthOk=validField(l2Raw.substring(0,6),l2Raw[6])
                val rawExpiryOk=validField(l2Raw.substring(8,14),l2Raw[14])
                val n=listOf(rawDocOk,rawBirthOk,rawExpiryOk).count{it};pc[n]++
                if(!rawDocOk)fdoc++;if(!rawBirthOk)fbirth++;if(!rawExpiryOk)fexp++
                val successes=mutableListOf<Pair<String,MrzAccessData>>()
                for((l1,usedA) in l1Variants)for(useB in listOf(false,true)){
                    val check=if(useB)numericChar(l1[14]) else l1[14]
                    val birth=if(useB)numericString(l2Raw.substring(0,6)) else l2Raw.substring(0,6)
                    val birthCheck=if(useB)numericChar(l2Raw[6]) else l2Raw[6]
                    val expiry=if(useB)numericString(l2Raw.substring(8,14)) else l2Raw.substring(8,14)
                    val expiryCheck=if(useB)numericChar(l2Raw[14]) else l2Raw[14]
                    if(check==null||birth==null||birthCheck==null||expiry==null||expiryCheck==null)continue
                    if(validField(l1.substring(5,14),check)&&validField(birth,birthCheck)&&validField(expiry,expiryCheck)){
                        val data=MrzAccessData(l1.substring(5,14).replace("<",""),birth,expiry)
                        val kind=when{usedA&&useB->"AB";usedA->"A";useB->"B";else->"RAW"}
                        successes+=Pair(kind,data)
                    }
                }
                val distinct=successes.map{it.second}.distinct()
                if(distinct.size==1){
                    val data=distinct.single();valid[data.documentNumber+"|"+data.birthYYMMDD+"|"+data.expiryYYMMDD]=data
                    val kinds=successes.map{it.first}.toSet()
                    when{"RAW" in kinds->successRaw++;"A" in kinds->successA++;"B" in kinds->successB++;else->successAB++}
                }
            }
        }
        val td1=valid.values.singleOrNull()
        val diag=MrzFrameDiagnostic(candidates.map{it.length},raw.lines().count{it.isNotBlank()},attempted,removed,fillerLengths,
            allL1.size,l1s.size,l2s.size,pairs,pc,fdoc,fbirth,fexp,t3d,t3b,t3e,nonNum,wrong,fillYes,fillNo,
            nnFill,nnNoFill,p14Filler,p14Similar,p14Other,successRaw,successA,successB,successAB)
        return (td1?:td3Result) to diag
    }
    private fun numericChar(c:Char):Char?=if(c.isDigit())c else digitLike[c]
    private fun numericString(s:String):String?{
        val out=StringBuilder()
        for(c in s){val n=numericChar(c)?:return null;out.append(n)}
        return out.toString()
    }
    private fun validField(value:String,check:Char):Boolean{
        if(!check.isDigit())return false
        val w=intArrayOf(7,3,1);return value.mapIndexed{i,c->mrzValue(c)*w[i%3]}.sum()%10==check.digitToInt()
    }
    private fun mrzValue(c:Char)=when(c){in '0'..'9'->c-'0';in 'A'..'Z'->c-'A'+10;'<'->0;else->99}
}
