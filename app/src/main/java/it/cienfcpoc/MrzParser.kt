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
    val td1FillerPos1Present:Int, val td1FillerPos1Absent:Int
)

object MrzParser {
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
        val l2s=lines.filter{it.length>=15&&Regex("^\\d{7}[MF<X]\\d{7}.*$").matches(it)}
        if(l1s.isNotEmpty()||l2s.isNotEmpty())attempted=if(attempted=="TD3")"TD3+TD1" else "TD1"
        var pairs=0;val pc=IntArray(4);var fdoc=0;var fbirth=0;var fexp=0
        var nonNum=0;var wrong=0;var fillYes=0;var fillNo=0
        val valid=linkedMapOf<String,MrzAccessData>()
        for(l1 in l1s){
            if(l1[1]=='<')fillYes++ else fillNo++
            val check=l1[14]
            val docOk=validField(l1.substring(5,14),check)
            if(!check.isDigit())nonNum++ else if(!docOk)wrong++
            for(l2 in l2s){
                pairs++
                val birthOk=validField(l2.substring(0,6),l2[6]);val expOk=validField(l2.substring(8,14),l2[14])
                val n=listOf(docOk,birthOk,expOk).count{it};pc[n]++
                if(!docOk)fdoc++;if(!birthOk)fbirth++;if(!expOk)fexp++
                if(n==3){val d=MrzAccessData(l1.substring(5,14).replace("<",""),l2.substring(0,6),l2.substring(8,14));valid[d.documentNumber+"|"+d.birthYYMMDD+"|"+d.expiryYYMMDD]=d}
            }
        }
        val td1=valid.values.singleOrNull()
        val diag=MrzFrameDiagnostic(candidates.map{it.length},raw.lines().count{it.isNotBlank()},attempted,removed,fillerLengths,
            allL1.size,l1s.size,l2s.size,pairs,pc,fdoc,fbirth,fexp,t3d,t3b,t3e,nonNum,wrong,fillYes,fillNo)
        return (td1?:td3Result) to diag
    }
    private fun validField(value:String,check:Char):Boolean{
        if(!check.isDigit())return false
        val w=intArrayOf(7,3,1);return value.mapIndexed{i,c->mrzValue(c)*w[i%3]}.sum()%10==check.digitToInt()
    }
    private fun mrzValue(c:Char)=when(c){in '0'..'9'->c-'0';in 'A'..'Z'->c-'A'+10;'<'->0;else->99}
}
