package com.shaneblackburn.progallery.core.model

/**
 * Case-insensitive "natural" string ordering, so "IMG_2.jpg" sorts before "IMG_10.jpg".
 * Digit runs are compared numerically (without overflow, by length then lexically).
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
                // Equal values: fewer leading zeros first, for a stable total order.
                val zeros = (i - startA) - (j - startB)
                if (zeros != 0) return zeros
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la.compareTo(lb)
                i++
                j++
            }
        }
        val rem = (a.length - i) - (b.length - j)
        if (rem != 0) return rem
        // Same ignoring case: fall back to case-sensitive for determinism.
        return a.compareTo(b)
    }
}
