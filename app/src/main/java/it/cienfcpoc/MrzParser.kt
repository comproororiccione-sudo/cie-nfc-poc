package it.cienfcpoc

data class MrzAccessData(val documentNumber:String,val birthYYMMDD:String,val expiryYYMMDD:String)

data class MrzFrameDiagnostic(
    val candidateLengths:List<Int>,
    val mlKitLineCount:Int,
    val attemptedFormat:String,
    val failedChecks:Set<String>,
    val removedChars:Int,
    val fillerLineLengths:List<Int>,
    val td1Line1Count:Int,
    val td1Line2Count:Int,
    val td1PairsTried:Int,
    val td1PairCheckCounts:IntArray,
    val td1Line1Missing:Boolean,
    val td1Line2Missing:Boolean
)

object MrzParser {
    fun parse(raw:String):MrzAccessData?=parseWithDiagnostic(raw).first

    fun parseWithDiagnostic(raw:String):Pair<MrzAccessData?,MrzFrameDiagnostic>{
        var removed=0
        val lines=raw.uppercase().lines().map{source->
            val noSpaces=source.replace(" ","")
            val cleaned=noSpaces.replace(Regex("[^A-Z0-9<]"),"")
            removed+=noSpaces.length-cleaned.length
            cleaned
        }
        val candidates=lines.filter{it.length>=10}
        val fillerLengths=lines.filter{it.contains("<")}.map{it.length}
        val failed=linkedSetOf<String>()
        var attempted="NESSUNO"

        // TD3 remains strict: this change targets only TD1.
        for(line in lines.filter{it.length>=44}){
            attempted="TD3"
            val l=line.take(44)
            val checks=linkedMapOf(
                "documentNumber" to validField(l.substring(0,9),l[9]),
                "birthDate" to validField(l.substring(13,19),l[19]),
                "expiryDate" to validField(l.substring(21,27),l[27])
            )
            checks.filterValues{!it}.keys.forEach{failed+=it}
            if(checks.values.all{it})return MrzAccessData(l.substring(0,9).replace("<",""),l.substring(13,19),l.substring(21,27)) to
                diag(candidates,raw,attempted,failed,removed,fillerLengths,0,0,0,IntArray(4),false,false)
        }

        val line1s=lines.filter{it.length>=15 && it[0] in setOf('I','A','C')}
        val line2Regex=Regex("^\\d{7}[MF<X]\\d{7}.*$")
        val line2s=lines.filter{it.length>=15 && line2Regex.matches(it)}
        if(line1s.isNotEmpty()||line2s.isNotEmpty())attempted=if(attempted=="TD3")"TD3+TD1" else "TD1"

        var pairs=0
        val pairChecks=IntArray(4)
        val valid=linkedMapOf<String,MrzAccessData>()
        for(l1 in line1s)for(l2 in line2s){
            pairs++
            val docOk=validField(l1.substring(5,14),l1[14])
            val birthOk=validField(l2.substring(0,6),l2[6])
            val expiryOk=validField(l2.substring(8,14),l2[14])
            val n=listOf(docOk,birthOk,expiryOk).count{it}
            pairChecks[n]++
            if(!docOk)failed+="documentNumber"
            if(!birthOk)failed+="birthDate"
            if(!expiryOk)failed+="expiryDate"
            if(n==3){
                val data=MrzAccessData(l1.substring(5,14).replace("<",""),l2.substring(0,6),l2.substring(8,14))
                valid[data.documentNumber+"|"+data.birthYYMMDD+"|"+data.expiryYYMMDD]=data
            }
        }
        val result=valid.values.singleOrNull()
        return result to diag(candidates,raw,attempted,failed,removed,fillerLengths,line1s.size,line2s.size,pairs,pairChecks,line1s.isEmpty(),line2s.isEmpty())
    }

    private fun diag(candidates:List<String>,raw:String,attempted:String,failed:Set<String>,removed:Int,fillerLengths:List<Int>,l1:Int,l2:Int,pairs:Int,pairChecks:IntArray,l1Missing:Boolean,l2Missing:Boolean)=
        MrzFrameDiagnostic(candidates.map{it.length},raw.lines().count{it.isNotBlank()},attempted,failed,removed,fillerLengths,l1,l2,pairs,pairChecks,l1Missing,l2Missing)

    private fun validField(value:String,check:Char):Boolean{
        if(!check.isDigit())return false
        val weights=intArrayOf(7,3,1)
        val sum=value.mapIndexed{i,c->mrzValue(c)*weights[i%3]}.sum()
        return sum%10==check.digitToInt()
    }
    private fun mrzValue(c:Char)=when(c){in '0'..'9'->c-'0';in 'A'..'Z'->c-'A'+10;'<'->0;else->99}
}
