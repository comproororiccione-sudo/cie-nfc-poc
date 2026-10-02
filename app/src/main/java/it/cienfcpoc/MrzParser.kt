package it.cienfcpoc

data class MrzAccessData(val documentNumber:String,val birthYYMMDD:String,val expiryYYMMDD:String)

object MrzParser {
    fun parse(raw:String):MrzAccessData? {
        val lines=raw.uppercase()
            .lines()
            .map { it.replace(" ", "").replace(Regex("[^A-Z0-9<]"), "") }
            .filter { it.length >= 25 }

        for (line in lines) {
            parseTd3Line2(line)?.let { return it }
            parseTd1Line1And2(lines, line)?.let { return it }
        }
        return null
    }

    private fun parseTd3Line2(line:String):MrzAccessData? {
        if (line.length < 44) return null
        val l=line.take(44)
        val doc=l.substring(0,9)
        val docCd=l[9]
        val birth=l.substring(13,19)
        val birthCd=l[19]
        val expiry=l.substring(21,27)
        val expiryCd=l[27]
        if (!validField(doc,docCd) || !validField(birth,birthCd) || !validField(expiry,expiryCd)) return null
        return MrzAccessData(doc.replace("<",""),birth,expiry)
    }

    private fun parseTd1Line1And2(lines:List<String>, line1:String):MrzAccessData? {
        if (line1.length < 30 || line1[0] !in setOf('I','A','C')) return null
        val idx=lines.indexOf(line1)
        if (idx<0 || idx+1>=lines.size) return null
        val l1=line1.take(30); val l2=lines[idx+1]
        if (l2.length<30) return null
        val doc=l1.substring(5,14); val docCd=l1[14]
        val birth=l2.substring(0,6); val birthCd=l2[6]
        val expiry=l2.substring(8,14); val expiryCd=l2[14]
        if (!validField(doc,docCd)||!validField(birth,birthCd)||!validField(expiry,expiryCd)) return null
        return MrzAccessData(doc.replace("<",""),birth,expiry)
    }

    private fun validField(value:String, check:Char):Boolean {
        if (!check.isDigit()) return false
        val weights=intArrayOf(7,3,1)
        val sum=value.mapIndexed { i,c -> mrzValue(c)*weights[i%3] }.sum()
        return sum%10 == check.digitToInt()
    }

    private fun mrzValue(c:Char)=when(c){
        in '0'..'9'->c-'0'
        in 'A'..'Z'->c-'A'+10
        '<'->0
        else->99
    }
}
