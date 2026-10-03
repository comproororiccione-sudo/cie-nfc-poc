package it.cienfcpoc

data class MrzAccessData(val documentNumber:String,val birthYYMMDD:String,val expiryYYMMDD:String)

data class MrzFrameDiagnostic(
    val candidateLengths:List<Int>,
    val mlKitLineCount:Int,
    val attemptedFormat:String,
    val failedChecks:Set<String>,
    val removedChars:Int
)

object MrzParser {
    fun parse(raw:String):MrzAccessData? = parseWithDiagnostic(raw).first

    fun parseWithDiagnostic(raw:String):Pair<MrzAccessData?,MrzFrameDiagnostic> {
        var removed=0
        val lines=raw.uppercase().lines().map { source ->
            val noSpaces=source.replace(" ","")
            val cleaned=noSpaces.replace(Regex("[^A-Z0-9<]"),"")
            removed += noSpaces.length-cleaned.length
            cleaned
        }
        val candidates=lines.filter { it.length>=10 }
        val parseable=lines.filter { it.length>=25 }
        var attempted="NESSUNO"
        val failed=linkedSetOf<String>()

        for((index,line) in parseable.withIndex()){
            if(line.length>=44){
                attempted="TD3"
                val l=line.take(44)
                val checks=linkedMapOf(
                    "documentNumber" to validField(l.substring(0,9),l[9]),
                    "birthDate" to validField(l.substring(13,19),l[19]),
                    "expiryDate" to validField(l.substring(21,27),l[27])
                )
                checks.filterValues{!it}.keys.forEach{failed+=it}
                if(checks.values.all{it}) return MrzAccessData(l.substring(0,9).replace("<",""),l.substring(13,19),l.substring(21,27)) to
                    MrzFrameDiagnostic(candidates.map{it.length},raw.lines().count{it.isNotBlank()},"TD3",failed,removed)
            }
            if(line.length>=30 && line[0] in setOf('I','A','C')){
                attempted=if(attempted=="TD3")"TD3+TD1" else "TD1"
                val next=parseable.getOrNull(index+1)
                if(next!=null && next.length>=30){
                    val l1=line.take(30); val l2=next.take(30)
                    val checks=linkedMapOf(
                        "documentNumber" to validField(l1.substring(5,14),l1[14]),
                        "birthDate" to validField(l2.substring(0,6),l2[6]),
                        "expiryDate" to validField(l2.substring(8,14),l2[14])
                    )
                    checks.filterValues{!it}.keys.forEach{failed+=it}
                    if(checks.values.all{it}) return MrzAccessData(l1.substring(5,14).replace("<",""),l2.substring(0,6),l2.substring(8,14)) to
                        MrzFrameDiagnostic(candidates.map{it.length},raw.lines().count{it.isNotBlank()},"TD1",failed,removed)
                }
            }
        }
        return null to MrzFrameDiagnostic(candidates.map{it.length},raw.lines().count{it.isNotBlank()},attempted,failed,removed)
    }

    private fun validField(value:String,check:Char):Boolean{
        if(!check.isDigit())return false
        val weights=intArrayOf(7,3,1)
        val sum=value.mapIndexed{i,c->mrzValue(c)*weights[i%3]}.sum()
        return sum%10==check.digitToInt()
    }

    private fun mrzValue(c:Char)=when(c){
        in '0'..'9'->c-'0'
        in 'A'..'Z'->c-'A'+10
        '<'->0
        else->99
    }
}
